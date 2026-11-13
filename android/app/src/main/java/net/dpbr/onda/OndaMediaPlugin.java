package net.dpbr.onda;

import android.content.Intent;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "OndaMedia")
public class OndaMediaPlugin extends Plugin {
    @PluginMethod
    public void ping(PluginCall call) {
        JSObject result = new JSObject();
        result.put("ok", true);
        result.put("platform", "android");
        result.put("version", "0.0.1");
        call.resolve(result);
    }

    @PluginMethod
    public void startCallService(PluginCall call) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startCallWithPermissions();
        } else {
            Intent intent = new Intent(getContext(), CallService.class)
                .setAction(CallService.ACTION_START);
            ContextCompat.startForegroundService(getContext(), intent);
        }
        call.resolve(new JSObject().put("started", true));
    }

    @PluginMethod
    public void stopCallService(PluginCall call) {
        Intent intent = new Intent(getContext(), CallService.class)
            .setAction(CallService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve(new JSObject().put("stopped", true));
    }
}
