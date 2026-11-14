package net.dpbr.onda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
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
import java.net.HttpURLConnection
import java.net.URL

class CallService : Service() {
    companion object {
        const val ACTION_START = "net.dpbr.onda.action.START_CALL"
        const val ACTION_STOP = "net.dpbr.onda.action.STOP_CALL"
        const val ACTION_TOGGLE_MIC = "net.dpbr.onda.action.TOGGLE_MIC"
        const val EXTRA_URL = "net.dpbr.onda.extra.LIVEKIT_URL"
        const val EXTRA_TOKEN = "net.dpbr.onda.extra.LIVEKIT_TOKEN"
        const val EXTRA_ROOM_ID = "net.dpbr.onda.extra.ROOM_ID"
        const val EXTRA_GUEST_ID = "net.dpbr.onda.extra.GUEST_ID"
        const val EXTRA_PRESENCE_URL = "net.dpbr.onda.extra.PRESENCE_URL"
        const val EXTRA_GUEST_NAME = "net.dpbr.onda.extra.GUEST_NAME"
        const val EXTRA_AVATAR_TONE = "net.dpbr.onda.extra.AVATAR_TONE"
        private const val CHANNEL_ID = "onda_call"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var current: CallService? = null

        @Volatile
        private var pendingCameraPreview: TextureViewRenderer? = null

        @JvmStatic
        fun setMicrophone(enabled: Boolean) {
            current?.setMicrophoneEnabled(enabled)
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
    private var localVideoTrack: LocalVideoTrack? = null
    private var cameraPreview: TextureViewRenderer? = null
    private var cameraPreviewInitialized = false
    private var networkCallbackRegisteredAt = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        pendingCameraPreview?.let { attachCameraRenderer(it) }
        acquireWakeLock()
        createNotificationChannel()
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_MIC) {
            startForegroundCompat(buildNotification(notificationText()))
            setMicrophoneEnabled(!microphoneEnabled)
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(notificationText()))

        roomId = intent?.getStringExtra(EXTRA_ROOM_ID)
        guestId = intent?.getStringExtra(EXTRA_GUEST_ID)
        guestName = intent?.getStringExtra(EXTRA_GUEST_NAME)
        guestAvatarTone = intent?.getIntExtra(EXTRA_AVATAR_TONE, 0) ?: 0
        presenceUrl = intent?.getStringExtra(EXTRA_PRESENCE_URL)

        val url = intent?.getStringExtra(EXTRA_URL)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            connectToLiveKit(url, token)
        }

        return START_NOT_STICKY
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
                liveKitRoom.connect(url, token)
                liveKitRoom.localParticipant.setMicrophoneEnabled(microphoneEnabled)
                statusText = "Chamada de voz em andamento"
                updateNotification()
                startPresenceHeartbeat();
                postMediaState()
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
            newRoom.connect(url, token)
            newRoom.localParticipant.setMicrophoneEnabled(microphoneEnabled)
            cameraPreviewInitialized = false
            if (cameraEnabled) {
                try {
                    newRoom.localParticipant.setCameraEnabled(true)
                    localVideoTrack = newRoom.localParticipant
                        .getTrackPublication(Track.Source.CAMERA)
                        ?.track as? LocalVideoTrack
                    attachCameraRenderer(cameraPreview)
                } catch (cameraError: Exception) {
                    Log.w("OndaCall", "Could not restore camera after reconnect.", cameraError)
                }
            }
            statusText = "Chamada de voz em andamento"
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

    override fun onDestroy() {
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

    private fun notificationText(): String =
        if (microphoneEnabled) statusText else "Microfone silenciado"

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

    private fun setCameraEnabled(enabled: Boolean) {
        if (enabled == cameraEnabled && (!enabled || localVideoTrack != null)) {
            if (enabled) {
                attachCameraRenderer(cameraPreview)
            }
            return
        }

        cameraEnabled = enabled
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
                    Log.i("OndaCall", "Camera track available: ${track != null}")
                    attachCameraRenderer(cameraPreview)
                    postMediaState()
                    Log.i("OndaCall", "Camera enabled.")
                } else {
                    detachCameraTrack()
                    postMediaState()
                    Log.i("OndaCall", "Camera disabled.")
                }
            } catch (error: Exception) {
                Log.e("OndaCall", "Could not change camera state.", error)
                cameraEnabled = false
                detachCameraTrack()
                postMediaState()
            }

            cameraStateListener?.onCameraStateChanged(cameraEnabled)
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

    private fun startPresenceHeartbeat() {
        if (roomId.isNullOrBlank() || guestId.isNullOrBlank() || presenceUrl.isNullOrBlank()) {
            return
        }

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                postPresence("native")
                delay(30_000)
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
                        """{"roomId":"$room","guestId":"$guest","micOn":$microphoneEnabled,"cameraOn":$cameraEnabled,"screenOn":false,"screenWithAudio":false}"""
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

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Onda")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (microphoneEnabled) "Silenciar" else "Ativar", micPending)
            .addAction(0, "Encerrar", stopPending)
            .build()
    }
}