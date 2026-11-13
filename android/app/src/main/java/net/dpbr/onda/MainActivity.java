package net.dpbr.onda;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private boolean bridgeProbeScheduled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(OndaMediaPlugin.class);
        super.onCreate(savedInstanceState);
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
