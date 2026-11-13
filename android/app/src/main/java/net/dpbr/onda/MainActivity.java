package net.dpbr.onda;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndaMediaPlugin.class);
        super.onCreate(savedInstanceState);
        installSystemBarScrims();

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
                "const top = " + top + ";" +
                "const bottom = " + bottom + ";" +
                "let style = document.getElementById('onda-mobile-safe-area');" +
                "if (!style) { style = document.createElement('style'); style.id = 'onda-mobile-safe-area'; document.head.appendChild(style); }" +
                "style.textContent = 'html.onda-mobile body{padding-top:' + top + 'px !important;padding-bottom:' + bottom + 'px !important;} html.onda-mobile .site-header{top:' + top + 'px !important;} html.onda-mobile .room-app{height:calc(100vh - ' + (top + bottom) + 'px) !important;}';" +
                "document.documentElement.classList.add('onda-mobile');" +
            "})();";

        getBridge().getWebView().evaluateJavascript(script, null);
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

        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }
}
