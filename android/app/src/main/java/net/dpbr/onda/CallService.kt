package net.dpbr.onda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.room.track.video.CameraCapturerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.getCameraX
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CallService : LifecycleService() {
    companion object {
        const val ACTION_START = "net.dpbr.onda.action.START_CALL"
        const val ACTION_STOP = "net.dpbr.onda.action.STOP_CALL"
        const val ACTION_TOGGLE_MIC = "net.dpbr.onda.action.TOGGLE_MIC"
        const val ACTION_DISABLE_CAMERA = "net.dpbr.onda.action.DISABLE_CAMERA"
        const val ACTION_START_SCREEN_SHARE = "net.dpbr.onda.action.START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "net.dpbr.onda.action.STOP_SCREEN_SHARE"
        const val EXTRA_URL = "net.dpbr.onda.extra.LIVEKIT_URL"
        const val EXTRA_TOKEN = "net.dpbr.onda.extra.LIVEKIT_TOKEN"
        const val EXTRA_ROOM_ID = "net.dpbr.onda.extra.ROOM_ID"
        const val EXTRA_GUEST_ID = "net.dpbr.onda.extra.GUEST_ID"
        const val EXTRA_PRESENCE_URL = "net.dpbr.onda.extra.PRESENCE_URL"
        const val EXTRA_GUEST_NAME = "net.dpbr.onda.extra.GUEST_NAME"
        const val EXTRA_AVATAR_TONE = "net.dpbr.onda.extra.AVATAR_TONE"
        const val EXTRA_CAMERA_WIDTH = "net.dpbr.onda.extra.CAMERA_WIDTH"
        const val EXTRA_CAMERA_HEIGHT = "net.dpbr.onda.extra.CAMERA_HEIGHT"
        const val EXTRA_CAMERA_FRAME_RATE = "net.dpbr.onda.extra.CAMERA_FRAME_RATE"
        const val EXTRA_CAMERA_BITRATE = "net.dpbr.onda.extra.CAMERA_BITRATE"
        const val EXTRA_START_NONCE = "net.dpbr.onda.extra.START_NONCE"
        const val EXTRA_SCREEN_RESULT_CODE = "net.dpbr.onda.extra.SCREEN_RESULT_CODE"
        const val EXTRA_SCREEN_RESULT_DATA = "net.dpbr.onda.extra.SCREEN_RESULT_DATA"
        const val EXTRA_SCREEN_WIDTH = "net.dpbr.onda.extra.SCREEN_WIDTH"
        const val EXTRA_SCREEN_HEIGHT = "net.dpbr.onda.extra.SCREEN_HEIGHT"
        const val EXTRA_SCREEN_FRAME_RATE = "net.dpbr.onda.extra.SCREEN_FRAME_RATE"
        const val EXTRA_SCREEN_BITRATE = "net.dpbr.onda.extra.SCREEN_BITRATE"
        private const val CHANNEL_ID = "onda_call"
        private const val NOTIFICATION_ID = 1001
        private const val SCREEN_NOTIFICATION_ID = 1002

        @Volatile
        private var current: CallService? = null

        private val processNonce = java.util.UUID.randomUUID().toString()

        @JvmStatic
        fun getProcessNonce(): String = processNonce

        @Volatile
        private var pendingCameraPreview: TextureViewRenderer? = null

        @JvmStatic
        fun setMicrophone(enabled: Boolean) {
            current?.setMicrophoneEnabled(enabled)
        }

        @JvmStatic
        fun isCallActive(): Boolean = current != null

        @JvmStatic
        fun stopCurrentCall() {
            current?.stopSelf()
        }

        @JvmStatic
        fun getCurrentRoomId(): String? = current?.roomId

        @JvmStatic
        fun validateCurrentRoom() {
            current?.validateRoomAndStopIfMissing()
        }

        @JvmStatic
        fun dismissStaleNotification(context: Context) {
            val service = current
            if (service == null) {
                context.stopService(Intent(context, CallService::class.java))
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
                return
            }

            if (service.room == null && !service.connecting) {
                service.stopSelf()
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            }
        }

        @JvmStatic
        fun isMicrophoneEnabled(): Boolean = current?.microphoneEnabled ?: false

        @JvmStatic
        fun setCamera(enabled: Boolean) {
            current?.setCameraEnabled(enabled)
        }

        @JvmStatic
        fun isCameraEnabled(): Boolean = current?.cameraEnabled ?: false

        @JvmStatic
        fun isScreenShareEnabled(): Boolean = current?.screenShareEnabled ?: false

        @JvmStatic
        fun setScreenShareQuality(width: Int, height: Int, frameRate: Int, bitrateKbps: Int) {
            current?.updateScreenShareQuality(width, height, frameRate, bitrateKbps)
        }

        @JvmStatic
        fun getCameraDevices(): List<Map<String, Any?>> = current?.cameraDevices() ?: emptyList()

        @JvmStatic
        fun getActiveCameraId(): String = current?.activeCameraId ?: ""

        @JvmStatic
        fun isTorchSupported(): Boolean = current?.torchSupported() ?: false

        @JvmStatic
        fun isTorchOn(): Boolean = current?.torchEnabled ?: false

        @JvmStatic
        fun switchCamera(deviceId: String?): Boolean = current?.switchCameraTo(deviceId) ?: false

        @JvmStatic
        fun setTorch(enabled: Boolean): Boolean = current?.setTorchEnabled(enabled) ?: false

        @JvmStatic
        fun reassertCamera() {
            current?.restartCameraAfterResume()
        }

        @JvmStatic
        fun setCameraQuality(width: Int, height: Int, frameRate: Int, bitrateKbps: Int) {
            current?.updateCameraQuality(width, height, frameRate, bitrateKbps)
        }

        @JvmStatic
        fun attachCameraPreview(renderer: TextureViewRenderer?) {
            pendingCameraPreview = renderer
            current?.attachCameraRenderer(renderer)
        }

        interface CameraStateListener {
            fun onCameraStateChanged(enabled: Boolean)
        }

        @JvmField
        @Volatile
        var cameraStateListener: CameraStateListener? = null

        @JvmStatic
        fun getAudioDevices(): List<Map<String, Any?>>? = current?.audioDevices()

        @JvmStatic
        fun selectAudioDevice(id: String): Boolean =
            current?.applyAudioDevice(id) ?: false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var connecting = false
    private var statusText = "Chamada de voz em andamento"
    private var roomId: String? = null
    private var guestId: String? = null
    private var guestName: String? = null
    private var guestAvatarTone = 0
    private var presenceUrl: String? = null
    private var heartbeatJob: Job? = null
    private var liveKitUrl: String? = null
    private var liveKitToken: String? = null
    private var microphoneEnabled = true
    private var cameraEnabled = false
    private var screenShareEnabled = false
    private var screenShareJob: Job? = null
    private var screenShareResultData: Intent? = null
    private var screenShareTrack: LocalVideoTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var cameraPosition = CameraPosition.FRONT
    private var activeCameraId: String? = null
    private var torchEnabled = false
    private var torchPreference = false
    private var torchResetJob: Job? = null
    private var cameraProvider: CameraCapturerUtils.CameraProvider? = null
    private var cameraRestartInProgress = false
    private var cameraCaptureWidth = 1280
    private var cameraCaptureHeight = 720
    private var cameraFrameRate = 30
    private var cameraBitrateKbps = 1500
    private var screenCaptureWidth = 1920
    private var screenCaptureHeight = 1080
    private var screenFrameRate = 30
    private var screenBitrateKbps = 4000
    private val cameraPreferences by lazy {
        getSharedPreferences("onda_camera", MODE_PRIVATE)
    }
    private var cameraPreview: TextureViewRenderer? = null
    private var cameraPreviewInitialized = false
    private var networkCallbackRegisteredAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var roomValidationJob: Job? = null
    private var speakingJob: Job? = null
    private var localSpeaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var preferredAudioRouteId: String? = null
    private var lastAudioRouteId: String? = null
    private var audioRouteSuppressedUntil = 0L
    private var microphoneSuppressedByFocus = false
    private var audioRoutingJob: Job? = null
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null
    private val cameraUnavailableIds = mutableSetOf<String>()
    private var cameraInterrupted = false
    private var cameraRecoveryJob: Job? = null
    private var cameraAvailabilityJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        loadCameraPreferences()
        setupCameraX()
        registerAudioCallbacks()
        registerCameraAvailabilityCallback()
        pendingCameraPreview?.let { attachCameraRenderer(it) }
        acquireWakeLock()
        createNotificationChannel()
    }

    private fun loadCameraPreferences() {
        val prefs = cameraPreferences
        cameraPosition = if (prefs.getString("position", "front") == "back") {
            CameraPosition.BACK
        } else {
            CameraPosition.FRONT
        }
        activeCameraId = prefs.getString("id", null)?.takeIf { it.isNotBlank() }
        torchPreference = prefs.getBoolean("torch", false) && cameraPosition == CameraPosition.BACK
        torchEnabled = false
        Log.i(
            "OndaCall",
            "Camera preference loaded: ${activeCameraId ?: "auto"} (${cameraPosition}), torch=$torchEnabled"
        )
    }

    private fun saveCameraPreferences() {
        cameraPreferences.edit()
            .putString("id", activeCameraId ?: "")
            .putString("position", if (cameraPosition == CameraPosition.BACK) "back" else "front")
            .putBoolean("torch", torchPreference)
            .apply()
    }

    private fun audioManager(): AudioManager? = getSystemService(AudioManager::class.java)

    private fun registerAudioCallbacks() {
        if (audioDeviceCallback != null) {
            return
        }

        val manager = audioManager() ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>?) {
                scheduleAudioRouteRefresh("device added", 450)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>?) {
                scheduleAudioRouteRefresh("device removed", 450)
            }
        }

        try {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
            audioDeviceCallback = callback
            Log.i("OndaCall", "Audio device callbacks registered.")
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not register audio device callbacks.", error)
        }
    }

    private fun unregisterAudioCallbacks() {
        val manager = audioManager()
        val callback = audioDeviceCallback
        if (manager != null && callback != null) {
            try {
                manager.unregisterAudioDeviceCallback(callback)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not unregister audio device callbacks.", error)
            }
        }
        audioDeviceCallback = null
        audioRoutingJob?.cancel()
        audioRoutingJob = null
    }

    private fun requestCallAudioFocus() {
        if (audioFocusListener != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest == null) {
                requestAudioFocusRequest()
            }
            return
        }

        val manager = audioManager() ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            handleAudioFocusChange(change)
        }
        audioFocusListener = listener

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestAudioFocusRequest()
        } else {
            try {
                manager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not request audio focus.", error)
            }
        }
    }

    private fun requestAudioFocusRequest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = audioManager() ?: return
        val listener = audioFocusListener ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(listener)
            .build()

        try {
            audioFocusRequest = request
            manager.requestAudioFocus(request)
            Log.i("OndaCall", "Audio focus requested for the call.")
        } catch (error: Exception) {
            audioFocusRequest = null
            Log.w("OndaCall", "Could not request audio focus.", error)
        }
    }

    private fun abandonCallAudioFocus() {
        val manager = audioManager()
        val request = audioFocusRequest
        val listener = audioFocusListener
        if (manager != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                    manager.abandonAudioFocusRequest(request)
                } else if (listener != null) {
                    manager.abandonAudioFocus(listener)
                }
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not abandon audio focus.", error)
            }
        }
        audioFocusRequest = null
        audioFocusListener = null
        microphoneSuppressedByFocus = false
    }

    private fun handleAudioFocusChange(change: Int) {
        Log.i("OndaCall", "Audio focus changed: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                microphoneSuppressedByFocus = true
                scope.launch {
                    try {
                        room?.localParticipant?.setMicrophoneEnabled(false)
                        postMediaState()
                    } catch (error: Exception) {
                        Log.w("OndaCall", "Could not pause microphone for audio focus loss.", error)
                    }
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (microphoneSuppressedByFocus && microphoneEnabled) {
                    scope.launch {
                        try {
                            room?.localParticipant?.setMicrophoneEnabled(true)
                            postMediaState()
                        } catch (error: Exception) {
                            Log.w("OndaCall", "Could not restore microphone after audio focus.", error)
                        }
                    }
                }
                microphoneSuppressedByFocus = false
                scheduleAudioRouteRefresh("audio focus regained", 350)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // The call remains audible; no route change is necessary.
            }
        }
    }

    private fun scheduleAudioRouteRefresh(reason: String, delayMs: Long = 350) {
        audioRoutingJob?.cancel()
        audioRoutingJob = scope.launch {
            delay(delayMs)
            applyBestAudioRoute(reason)
        }
    }

    private fun applyBestAudioRoute(reason: String) {
        if (SystemClock.elapsedRealtime() < audioRouteSuppressedUntil) {
            return
        }

        val handler = room?.audioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        if (devices.isEmpty()) {
            return
        }

        val available = devices.map { it.javaClass.simpleName }.toSet()
        val current = handler.selectedAudioDevice?.javaClass?.simpleName
        val preferred = preferredAudioRouteId?.takeIf { available.contains(it) }
        lastAudioRouteId = current
        val preferredIsHeadset =
            preferred == "BluetoothHeadset" || preferred == "WiredHeadset"

        val target = when {
            preferredIsHeadset && preferred != current -> preferred
            available.contains("BluetoothHeadset") && current != "BluetoothHeadset" -> "BluetoothHeadset"
            available.contains("WiredHeadset") && current != "WiredHeadset" -> "WiredHeadset"
            preferred != null && preferred != current -> preferred
            current != null && available.contains(current) -> current
            available.contains("Earpiece") -> "Earpiece"
            available.contains("Speakerphone") -> "Speakerphone"
            else -> null
        }

        if (target == null || target == current) {
            return
        }

        val device = devices.firstOrNull { it.javaClass.simpleName == target } ?: return
        try {
            handler.selectDevice(device)
            lastAudioRouteId = target
            Log.i("OndaCall", "Audio route changed after $reason: $target")
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not change audio route to $target.", error)
        }
    }

    private fun registerCameraAvailabilityCallback() {
        if (cameraAvailabilityCallback != null) {
            return
        }

        val manager = cameraManager() ?: return
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                cameraUnavailableIds.add(cameraId)
                if (!cameraEnabled) {
                    return
                }

                val active = activeCameraId
                val matchesActive = (active != null && active == cameraId) ||
                    (active == null && cameraFacing(cameraId) != null)
                if (!matchesActive) {
                    return
                }

                // Opening our own camera also triggers this callback on some
                // devices. Inspect CameraX state before treating it as a
                // takeover by another application.
                cameraAvailabilityJob?.cancel()
                cameraAvailabilityJob = scope.launch {
                    delay(400)
                    if (!cameraEnabled || !cameraUnavailableIds.contains(cameraId)) {
                        return@launch
                    }

                    val state = try {
                        cameraX()?.cameraInfo?.cameraState?.value?.type
                    } catch (error: Exception) {
                        Log.w("OndaCall", "Could not read CameraX state.", error)
                        null
                    }

                    if (state == CameraState.Type.OPEN ||
                        state == CameraState.Type.OPENING ||
                        state == CameraState.Type.PENDING_OPEN) {
                        // This is our own camera; the availability callback
                        // does not represent an external takeover.
                        cameraUnavailableIds.remove(cameraId)
                        return@launch
                    }

                    if (state == CameraState.Type.CLOSED ||
                        state == CameraState.Type.CLOSING) {
                        cameraInterrupted = true
                        Log.w("OndaCall", "Camera $cameraId was taken or closed externally.")
                    }
                }
            }

            override fun onCameraAvailable(cameraId: String) {
                cameraUnavailableIds.remove(cameraId)
                if (!cameraInterrupted || !cameraEnabled) {
                    return
                }

                val active = activeCameraId
                if ((active != null && active == cameraId) || active == null) {
                    cameraInterrupted = false
                    scheduleCameraRecovery("camera released")
                }
            }
        }

        try {
            manager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
            cameraAvailabilityCallback = callback
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not register camera availability callback.", error)
        }
    }

    private fun unregisterCameraAvailabilityCallback() {
        val manager = cameraManager()
        val callback = cameraAvailabilityCallback
        if (manager != null && callback != null) {
            try {
                manager.unregisterAvailabilityCallback(callback)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not unregister camera availability callback.", error)
            }
        }
        cameraAvailabilityCallback = null
        cameraAvailabilityJob?.cancel()
        cameraAvailabilityJob = null
        cameraRecoveryJob?.cancel()
        cameraRecoveryJob = null
    }

    private fun scheduleCameraRecovery(reason: String) {
        cameraRecoveryJob?.cancel()
        cameraRecoveryJob = scope.launch {
            delay(700)
            if (!cameraEnabled || cameraInterrupted || cameraRestartInProgress) {
                return@launch
            }
            Log.i("OndaCall", "Recovering camera after $reason.")
            restartCameraAfterResume()
        }
    }

    private fun setupCameraX() {
        try {
            val provider = CameraXHelper.createCameraProvider(this)
            if (!provider.isSupported(applicationContext)) {
                Log.i("OndaCall", "CameraX is not supported; keeping Camera2.")
                return
            }

            CameraCapturerUtils.registerCameraProvider(provider)
            cameraProvider = provider
            Log.i("OndaCall", "CameraX camera provider registered.")
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not initialize CameraX; keeping Camera2.", error)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "onda:voice_call"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not acquire wake lock.", error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            // The system recreated the service without a call request. Do not
            // leave a foreground notification for a room that no longer exists.
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_TOGGLE_MIC) {
            if (room == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundCompat(buildNotification(notificationText()))
            setMicrophoneEnabled(!microphoneEnabled)
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_DISABLE_CAMERA) {
            if (room == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundCompat(buildNotification(notificationText()))
            setCameraEnabled(false)
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_START_SCREEN_SHARE) {
            if (room == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            val resultCode = intent.getIntExtra(EXTRA_SCREEN_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_SCREEN_RESULT_DATA)
            screenCaptureWidth = intent.getIntExtra(EXTRA_SCREEN_WIDTH, 1920)
            screenCaptureHeight = intent.getIntExtra(EXTRA_SCREEN_HEIGHT, 1080)
            screenFrameRate = intent.getIntExtra(EXTRA_SCREEN_FRAME_RATE, 30)
            screenBitrateKbps = intent.getIntExtra(EXTRA_SCREEN_BITRATE, 4000)
            if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                Log.w("OndaCall", "Screen share permission was not granted.")
                return START_NOT_STICKY
            }

            startScreenShare(resultCode, resultData)
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP_SCREEN_SHARE) {
            if (room == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            stopScreenShare()
            return START_NOT_STICKY
        }

        if (intent.getStringExtra(EXTRA_START_NONCE) != processNonce) {
            // This intent survived a process/package restart; it is not a
            // current call request.
            stopSelf()
            return START_NOT_STICKY
        }

        roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        guestId = intent.getStringExtra(EXTRA_GUEST_ID)
        guestName = intent.getStringExtra(EXTRA_GUEST_NAME)
        guestAvatarTone = intent.getIntExtra(EXTRA_AVATAR_TONE, 0)
        presenceUrl = intent.getStringExtra(EXTRA_PRESENCE_URL)
        cameraCaptureWidth = intent.getIntExtra(EXTRA_CAMERA_WIDTH, 1280)
        cameraCaptureHeight = intent.getIntExtra(EXTRA_CAMERA_HEIGHT, 720)
        cameraFrameRate = intent.getIntExtra(EXTRA_CAMERA_FRAME_RATE, 30)
        cameraBitrateKbps = intent.getIntExtra(EXTRA_CAMERA_BITRATE, 1500)

        val url = intent.getStringExtra(EXTRA_URL)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            // A bare ACTION_START has no room to join; it should not keep an
            // ongoing-call notification alive.
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(notificationText()))
        connectToLiveKit(url, token)
        return START_NOT_STICKY
    }

    private fun updateCameraQuality(width: Int, height: Int, frameRate: Int, bitrateKbps: Int) {
        cameraCaptureWidth = width
        cameraCaptureHeight = height
        cameraFrameRate = frameRate
        cameraBitrateKbps = bitrateKbps
        room?.let { applyCameraQuality(it) }
        if (cameraEnabled) {
            restartCameraAfterResume()
        }
    }

    private fun applyCameraQuality(target: Room) {
        val width = cameraCaptureWidth.coerceIn(160, 1920)
        val height = cameraCaptureHeight.coerceIn(120, 1080)
        val fps = cameraFrameRate.coerceIn(1, 60)
        val bitrate = cameraBitrateKbps.coerceIn(100, 20_000)

        target.videoTrackCaptureDefaults = LocalVideoTrackOptions(
            position = cameraPosition,
            captureParams = VideoCaptureParameter(width, height, fps, false)
        )
        target.videoTrackPublishDefaults = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(bitrate * 1000, fps),
            simulcast = false
        )
        Log.i("OndaCall", "Camera quality: ${width}x${height}@${fps} ${bitrate}kbps")
    }

    private fun applyScreenShareQuality(target: Room) {
        val width = screenCaptureWidth.coerceIn(320, 3840)
        val height = screenCaptureHeight.coerceIn(240, 2160)
        val fps = screenFrameRate.coerceIn(1, 60)
        val bitrate = screenBitrateKbps.coerceIn(300, 20_000)

        target.screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
            captureParams = VideoCaptureParameter(width, height, fps, false)
        )
        target.screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(bitrate * 1000, fps),
            simulcast = false,
            degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        )
        target.localParticipant.screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
            captureParams = VideoCaptureParameter(width, height, fps, false)
        )
        target.localParticipant.screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
            videoEncoding = VideoEncoding(bitrate * 1000, fps),
            simulcast = false,
            degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        )
        Log.i("OndaCall", "Screen quality: ${width}x${height}@${fps} ${bitrate}kbps")
    }

    private fun connectToLiveKit(url: String, token: String) {
        if (connecting || room != null) {
            return
        }

        connecting = true
        liveKitUrl = url
        liveKitToken = token
        statusText = "Conectando a chamada..."
        updateNotification()
        scope.launch {
            try {
                val liveKitRoom = LiveKit.create(applicationContext)
                room = liveKitRoom
                applyCameraQuality(liveKitRoom)
                liveKitRoom.connect(url, token)
                liveKitRoom.localParticipant.setMicrophoneEnabled(
                    microphoneEnabled && !microphoneSuppressedByFocus
                )
                requestCallAudioFocus()
                scheduleAudioRouteRefresh("call connected", 0)
                statusText = "Chamada de voz em andamento"
                updateNotification()
                startPresenceHeartbeat();
                postMediaState()
                startSpeakingMonitor()
                registerNetworkCallback()
                Log.i("OndaCall", "Connected to LiveKit room and microphone enabled.")
            } catch (error: Exception) {
                Log.e("OndaCall", "LiveKit connection failed.", error)
                statusText = "Falha ao conectar a chamada"
                updateNotification()
                room = null
                stopSelf()
            } finally {
                connecting = false
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }

        try {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (SystemClock.elapsedRealtime() - networkCallbackRegisteredAt < 3000L) {
                        return
                    }
                    Log.i("OndaCall", "Network available; scheduling LiveKit reconnect.")
                    scheduleReconnect()
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallbackRegisteredAt = SystemClock.elapsedRealtime()
            networkCallback = callback
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not register network callback.", error)
        }
    }

    private fun scheduleReconnect() {
        if (liveKitUrl.isNullOrBlank() || liveKitToken.isNullOrBlank() || room == null) {
            return;
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1500)
            reconnectLiveKit()
        }
    }

    private suspend fun reconnectLiveKit() {
        if (connecting || room == null) {
            return
        }

        val url = liveKitUrl ?: return
        val token = liveKitToken ?: return
        val oldRoom = room
        connecting = true
        statusText = "Reconectando a chamada..."
        updateNotification()
        try {
            oldRoom?.disconnect()
            val newRoom = LiveKit.create(applicationContext)
            room = newRoom
            applyCameraQuality(newRoom)
            newRoom.connect(url, token)
            if (screenShareEnabled) {
                // A new Room needs a new screencast track. The MediaProjection
                // permission token cannot be safely reused after reconnect, so
                // end the screen session and let the user start it again.
                screenShareEnabled = false
                screenShareResultData = null
                screenShareTrack = null
                screenShareJob?.cancel()
                screenShareJob = null
                Log.i("OndaCall", "Screen share stopped because the call reconnected.")
            }
            requestCallAudioFocus()
            restoreMicrophoneAfterReconnect(newRoom)
            scheduleAudioRouteRefresh("call reconnected", 350)
            cameraPreviewInitialized = false
            if (cameraEnabled) {
                try {
                    newRoom.localParticipant.setCameraEnabled(true)
                    localVideoTrack = newRoom.localParticipant
                        .getTrackPublication(Track.Source.CAMERA)
                        ?.track as? LocalVideoTrack
                    restoreCameraSelection(localVideoTrack)
                    attachCameraRenderer(cameraPreview)
                } catch (cameraError: Exception) {
                    Log.w("OndaCall", "Could not restore camera after reconnect.", cameraError)
                }
            }
            statusText = "Chamada de voz em andamento"
            postMediaState()
            updateNotification()
            Log.i("OndaCall", "LiveKit reconnected after network change.")
        } catch (error: Exception) {
            Log.e("OndaCall", "LiveKit reconnect failed.", error)
            statusText = "Tentando reconectar..."
            updateNotification()
        } finally {
            connecting = false
        }
    }

    private suspend fun restoreMicrophoneAfterReconnect(target: Room) {
        if (!microphoneEnabled) {
            microphoneSuppressedByFocus = false
            target.localParticipant.setMicrophoneEnabled(false)
            return
        }

        // A network change can leave the old audio track published but silent.
        // Recreate it once, which is the same recovery as toggling the mic.
        microphoneSuppressedByFocus = false
        target.localParticipant.setMicrophoneEnabled(false)
        delay(180)
        target.localParticipant.setMicrophoneEnabled(true)
        postMediaState()
        Log.i("OndaCall", "Microphone republished after reconnect.")
    }

    override fun onDestroy() {
        clearTorchMode()
        unregisterAudioCallbacks()
        abandonCallAudioFocus()
        unregisterCameraAvailabilityCallback()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not remove call notification.", error)
        }
        cameraProvider?.let { provider ->
            CameraCapturerUtils.unregisterCameraProvider(provider)
            cameraProvider = null
        }
        if (current === this) {
            current = null
        }
        networkCallback?.let { callback ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not unregister network callback.", error)
            }
        }
        networkCallback = null
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        cameraEnabled = false
        detachCameraTrack()
        cameraPreview = null
        cameraPreviewInitialized = false
        cameraStateListener?.onCameraStateChanged(false)
        runBlocking {
            withContext(Dispatchers.IO) {
                postPresence("leave")
            }
        }
        val currentRoom = room
        room = null
        runBlocking {
            try {
                currentRoom?.disconnect()
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not disconnect LiveKit cleanly.", error)
            }
        }
        scope.cancel()
        wakeLock?.let { lock ->
            try {
                if (lock.isHeld) {
                    lock.release()
                }
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not release wake lock.", error)
            }
        }
        wakeLock = null
        Log.i("OndaCall", "Foreground call service stopped.")
        super.onDestroy()
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
        updateNotification()
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(enabled)
                postMediaState()
                Log.i("OndaCall", "Microphone enabled: $enabled")
            } catch (error: Exception) {
                Log.e("OndaCall", "Could not change microphone state.", error)
                microphoneEnabled = !enabled
                updateNotification()
            }
        }
    }

    private fun notificationText(): String = when {
        !microphoneEnabled -> "Microfone silenciado"
        screenShareEnabled -> "Compartilhando a tela"
        cameraEnabled -> "Chamada de voz e câmera em andamento"
        else -> statusText
    }

    private fun audioDevices(): List<Map<String, Any?>>? {
        val handler = room?.audioSwitchHandler ?: return null
        val selectedType = handler.selectedAudioDevice?.javaClass?.simpleName
        return handler.availableAudioDevices.map { device ->
            val type = device.javaClass.simpleName
            mapOf(
                "id" to type,
                "label" to audioDeviceLabel(type),
                "selected" to (type == selectedType)
            )
        }
    }

    private fun applyAudioDevice(id: String): Boolean {
        val handler = room?.audioSwitchHandler ?: return false
        val device = handler.availableAudioDevices.firstOrNull {
            it.javaClass.simpleName.equals(id, ignoreCase = true)
        } ?: return false
        handler.selectDevice(device)
        preferredAudioRouteId = id
        lastAudioRouteId = id
        // Absorb the device callback caused by this selection instead of
        // immediately choosing another route in a loop.
        audioRouteSuppressedUntil = SystemClock.elapsedRealtime() + 5_000
        Log.i("OndaCall", "Audio route selected: $id")
        return true
    }

    private fun audioDeviceLabel(type: String): String = when (type) {
        "Speakerphone" -> "Alto-falante"
        "Earpiece" -> "Auricular"
        "BluetoothHeadset" -> "Bluetooth"
        "WiredHeadset" -> "Fone com fio"
        else -> type
    }

    private fun restoreCameraSelection(track: LocalVideoTrack?) {
        if (track == null) {
            return
        }

        val desiredFacing = if (cameraPosition == CameraPosition.BACK) "environment" else "user"
        val desiredId = activeCameraId
            ?.takeIf { cameraFacing(it) == desiredFacing }
            ?: findCameraId(cameraPosition)
        activeCameraId = desiredId

        if (desiredId != null) {
            try {
                track.switchCamera(desiredId, cameraPosition)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not restore selected camera.", error)
            }
        }

        saveCameraPreferences()
        val restoreTorch = torchPreference
        if (restoreTorch && cameraPosition == CameraPosition.BACK) {
            torchEnabled = false
            scope.launch {
                for (attempt in 0 until 5) {
                    delay(350)
                    if (!cameraEnabled || cameraPosition != CameraPosition.BACK) {
                        return@launch
                    }
                    if (setTorchEnabled(true)) {
                        Log.i("OndaCall", "Torch preference restored.")
                        return@launch
                    }
                }
            }
        }
    }

    private fun startScreenShare(resultCode: Int, resultData: Intent) {
        if (screenShareEnabled) {
            return
        }

        screenShareResultData = resultData
        screenShareJob?.cancel()
        screenShareJob = scope.launch {
            try {
                if (cameraEnabled) {
                    setCameraEnabled(false)
                    delay(350)
                }

                val participant = room?.localParticipant
                if (participant == null) {
                    screenShareResultData = null
                    return@launch
                }

                room?.let { applyScreenShareQuality(it) }

                val screenNotification = buildScreenShareNotification()
                val params = ScreenCaptureParams(
                    resultData,
                    SCREEN_NOTIFICATION_ID,
                    screenNotification
                ) {
                    scope.launch { handleScreenShareStopped() }
                }

                val started = participant.setScreenShareEnabled(true, params)
                screenShareEnabled = started
                screenShareTrack = if (started) {
                    participant.getTrackPublication(Track.Source.SCREEN_SHARE)
                        ?.track as? LocalVideoTrack
                } else {
                    null
                }
                if (!started) {
                    screenShareResultData = null
                }
                postMediaState()
                updateNotification()
                Log.i("OndaCall", "Screen share enabled: $started (result=$resultCode)")
            } catch (error: Exception) {
                screenShareEnabled = false
                screenShareResultData = null
                screenShareTrack = null
                Log.e("OndaCall", "Could not start screen sharing.", error)
                postMediaState()
                updateNotification()
            }
        }
    }

    private fun updateScreenShareQuality(width: Int, height: Int, frameRate: Int, bitrateKbps: Int) {
        screenCaptureWidth = width
        screenCaptureHeight = height
        screenFrameRate = frameRate
        screenBitrateKbps = bitrateKbps
        room?.let { applyScreenShareQuality(it) }

        if (screenShareEnabled) {
            val track = screenShareTrack ?: room?.localParticipant
                ?.getTrackPublication(Track.Source.SCREEN_SHARE)
                ?.track as? LocalVideoTrack
            screenShareTrack = track
            if (track != null) {
                try {
                    track.capturer.changeCaptureFormat(width, height, frameRate)
                    Log.i("OndaCall", "Screen capture format updated live: ${width}x${height}@$frameRate")
                } catch (error: Exception) {
                    Log.w("OndaCall", "Could not update screen capture format live.", error)
                }
            }
        }
    }

    private fun stopScreenShare() {
        if (!screenShareEnabled) {
            screenShareResultData = null
            return
        }

        screenShareJob?.cancel()
        screenShareJob = scope.launch {
            try {
                room?.localParticipant?.setScreenShareEnabled(false)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not stop screen sharing cleanly.", error)
            } finally {
                handleScreenShareStopped()
            }
        }
    }

    private fun handleScreenShareStopped() {
        if (!screenShareEnabled && screenShareResultData == null) {
            return
        }

        screenShareEnabled = false
        screenShareResultData = null
        screenShareTrack = null
        postMediaState()
        updateNotification()
        Log.i("OndaCall", "Screen share stopped.")
    }

    private fun setCameraEnabled(enabled: Boolean) {
        if (enabled && screenShareEnabled) {
            // Only one presentation source is shown at a time. Starting the
            // camera stops the active screen share.
            stopScreenShare()
        }

        if (enabled == cameraEnabled && (!enabled || localVideoTrack != null)) {
            if (enabled) {
                attachCameraRenderer(cameraPreview)
            }
            return
        }

        cameraEnabled = enabled
        if (enabled) {
            cameraInterrupted = false
        } else {
            cameraInterrupted = false
            cameraUnavailableIds.clear()
            cameraAvailabilityJob?.cancel()
            cameraAvailabilityJob = null
        }
        scope.launch {
            val participant = room?.localParticipant
            if (participant == null) {
                cameraEnabled = false
                cameraStateListener?.onCameraStateChanged(false)
                return@launch
            }

            try {
                participant.setCameraEnabled(enabled)
                if (enabled) {
                    var track: LocalVideoTrack? = null
                    for (attempt in 0 until 20) {
                        val publication = participant.getTrackPublication(Track.Source.CAMERA)
                        track = publication?.track as? LocalVideoTrack
                        if (track != null) {
                            break
                        }
                        delay(100)
                    }
                    localVideoTrack = track
                    restoreCameraSelection(track)
                    Log.i("OndaCall", "Camera track available: ${track != null}")

                    attachCameraRenderer(cameraPreview)
                    postMediaState()
                    startForegroundCompat(buildNotification(notificationText()))
                    Log.i("OndaCall", "Camera enabled.")
                } else {
                    clearTorchMode()
                    detachCameraTrack()
                    postMediaState()
                    startForegroundCompat(buildNotification(notificationText()))
                    Log.i("OndaCall", "Camera disabled.")
                }
            } catch (error: Exception) {
                Log.e("OndaCall", "Could not change camera state.", error)
                clearTorchMode()
                cameraEnabled = false
                detachCameraTrack()
                postMediaState()
                startForegroundCompat(buildNotification(notificationText()))
            }

            cameraStateListener?.onCameraStateChanged(cameraEnabled)
        }
    }

    private fun cameraManager(): CameraManager? = getSystemService(CameraManager::class.java)

    private fun cameraDevices(): List<Map<String, Any?>> {
        val manager = cameraManager() ?: return emptyList()
        val devices = mutableListOf<Map<String, Any?>>()
        val counts = mutableMapOf<Int, Int>()

        try {
            for ((index, id) in manager.cameraIdList.withIndex()) {
                val characteristics = manager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facing = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "environment"
                    CameraCharacteristics.LENS_FACING_FRONT -> "user"
                    else -> ""
                }
                val number = (counts[lensFacing ?: 0] ?: 0) + 1
                counts[lensFacing ?: 0] = number
                val label = when (facing) {
                    "environment" -> "Câmera traseira"
                    "user" -> "Câmera frontal"
                    else -> "Câmera"
                } + if (number > 1) " $number" else ""

                devices.add(
                    mapOf(
                        "deviceId" to id,
                        "label" to label,
                        "facing" to facing
                    )
                )
            }
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not list cameras.", error)
        }

        return devices
    }

    private fun cameraFacing(deviceId: String): String? {
        val manager = cameraManager() ?: return null
        return try {
            when (manager.getCameraCharacteristics(deviceId).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "environment"
                CameraCharacteristics.LENS_FACING_FRONT -> "user"
                else -> null
            }
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not read camera facing.", error)
            null
        }
    }

    private fun findCameraId(position: CameraPosition): String? {
        val manager = cameraManager() ?: return null
        val wanted = if (position == CameraPosition.BACK) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }

        return try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            }
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not find camera for position.", error)
            null
        }
    }

    private fun cameraHasFlash(deviceId: String): Boolean {
        val manager = cameraManager() ?: return false
        return try {
            manager.getCameraCharacteristics(deviceId)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not read camera flash capability.", error)
            false
        }
    }

    private fun cameraX(): Camera? {
        return try {
            localVideoTrack?.capturer?.getCameraX()?.value
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not access CameraX camera.", error)
            null
        }
    }

    private fun torchSupported(): Boolean {
        if (cameraPosition != CameraPosition.BACK) {
            return false
        }

        val id = activeCameraId
        if (id != null && cameraFacing(id) == "environment" && cameraHasFlash(id)) {
            return true
        }

        return cameraX()?.cameraInfo?.hasFlashUnit() == true
    }

    private fun switchCameraTo(deviceId: String?): Boolean {
        val track = localVideoTrack ?: return false
        val manager = cameraManager() ?: return false
        val target = (deviceId ?: activeCameraId)?.takeIf { it.isNotBlank() } ?: return false

        val facing = try {
            when (manager.getCameraCharacteristics(target).get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> CameraPosition.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> CameraPosition.FRONT
                else -> return false
            }
        } catch (error: Exception) {
            Log.w("OndaCall", "Could not switch to camera $target.", error)
            return false
        }

        return try {
            if (torchEnabled) {
                clearTorchMode()
            }

            track.switchCamera(target, facing)
            activeCameraId = target
            cameraPosition = facing
            cameraInterrupted = false
            torchEnabled = false
            torchPreference = false
            room?.let { applyCameraQuality(it) }
            saveCameraPreferences()
            Log.i("OndaCall", "Camera switched to $target ($facing).")
            true
        } catch (error: Exception) {
            Log.e("OndaCall", "Could not switch camera.", error)
            false
        }
    }

    private fun setTorchEnabled(enabled: Boolean): Boolean {
        if (enabled == torchEnabled) {
            return true
        }

        if (cameraPosition != CameraPosition.BACK) {
            return false
        }

        val camera = cameraX()
        if (camera != null) {
            if (!camera.cameraInfo.hasFlashUnit()) {
                return false
            }

            return try {
                camera.cameraControl.enableTorch(enabled)
                torchEnabled = enabled
                torchPreference = enabled
                saveCameraPreferences()
                Log.i("OndaCall", "CameraX torch enabled: $enabled")
                true
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not change CameraX torch mode.", error)
                false
            }
        }

        val id = activeCameraId ?: return false
        if (cameraFacing(id) != "environment" || !cameraHasFlash(id)) {
            return false
        }

        val manager = cameraManager() ?: return false
        return try {
            manager.setTorchMode(id, enabled)
            torchEnabled = enabled
            torchPreference = enabled
            saveCameraPreferences()
            Log.i("OndaCall", "Torch enabled: $enabled")
            true
        } catch (error: Exception) {
            if (!enabled && cameraEnabled) {
                // Some devices refuse to release torch mode while the camera
                // session is open. Briefly stop capture so the camera service
                // can turn the flashlight off.
                Log.w("OndaCall", "Scheduling torch release with camera pause.")
                torchEnabled = false
                restartCaptureForTorchOff(id)
                return true
            }

            Log.w("OndaCall", "Could not change torch mode.", error)
            false
        }
    }

    private fun restartCaptureForTorchOff(cameraId: String) {
        if (torchResetJob != null) {
            return
        }

        torchResetJob = scope.launch {
            val track = localVideoTrack
            try {
                track?.stopCapture()
                delay(350)
                try {
                    cameraManager()?.setTorchMode(cameraId, false)
                } catch (error: Exception) {
                    Log.w("OndaCall", "Could not release torch during camera pause.", error)
                }
                delay(100)
            } finally {
                if (cameraEnabled) {
                    try {
                        track?.startCapture()
                    } catch (error: Exception) {
                        Log.w("OndaCall", "Could not restart camera after torch release.", error)
                    }
                }
                torchResetJob = null
            }
        }
    }

    private fun clearTorchMode() {
        torchResetJob?.cancel()
        torchResetJob = null
        val wasEnabled = torchEnabled
        torchEnabled = false
        if (!wasEnabled) {
            return
        }

        val camera = cameraX()
        if (camera != null) {
            try {
                camera.cameraControl.enableTorch(false)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not disable CameraX torch.", error)
            }
            return
        }

        val id = activeCameraId ?: return
        try {
            cameraManager()?.setTorchMode(id, false)
        } catch (error: Exception) {
            // Closing the camera also releases the torch on most devices.
            Log.w("OndaCall", "Could not release torch mode directly.", error)
        }
    }

    private fun restartCameraAfterResume() {
        if (!cameraEnabled || cameraRestartInProgress) {
            return
        }

        if (cameraInterrupted) {
            Log.i("OndaCall", "Camera recovery postponed; another app still owns the camera.")
            return
        }

        val participant = room?.localParticipant ?: return
        cameraRestartInProgress = true
        scope.launch {
            try {
                Log.i("OndaCall", "Reasserting camera after app resume.")
                clearTorchMode()
                participant.setCameraEnabled(false)
                delay(400)

                if (room?.localParticipant !== participant) {
                    return@launch
                }

                if (!cameraEnabled) {
                    return@launch
                }

                participant.setCameraEnabled(true)

                var track: LocalVideoTrack? = null
                for (attempt in 0 until 20) {
                    val publication = participant.getTrackPublication(Track.Source.CAMERA)
                    track = publication?.track as? LocalVideoTrack
                    if (track != null) {
                        break
                    }
                    delay(100)
                }

                localVideoTrack = track
                restoreCameraSelection(track)

                attachCameraRenderer(cameraPreview)
                postMediaState()
                startForegroundCompat(buildNotification(notificationText()))
                cameraStateListener?.onCameraStateChanged(cameraEnabled)
                Log.i("OndaCall", "Camera reasserted: ${track != null}")
            } catch (error: Exception) {
                cameraEnabled = false
                postMediaState()
                cameraStateListener?.onCameraStateChanged(false)
                Log.e("OndaCall", "Could not reassert camera after resume.", error)
            } finally {
                cameraRestartInProgress = false
            }
        }
    }

    private fun attachCameraRenderer(renderer: TextureViewRenderer?) {
        if (cameraPreview !== renderer) {
            val previous = cameraPreview
            if (previous != null) {
                try {
                    localVideoTrack?.removeRenderer(previous)
                } catch (error: Exception) {
                    Log.w("OndaCall", "Could not detach previous camera preview.", error)
                }
            }
            cameraPreview = renderer
            cameraPreviewInitialized = false
        }

        val currentRenderer = renderer ?: return
        val track = localVideoTrack ?: return
        try {
            Log.i("OndaCall", "Camera preview attach: initialized=$cameraPreviewInitialized")
            if (!cameraPreviewInitialized) {
                room?.initVideoRenderer(currentRenderer)
                cameraPreviewInitialized = true
            }
            track.removeRenderer(currentRenderer)
            track.addRenderer(currentRenderer)
            cameraStateListener?.onCameraStateChanged(cameraEnabled)
        } catch (error: Exception) {
            Log.e("OndaCall", "Could not attach camera preview.", error)
        }
    }

    private fun detachCameraTrack() {
        val renderer = cameraPreview
        val track = localVideoTrack
        if (renderer != null && track != null) {
            try {
                track.removeRenderer(renderer)
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not detach camera preview.", error)
            }
        }
        localVideoTrack = null
    }

    private fun validateRoomAndStopIfMissing() {
        val room = roomId ?: return
        val base = presenceUrl?.trimEnd('/') ?: return
        if (room.isBlank() || base.isBlank() || roomValidationJob?.isActive == true) {
            return
        }

        roomValidationJob = scope.launch {
            delay(250)
            when (queryRoomExists(base, room)) {
                false -> {
                    Log.i("OndaCall", "Room $room no longer exists; ending the call.")
                    stopSelf()
                }
                else -> Unit
            }
        }
    }

    private suspend fun queryRoomExists(base: String, room: String): Boolean? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val encodedRoom = URLEncoder.encode(room, "UTF-8")
                connection = (URL("$base/api/rooms/$encodedRoom/exists").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    return@withContext null
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                    .replace(" ", "")
                    .replace("\n", "")
                    .replace("\r", "")
                when {
                    body.contains("\"exists\":true") -> true
                    body.contains("\"exists\":false") -> false
                    else -> null
                }
            } catch (error: Exception) {
                Log.w("OndaCall", "Could not validate room existence.", error)
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun startPresenceHeartbeat() {
        if (roomId.isNullOrBlank() || guestId.isNullOrBlank() || presenceUrl.isNullOrBlank()) {
            return
        }

        heartbeatJob?.cancel()
        val base = presenceUrl!!.trimEnd('/')
        val room = roomId!!
        heartbeatJob = scope.launch {
            var validationTicks = 0
            while (isActive) {
                postPresence("native")
                postMediaState()
                postSpeakingState(localSpeaking)
                validationTicks += 1
                if (validationTicks >= 3) {
                    validationTicks = 0
                    if (queryRoomExists(base, room) == false) {
                        Log.i("OndaCall", "Room $room no longer exists; ending the call.")
                        stopSelf()
                        return@launch
                    }
                }
                delay(10_000)
            }
        }
    }

    private suspend fun postPresence(action: String) {
        val base = presenceUrl?.trimEnd('/') ?: return
        val room = roomId ?: return
        val guest = guestId ?: return
        if (base.isBlank() || room.isBlank() || guest.isBlank()) {
            return
        }

        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$base/api/presence/$action").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val safeName = (guestName ?: "Convidado").replace("\\", "\\\\").replace("\"", "\\\"")
                val body = """{"roomId":"$room","guestId":"$guest","name":"$safeName","avatarTone":$guestAvatarTone}"""
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w("OndaCall", "Presence $action returned HTTP $code.")
                }
            } catch (error: Exception) {
                Log.w("OndaCall", "Presence $action failed.", error)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun postMediaState() {
        val base = presenceUrl?.trimEnd('/') ?: return
        val room = roomId ?: return
        val guest = guestId ?: return
        if (base.isBlank() || room.isBlank() || guest.isBlank()) {
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL("$base/api/presence/media").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val body =
                        """{"roomId":"$room","guestId":"$guest","micOn":$microphoneEnabled,"cameraOn":$cameraEnabled,"screenOn":$screenShareEnabled,"screenWithAudio":false}"""
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        Log.w("OndaCall", "Media state returned HTTP $code.")
                    }
                } catch (error: Exception) {
                    Log.w("OndaCall", "Media state update failed.", error)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun startSpeakingMonitor() {
        if (speakingJob != null) {
            return
        }

        speakingJob = scope.launch {
            while (isActive) {
                val participant = room?.localParticipant
                val speaking = microphoneEnabled &&
                    participant != null &&
                    (participant.isSpeaking || participant.audioLevel > 0.02f)

                if (speaking != localSpeaking) {
                    localSpeaking = speaking
                    postSpeakingState(speaking)
                }

                delay(200)
            }
        }
    }

    private fun postSpeakingState(speaking: Boolean) {
        val base = presenceUrl?.trimEnd('/') ?: return
        val room = roomId ?: return
        val guest = guestId ?: return
        if (base.isBlank() || room.isBlank() || guest.isBlank()) {
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL("$base/api/presence/speaking").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 4000
                        readTimeout = 4000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val body =
                        """{"roomId":"$room","guestId":"$guest","speaking":$speaking}"""
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } catch (error: Exception) {
                    Log.w("OndaCall", "Speaking state update failed.", error)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cameraEnabled) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i("OndaCall", "Foreground call service started.")
        } catch (error: Exception) {
            Log.e("OndaCall", "Could not start foreground call service.", error)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(notificationText()))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chamada do Onda",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Mantém a chamada de voz ativa em segundo plano."
        channel.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildScreenShareNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            roomId?.takeIf { it.isNotBlank() }?.let { code ->
                data = Uri.parse("onda://sala/${Uri.encode(code)}")
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            5,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Compartilhando a tela")
            .setContentText("Toque para voltar à sala")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            roomId?.takeIf { it.isNotBlank() }?.let { code ->
                data = Uri.parse("onda://sala/${Uri.encode(code)}")
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CallService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val micIntent = Intent(this, CallService::class.java).setAction(ACTION_TOGGLE_MIC)
        val micPending = PendingIntent.getService(
            this,
            2,
            micIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cameraIntent = Intent(this, CallService::class.java).setAction(ACTION_DISABLE_CAMERA)
        val cameraPending = PendingIntent.getService(
            this,
            3,
            cameraIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val screenIntent = Intent(this, CallService::class.java).setAction(ACTION_STOP_SCREEN_SHARE)
        val screenPending = PendingIntent.getService(
            this,
            4,
            screenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Onda")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (microphoneEnabled) "Silenciar" else "Ativar", micPending)

        if (cameraEnabled) {
            builder.addAction(0, "Desligar câmera", cameraPending)
        }

        if (screenShareEnabled) {
            builder.addAction(0, "Parar tela", screenPending)
        }

        return builder
            .addAction(0, "Encerrar", stopPending)
            .build()
    }
}