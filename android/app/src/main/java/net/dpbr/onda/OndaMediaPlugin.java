package net.dpbr.onda;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.List;
import java.util.Map;

@CapacitorPlugin(
    name = "OndaMedia",
    permissions = @Permission(alias = "camera", strings = { Manifest.permission.CAMERA })
)
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

        JSObject cameraQuality = call.getObject("cameraQuality");
        int cameraWidth = cameraQuality != null ? cameraQuality.optInt("width", 1280) : 1280;
        int cameraHeight = cameraQuality != null ? cameraQuality.optInt("height", 720) : 720;
        int cameraFrameRate = cameraQuality != null ? cameraQuality.optInt("frameRate", 30) : 30;
        int cameraBitrate = cameraQuality != null ? cameraQuality.optInt("bitrateKbps", 1500) : 1500;

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startCallWithPermissions(
                url,
                token,
                call.getString("roomId"),
                call.getString("guestId"),
                call.getString("presenceUrl"),
                call.getString("guestName"),
                call.getInt("avatarTone", 0),
                cameraWidth,
                cameraHeight,
                cameraFrameRate,
                cameraBitrate
            );
        } else {
            Intent intent = new Intent(getContext(), CallService.class)
                .setAction(CallService.ACTION_START)
                .putExtra(CallService.EXTRA_URL, url)
                .putExtra(CallService.EXTRA_TOKEN, token)
                .putExtra(CallService.EXTRA_ROOM_ID, call.getString("roomId"))
                .putExtra(CallService.EXTRA_GUEST_ID, call.getString("guestId"))
                .putExtra(CallService.EXTRA_PRESENCE_URL, call.getString("presenceUrl"))
                .putExtra(CallService.EXTRA_GUEST_NAME, call.getString("guestName"))
                .putExtra(CallService.EXTRA_AVATAR_TONE, call.getInt("avatarTone", 0))
                .putExtra(CallService.EXTRA_CAMERA_WIDTH, cameraWidth)
                .putExtra(CallService.EXTRA_CAMERA_HEIGHT, cameraHeight)
                .putExtra(CallService.EXTRA_CAMERA_FRAME_RATE, cameraFrameRate)
                .putExtra(CallService.EXTRA_CAMERA_BITRATE, cameraBitrate)
                .putExtra(CallService.EXTRA_START_NONCE, CallService.getProcessNonce());
            ContextCompat.startForegroundService(getContext(), intent);
        }
        call.resolve(new JSObject().put("started", true));
    }

    @PluginMethod
    public void setTheme(PluginCall call) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateSystemBarTheme(call.getString("theme"));
        }
        call.resolve();
    }

    @PluginMethod
    public void shareLink(PluginCall call) {
        String url = call.getString("url");
        String title = call.getString("title", "Onda");
        String text = call.getString("text", "");

        if (url == null || url.isEmpty()) {
            call.reject("Link da sala indisponível.");
            return;
        }

        String message = text.isEmpty() ? url : text + "\n" + url;
        Intent send = new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, message);
        Intent chooser = Intent.createChooser(send, "Compartilhar sala");

        try {
            if (getActivity() != null) {
                getActivity().startActivity(chooser);
            } else {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(chooser);
            }
            call.resolve(new JSObject().put("shared", true));
        } catch (Exception error) {
            call.reject("Não foi possível abrir o compartilhamento.", error);
        }
    }

    @PluginMethod
    public void getAudioDevices(PluginCall call) {
        List<Map<String, Object>> devices = CallService.getAudioDevices();
        if (devices == null) {
            call.reject("A chamada nativa não está ativa.");
            return;
        }

        JSArray result = new JSArray();
        for (Map<String, Object> device : devices) {
            JSObject item = new JSObject();
            item.put("id", device.get("id"));
            item.put("label", device.get("label"));
            item.put("selected", device.get("selected"));
            result.put(item);
        }
        call.resolve(new JSObject().put("devices", result));
    }

    @PluginMethod
    public void selectAudioDevice(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("Rota de áudio inválida.");
            return;
        }

        boolean selected = CallService.selectAudioDevice(id);
        if (!selected) {
            call.reject("Rota de áudio indisponível.");
            return;
        }
        call.resolve(new JSObject().put("selected", true));
    }

    @PluginMethod
    public void getOutputVolume(PluginCall call) {
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int percent = max > 0 ? Math.round(current * 100f / max) : 0;
        call.resolve(new JSObject().put("percent", percent));
    }

    @PluginMethod
    public void setOutputVolume(PluginCall call) {
        Integer percent = call.getInt("percent", 100);
        int clamped = Math.max(0, Math.min(100, percent == null ? 100 : percent));
        AudioManager audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int index = Math.round(max * (clamped / 100f));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
        call.resolve(new JSObject().put("percent", clamped));
    }

    @PluginMethod
    public void getMic(PluginCall call) {
        call.resolve(new JSObject().put("enabled", CallService.isMicrophoneEnabled()));
    }

    @PluginMethod
    public void setCamera(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback");
            return;
        }

        applyCameraState(call);
    }

    @PermissionCallback
    private void cameraPermissionCallback(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) {
            applyCameraState(call);
            return;
        }

        call.reject("A permissão de câmera é necessária para ligar o vídeo.");
    }

    private void applyCameraState(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        boolean next = Boolean.TRUE.equals(enabled);
        CallService.setCamera(next);
        call.resolve(new JSObject()
            .put("enabled", next)
            .put("running", CallService.isCameraEnabled()));
    }

    @PluginMethod
    public void getCamera(PluginCall call) {
        call.resolve(new JSObject()
            .put("enabled", CallService.isCameraEnabled())
            .put("running", CallService.isCameraEnabled()));
    }

    @PluginMethod
    public void setCameraQuality(PluginCall call) {
        int width = call.getInt("width", 1280);
        int height = call.getInt("height", 720);
        int frameRate = call.getInt("frameRate", 30);
        int bitrate = call.getInt("bitrateKbps", 1500);
        CallService.setCameraQuality(width, height, frameRate, bitrate);
        call.resolve(new JSObject().put("applied", true));
    }

    @PluginMethod
    public void getCameraOptions(PluginCall call) {
        List<Map<String, Object>> cameras = CallService.getCameraDevices();
        JSArray devices = new JSArray();
        String activeId = CallService.getActiveCameraId();
        String activeFacing = "";

        for (Map<String, Object> camera : cameras) {
            JSObject item = new JSObject();
            item.put("deviceId", camera.get("deviceId"));
            item.put("label", camera.get("label"));
            item.put("facing", camera.get("facing"));
            devices.put(item);

            if (activeId != null && activeId.equals(camera.get("deviceId"))) {
                Object facing = camera.get("facing");
                activeFacing = facing == null ? "" : facing.toString();
            }
        }

        call.resolve(new JSObject()
            .put("devices", devices)
            .put("activeDeviceId", activeId == null ? "" : activeId)
            .put("facingMode", activeFacing)
            .put("torchSupported", CallService.isTorchSupported())
            .put("torchOn", CallService.isTorchOn()));
    }

    @PluginMethod
    public void switchCamera(PluginCall call) {
        call.resolve(new JSObject().put("changed", CallService.switchCamera(call.getString("deviceId"))));
    }

    @PluginMethod
    public void setCameraTorch(PluginCall call) {
        Boolean enabled = call.getBoolean("enabled", false);
        call.resolve(new JSObject().put("changed", CallService.setTorch(Boolean.TRUE.equals(enabled))));
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
