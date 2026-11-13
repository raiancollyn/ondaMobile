package net.dpbr.onda;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;
import io.livekit.android.renderer.SurfaceViewRenderer;
import livekit.org.webrtc.RendererCommon;

public class MainActivity extends BridgeActivity {
    private static final int CALL_PERMISSIONS_REQUEST = 42;
    private static final int DARK_SCRIM = Color.rgb(12, 25, 22);
    private static final int LIGHT_SCRIM = Color.rgb(247, 243, 235);
    private boolean bridgeProbeScheduled;
    private boolean startCallWhenGranted;
    private String pendingCallUrl;
    private String pendingCallToken;
    private String pendingCallRoomId;
    private String pendingCallGuestId;
    private String pendingCallPresenceUrl;
    private String pendingCallGuestName;
    private int pendingCallAvatarTone;
    private View statusBarScrim;
    private View navigationBarScrim;
    private boolean insetsListenerInstalled;
    private int appliedTopInset = -1;
    private int appliedBottomInset = -1;
    private SurfaceViewRenderer cameraPreview;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndaMediaPlugin.class);
        super.onCreate(savedInstanceState);
        installSystemBarScrims();
        installCameraPreview();
        installWindowInsetsListener();
        getWindow().getDecorView().post(this::applySafeAreaInsets);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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
        pendingCallUrl = url;
        pendingCallToken = token;
        pendingCallRoomId = roomId;
        pendingCallGuestId = guestId;
        pendingCallPresenceUrl = presenceUrl;
        pendingCallGuestName = guestName;
        pendingCallAvatarTone = avatarTone;

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
        ContextCompat.startForegroundService(this, callIntent);
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
    public void onStart() {
        super.onStart();

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
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            updateSystemBarScrims(top, bottom);
            applyWebViewInsets(top, bottom);
            return insets;
        });
    }

    private void installCameraPreview() {
        if (cameraPreview != null) {
            return;
        }

        cameraPreview = new SurfaceViewRenderer(this);
        cameraPreview.setVisibility(View.GONE);
        cameraPreview.setMirror(true);
        cameraPreview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        cameraPreview.setEnableHardwareScaler(true);
        cameraPreview.setZOrderMediaOverlay(true);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(116), dp(154));
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.bottomMargin = dp(96);
        params.rightMargin = dp(12);
        addContentView(cameraPreview, params);

        CallService.attachCameraPreview(cameraPreview);
        CallService.cameraStateListener = enabled -> runOnUiThread(() -> {
            if (cameraPreview != null) {
                cameraPreview.setVisibility(enabled ? View.VISIBLE : View.GONE);
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

    @Override
    public void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            CallService.setCamera(false);
        }
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
