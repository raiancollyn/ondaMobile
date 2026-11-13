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
    public void startCall(PluginCall call) {
        String url = call.getString("url");
        String token = call.getString("token");
        if (url == null || token == null || url.isEmpty() || token.isEmpty()) {
            call.reject("URL e token do LiveKit são obrigatórios.");
            return;
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startCallWithPermissions(
                url,
                token,
                call.getString("roomId"),
                call.getString("guestId"),
                call.getString("presenceUrl")
            );
        } else {
            Intent intent = new Intent(getContext(), CallService.class)
                .setAction(CallService.ACTION_START)
                .putExtra(CallService.EXTRA_URL, url)
                .putExtra(CallService.EXTRA_TOKEN, token)
                .putExtra(CallService.EXTRA_ROOM_ID, call.getString("roomId"))
                .putExtra(CallService.EXTRA_GUEST_ID, call.getString("guestId"))
                .putExtra(CallService.EXTRA_PRESENCE_URL, call.getString("presenceUrl"));
            ContextCompat.startForegroundService(getContext(), intent);
        }
        call.resolve(new JSObject().put("started", true));
    }

    @PluginMethod
    public void setMic(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", true);
        CallService.setMicrophone(Boolean.TRUE.equals(enabled));
        call.resolve(new JSObject().put("enabled", Boolean.TRUE.equals(enabled)));
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
