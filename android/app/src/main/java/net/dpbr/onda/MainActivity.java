package net.dpbr.onda;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private static final int CALL_PERMISSIONS_REQUEST = 42;
    private boolean bridgeProbeScheduled;
    private boolean startCallWhenGranted;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndaMediaPlugin.class);
        super.onCreate(savedInstanceState);

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debuggable && getIntent().getBooleanExtra("onda_test_call", false)) {
            startCallWithPermissions();
        }
    }

    public void startCallWithPermissions() {
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
}
