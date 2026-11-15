package net.dpbr.onda;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import io.livekit.android.renderer.TextureViewRenderer;
import livekit.org.webrtc.RendererCommon;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends BridgeActivity {
    private static final int CALL_PERMISSIONS_REQUEST = 42;
    private static final int SCREEN_SHARE_REQUEST = 43;
    private static final int CHAT_NOTIFICATION_PERMISSION_REQUEST = 44;
    private static final String CHAT_NOTIFICATION_GROUP = "onda_chat";
    private static final int CHAT_NOTIFICATION_SUMMARY_ID = 1999;
    private final Set<Integer> activeChatNotificationIds =
        Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger chatNotificationCounter = new AtomicInteger(2000);
    private static final String CHAT_NOTIFICATION_CHANNEL = "onda_messages";
    private static final int DARK_SCRIM = Color.rgb(12, 25, 22);
    private static final int LIGHT_SCRIM = Color.rgb(247, 243, 235);
    private boolean bridgeProbeScheduled;
    private String pendingDeepLinkUrl;
    private String lastLoadedRoomUrl;
    private boolean startCallWhenGranted;
    private String pendingCallUrl;
    private String pendingCallToken;
    private String pendingCallRoomId;
    private String pendingCallGuestId;
    private String pendingCallPresenceUrl;
    private String pendingCallGuestName;
    private int pendingCallAvatarTone;
    private int pendingCameraWidth = 1280;
    private int pendingCameraHeight = 720;
    private int pendingCameraFrameRate = 30;
    private int pendingCameraBitrate = 1500;
    private int pendingScreenWidth = 1920;
    private int pendingScreenHeight = 1080;
    private int pendingScreenFrameRate = 30;
    private int pendingScreenBitrate = 4000;
    private View statusBarScrim;
    private View navigationBarScrim;
    private boolean insetsListenerInstalled;
    private boolean isInPipMode;
    private long lastPausedAt;
    private int appliedTopInset = -1;
    private int appliedBottomInset = -1;
    private TextureViewRenderer cameraPreview;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndaMediaPlugin.class);
        super.onCreate(savedInstanceState);
        handleDeepLink(getIntent());
        CallService.dismissStaleNotification(getApplicationContext());
        installSystemBarScrims();
        installWindowInsetsListener();
        getWindow().getDecorView().post(this::applySafeAreaInsets);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && getIntent().getBooleanExtra("onda_test_camera", false)) {
            getWindow().getDecorView().postDelayed(() -> CallService.setCamera(true), 5000);
        }
        if (debuggable && getIntent().getBooleanExtra("onda_test_call", false)) {
            startCallWithPermissions(
                getIntent().getStringExtra("onda_test_url"),
                getIntent().getStringExtra("onda_test_token"),
                getIntent().getStringExtra("onda_test_room"),
                getIntent().getStringExtra("onda_test_guest"),
                getIntent().getStringExtra("onda_test_presence")
            );
        }
    }

    public void startCallWithPermissions() {
        startCallWithPermissions(null, null);
    }

    public void startCallWithPermissions(String url, String token) {
        startCallWithPermissions(url, token, null, null, null);
    }

    public void startCallWithPermissions(
        String url,
        String token,
        String roomId,
        String guestId,
        String presenceUrl
    ) {
        startCallWithPermissions(url, token, roomId, guestId, presenceUrl, null, 0);
    }

    public void startCallWithPermissions(
        String url,
        String token,
        String roomId,
        String guestId,
        String presenceUrl,
        String guestName,
        int avatarTone
    ) {
        startCallWithPermissions(
            url,
            token,
            roomId,
            guestId,
            presenceUrl,
            guestName,
            avatarTone,
            1280,
            720,
            30,
            1500
        );
    }

    public void startCallWithPermissions(
        String url,
        String token,
        String roomId,
        String guestId,
        String presenceUrl,
        String guestName,
        int avatarTone,
        int cameraWidth,
        int cameraHeight,
        int cameraFrameRate,
        int cameraBitrate
    ) {
        pendingCallUrl = url;
        pendingCallToken = token;
        pendingCallRoomId = roomId;
        pendingCallGuestId = guestId;
        pendingCallPresenceUrl = presenceUrl;
        pendingCallGuestName = guestName;
        pendingCallAvatarTone = avatarTone;
        pendingCameraWidth = cameraWidth;
        pendingCameraHeight = cameraHeight;
        pendingCameraFrameRate = cameraFrameRate;
        pendingCameraBitrate = cameraBitrate;

        String[] needed = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        };
        boolean missing = false;
        for (String permission : needed) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }

        if (missing) {
            startCallWhenGranted = true;
            ActivityCompat.requestPermissions(this, needed, CALL_PERMISSIONS_REQUEST);
            return;
        }

        startCallService();
    }

    private void startCallService() {
        Intent callIntent = new Intent(this, CallService.class)
            .setAction(CallService.ACTION_START);
        if (pendingCallUrl != null && !pendingCallUrl.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_URL, pendingCallUrl);
        }
        if (pendingCallToken != null && !pendingCallToken.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_TOKEN, pendingCallToken);
        }
        if (pendingCallRoomId != null && !pendingCallRoomId.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_ROOM_ID, pendingCallRoomId);
        }
        if (pendingCallGuestId != null && !pendingCallGuestId.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_GUEST_ID, pendingCallGuestId);
        }
        if (pendingCallPresenceUrl != null && !pendingCallPresenceUrl.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_PRESENCE_URL, pendingCallPresenceUrl);
        }
        if (pendingCallGuestName != null && !pendingCallGuestName.isEmpty()) {
            callIntent.putExtra(CallService.EXTRA_GUEST_NAME, pendingCallGuestName);
        }
        callIntent.putExtra(CallService.EXTRA_AVATAR_TONE, pendingCallAvatarTone);
        callIntent.putExtra(CallService.EXTRA_CAMERA_WIDTH, pendingCameraWidth);
        callIntent.putExtra(CallService.EXTRA_CAMERA_HEIGHT, pendingCameraHeight);
        callIntent.putExtra(CallService.EXTRA_CAMERA_FRAME_RATE, pendingCameraFrameRate);
        callIntent.putExtra(CallService.EXTRA_CAMERA_BITRATE, pendingCameraBitrate);
        callIntent.putExtra(CallService.EXTRA_START_NONCE, CallService.getProcessNonce());
        ContextCompat.startForegroundService(this, callIntent);
    }

    public boolean requestChatNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        ActivityCompat.requestPermissions(
            this,
            new String[] { Manifest.permission.POST_NOTIFICATIONS },
            CHAT_NOTIFICATION_PERMISSION_REQUEST);
        return false;
    }

    public void showChatNotification(String sender, String text, String roomCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHAT_NOTIFICATION_CHANNEL,
                "Mensagens da sala",
                NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Mensagens recebidas enquanto o Onda não está em foco.");
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("onda://sala/" + Uri.encode(roomCode)))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this,
            6,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String safeSender = sender == null || sender.isBlank() ? "Nova mensagem" : sender;
        String safeText = text == null ? "" : text;
        int notificationId = chatNotificationCounter.getAndIncrement();
        if (notificationId > 100_000) {
            chatNotificationCounter.set(2000);
            notificationId = 2000;
        }
        activeChatNotificationIds.add(notificationId);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHAT_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(safeSender)
            .setContentText(safeText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(safeText))
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setGroup(CHAT_NOTIFICATION_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        manager.notify(notificationId, builder.build());

        int count = activeChatNotificationIds.size();
        String summaryText = count == 1 ? "1 nova mensagem" : count + " novas mensagens";
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this, CHAT_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Onda")
            .setContentText(summaryText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(summaryText))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .setGroup(CHAT_NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(count)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(CHAT_NOTIFICATION_SUMMARY_ID, summary.build());
    }

    public void clearChatNotifications() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        synchronized (activeChatNotificationIds) {
            for (int id : activeChatNotificationIds) {
                manager.cancel(id);
            }
            activeChatNotificationIds.clear();
        }
        manager.cancel(CHAT_NOTIFICATION_SUMMARY_ID);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SCREEN_SHARE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && CallService.isCallActive()) {
                Intent shareIntent = new Intent(this, CallService.class)
                    .setAction(CallService.ACTION_START_SCREEN_SHARE)
                    .putExtra(CallService.EXTRA_SCREEN_RESULT_CODE, resultCode)
                    .putExtra(CallService.EXTRA_SCREEN_RESULT_DATA, data)
                    .putExtra(CallService.EXTRA_SCREEN_WIDTH, pendingScreenWidth)
                    .putExtra(CallService.EXTRA_SCREEN_HEIGHT, pendingScreenHeight)
                    .putExtra(CallService.EXTRA_SCREEN_FRAME_RATE, pendingScreenFrameRate)
                    .putExtra(CallService.EXTRA_SCREEN_BITRATE, pendingScreenBitrate);
                startService(shareIntent);
            } else if (resultCode != RESULT_OK) {
                Toast.makeText(this, "O compartilhamento de tela foi cancelado.", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    public void setScreenShareQuality(int width, int height, int frameRate, int bitrateKbps) {
        pendingScreenWidth = Math.max(320, Math.min(3840, width));
        pendingScreenHeight = Math.max(240, Math.min(2160, height));
        pendingScreenFrameRate = Math.max(1, Math.min(60, frameRate));
        pendingScreenBitrate = Math.max(300, Math.min(20_000, bitrateKbps));
    }

    public boolean requestScreenShare() {
        if (!CallService.isCallActive()) {
            Toast.makeText(this, "Entre em uma chamada antes de compartilhar a tela.", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                Toast.makeText(this, "Compartilhamento de tela indisponível.", Toast.LENGTH_SHORT).show();
                return false;
            }

            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_SHARE_REQUEST);
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir o compartilhamento de tela.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CALL_PERMISSIONS_REQUEST || !startCallWhenGranted) {
            return;
        }

        startCallWhenGranted = false;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permita microfone e notificações para a chamada.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        startCallService();
    }

    @Override
    public void onPause() {
        super.onPause();
        lastPausedAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onResume() {
        super.onResume();

        long backgroundFor = lastPausedAt > 0
            ? SystemClock.elapsedRealtime() - lastPausedAt
            : 0;
        if (backgroundFor > 1500 && CallService.isCallActive()) {
            getWindow().getDecorView().postDelayed(CallService::reassertCamera, 1500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLink(intent);
        CallService.validateCurrentRoom();
        openActiveRoomIfNeeded();
    }

    private void handleDeepLink(Intent intent) {
        Uri data = intent != null ? intent.getData() : null;
        if (data == null ||
            !"onda".equals(data.getScheme()) ||
            !"sala".equals(data.getHost())) {
            return;
        }

        java.util.List<String> segments = data.getPathSegments();
        if (segments.isEmpty() || segments.get(0).isEmpty()) {
            return;
        }

        String roomCode = segments.get(0);
        StringBuilder target = new StringBuilder("https://onda.dpbr.net/sala/")
            .append(Uri.encode(roomCode));
        String handoff = data.getQueryParameter("handoff");
        if (handoff != null && !handoff.isEmpty()) {
            target.append("?handoff=").append(Uri.encode(handoff));
        }

        String currentRoom = CallService.getCurrentRoomId();
        if (currentRoom == null || !currentRoom.equalsIgnoreCase(roomCode)) {
            CallService.stopCurrentCall();
        }
        pendingDeepLinkUrl = target.toString();
        loadPendingDeepLink();
    }

    private void loadPendingDeepLink() {
        if (pendingDeepLinkUrl == null) {
            return;
        }

        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        lastLoadedRoomUrl = pendingDeepLinkUrl;
        getBridge().getWebView().loadUrl(pendingDeepLinkUrl);
        pendingDeepLinkUrl = null;
    }

    private void openActiveRoomIfNeeded() {
        if (pendingDeepLinkUrl != null) {
            return;
        }

        String room = CallService.getCurrentRoomId();
        if (room == null || room.isEmpty() ||
            getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        String encodedRoom = Uri.encode(room);
        String currentUrl = getBridge().getWebView().getUrl();
        if (currentUrl != null && currentUrl.contains("/sala/" + encodedRoom)) {
            return;
        }

        String target = "https://onda.dpbr.net/sala/" + encodedRoom;
        if (target.equals(lastLoadedRoomUrl)) {
            return;
        }

        lastLoadedRoomUrl = target;
        getBridge().getWebView().loadUrl(target);
    }

    @Override
    public void onStart() {
        super.onStart();
        clearChatNotifications();

        loadPendingDeepLink();
        CallService.validateCurrentRoom();
        openActiveRoomIfNeeded();

        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        getBridge().getWebView().postDelayed(this::applySafeAreaInsets, 700);
        getBridge().getWebView().postDelayed(this::applySafeAreaInsets, 3500);
        getBridge().getWebView().postDelayed(this::applySafeAreaInsets, 8000);
        if (bridgeProbeScheduled) {
            return;
        }

        bridgeProbeScheduled = true;
        getBridge().getWebView().postDelayed(() -> {
            if (getBridge() == null || getBridge().getWebView() == null) {
                return;
            }

            getBridge().getWebView().evaluateJavascript(
                "window.__ondaBridge = 'pending';" +
                    "(async () => { try { const r = await window.Capacitor.Plugins.OndaMedia.ping(); window.__ondaBridge = JSON.stringify(r); } catch (error) { window.__ondaBridge = 'ERR:' + error; } })();",
                null
            );

            getBridge().getWebView().postDelayed(() -> {
                if (getBridge() == null || getBridge().getWebView() == null) {
                    return;
                }

                getBridge().getWebView().evaluateJavascript(
                    "window.__ondaBridge",
                    value -> Log.i("OndaBridge", String.valueOf(value))
                );
            }, 2500);
        }, 8000);
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            CallService.isCallActive() &&
            !isInPictureInPictureMode()) {
            try {
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
                enterPictureInPictureMode(params);
            } catch (Exception error) {
                Log.w("OndaPip", "Não foi possível entrar em picture-in-picture.", error);
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration configuration) {
        super.onPictureInPictureModeChanged(isInPip, configuration);
        isInPipMode = isInPip;

        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        getBridge().getWebView().post(() -> {
            if (getBridge() == null || getBridge().getWebView() == null) {
                return;
            }

            getBridge().getWebView().evaluateJavascript(
                "window.onda && window.onda.setPipMode(" + isInPip + ");",
                null
            );

            if (isInPip) {
                updateSystemBarScrims(0, 0);
                applyWebViewInsets(0, 0);
            } else {
                applySafeAreaInsets();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (getBridge() != null &&
            getBridge().getWebView() != null &&
            getBridge().getWebView().canGoBack()) {
            getBridge().getWebView().goBack();
            return;
        }

        super.onBackPressed();
    }

    private void applySafeAreaInsets() {
        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        if (isInPipMode) {
            updateSystemBarScrims(0, 0);
            applyWebViewInsets(0, 0);
            return;
        }

        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        int topPx = 0;
        int bottomPx = 0;
        if (insets != null) {
            topPx = insets.getInsets(WindowInsets.Type.statusBars()).top;
            bottomPx = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        }

        float density = getResources().getDisplayMetrics().density;
        int top = Math.round(topPx / density);
        int bottom = Math.round(bottomPx / density);
        updateSystemBarScrims(topPx, bottomPx);
        applyWebViewInsets(topPx, bottomPx);

        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().evaluateJavascript(
                "document.documentElement.dataset.theme || 'dark'",
                value -> {
                    String theme = value == null ? "dark" : value.replace("\"", "");
                    updateSystemBarTheme(theme);
                }
            );
        }

        String script =
            "(function () {" +
                "let style = document.getElementById('onda-mobile-safe-area');" +
                "if (!style) { style = document.createElement('style'); style.id = 'onda-mobile-safe-area'; document.head.appendChild(style); }" +
                "style.textContent = 'html.onda-mobile body{padding-top:0 !important;padding-bottom:0 !important;} html.onda-mobile .site-header{top:0 !important;} html.onda-mobile .room-app{height:100vh !important;}';" +
                "document.documentElement.classList.add('onda-mobile');" +
            "})();";

        getBridge().getWebView().evaluateJavascript(script, null);
    }

    private void applyWebViewInsets(int topPx, int bottomPx) {
        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }

        if (isInPipMode) {
            topPx = 0;
            bottomPx = 0;
        }

        if (topPx == appliedTopInset && bottomPx == appliedBottomInset) {
            return;
        }

        appliedTopInset = topPx;
        appliedBottomInset = bottomPx;
        updateCameraPreviewPosition(topPx, bottomPx);
        View webView = getBridge().getWebView();
        webView.setPadding(0, 0, 0, 0);
        View parent = (View) webView.getParent();
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup.LayoutParams params = webView.getLayoutParams();
            if (params instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams margins =
                    (android.view.ViewGroup.MarginLayoutParams) params;
                margins.topMargin = topPx;
                margins.bottomMargin = bottomPx;
                webView.setLayoutParams(margins);
            }
        }
    }

    private void installWindowInsetsListener() {
        if (insetsListenerInstalled) {
            return;
        }

        insetsListenerInstalled = true;
        View decor = getWindow().getDecorView();
        ViewCompat.setOnApplyWindowInsetsListener(decor, (view, insets) -> {
            int top = isInPipMode ? 0 : insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = isInPipMode ? 0 : insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            updateSystemBarScrims(top, bottom);
            applyWebViewInsets(top, bottom);
            return insets;
        });
    }

    private void installCameraPreview() {
        if (cameraPreview != null) {
            return;
        }

        cameraPreview = new TextureViewRenderer(this);
        cameraPreview.setMirror(true);
        cameraPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        cameraPreview.setEnableHardwareScaler(true);
        cameraPreview.setTranslationY(dp(320));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(116), dp(154));
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.bottomMargin = dp(96);
        params.rightMargin = dp(12);
        addContentView(cameraPreview, params);

        CallService.attachCameraPreview(cameraPreview);
        CallService.cameraStateListener = enabled -> runOnUiThread(() -> {
            if (cameraPreview != null) {
                cameraPreview.animate()
                    .translationY(enabled ? 0 : dp(320))
                    .setDuration(180)
                    .start();
            }
        });
    }

    private void updateCameraPreviewPosition(int topPx, int bottomPx) {
        if (cameraPreview == null || cameraPreview.getLayoutParams() == null) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) cameraPreview.getLayoutParams();
        params.bottomMargin = bottomPx + dp(92);
        params.rightMargin = dp(12);
        cameraPreview.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }


    private void installSystemBarScrims() {
        if (statusBarScrim != null) {
            return;
        }

        statusBarScrim = new View(this);
        statusBarScrim.setBackgroundColor(DARK_SCRIM);
        statusBarScrim.setClickable(false);
        addContentView(
            statusBarScrim,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        );

        navigationBarScrim = new View(this);
        navigationBarScrim.setBackgroundColor(DARK_SCRIM);
        navigationBarScrim.setClickable(false);
        addContentView(
            navigationBarScrim,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM
            )
        );
    }

    private void updateSystemBarScrims(int topPx, int bottomPx) {
        if (statusBarScrim != null) {
            FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) statusBarScrim.getLayoutParams();
            params.height = Math.max(0, topPx);
            statusBarScrim.setLayoutParams(params);
            statusBarScrim.setVisibility(topPx > 0 ? View.VISIBLE : View.GONE);
        }

        if (navigationBarScrim != null) {
            FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) navigationBarScrim.getLayoutParams();
            params.height = Math.max(0, bottomPx);
            navigationBarScrim.setLayoutParams(params);
            navigationBarScrim.setVisibility(bottomPx > 0 ? View.VISIBLE : View.GONE);
        }
    }

    public void updateSystemBarTheme(String theme) {
        runOnUiThread(() -> applySystemBarTheme(theme));
    }

    private void applySystemBarTheme(String theme) {
        boolean light = "light".equalsIgnoreCase(theme);
        int color = light ? LIGHT_SCRIM : DARK_SCRIM;
        if (statusBarScrim != null) {
            statusBarScrim.setBackgroundColor(color);
        }
        if (navigationBarScrim != null) {
            navigationBarScrim.setBackgroundColor(color);
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(color));
        getWindow().getDecorView().setBackgroundColor(color);
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setBackgroundColor(color);
        }

        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }
}
