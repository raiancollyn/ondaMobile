package net.dpbr.onda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
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
        const val EXTRA_URL = "net.dpbr.onda.extra.LIVEKIT_URL"
        const val EXTRA_TOKEN = "net.dpbr.onda.extra.LIVEKIT_TOKEN"
        const val EXTRA_ROOM_ID = "net.dpbr.onda.extra.ROOM_ID"
        const val EXTRA_GUEST_ID = "net.dpbr.onda.extra.GUEST_ID"
        const val EXTRA_PRESENCE_URL = "net.dpbr.onda.extra.PRESENCE_URL"
        private const val CHANNEL_ID = "onda_call"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        private var current: CallService? = null

        @JvmStatic
        fun setMicrophone(enabled: Boolean) {
            current?.setMicrophoneEnabled(enabled)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var connecting = false
    private var statusText = "Chamada de voz em andamento"
    private var roomId: String? = null
    private var guestId: String? = null
    private var presenceUrl: String? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(statusText))

        roomId = intent?.getStringExtra(EXTRA_ROOM_ID)
        guestId = intent?.getStringExtra(EXTRA_GUEST_ID)
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
        statusText = "Conectando a chamada..."
        updateNotification()
        scope.launch {
            try {
                val liveKitRoom = LiveKit.create(applicationContext)
                room = liveKitRoom
                liveKitRoom.connect(url, token)
                liveKitRoom.localParticipant.setMicrophoneEnabled(true)
                statusText = "Chamada de voz em andamento"
                updateNotification()
                startPresenceHeartbeat()
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

    override fun onDestroy() {
        if (current === this) {
            current = null
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
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
        Log.i("OndaCall", "Foreground call service stopped.")
        super.onDestroy()
    }

    private fun setMicrophoneEnabled(enabled: Boolean) {
        scope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(enabled)
                Log.i("OndaCall", "Microphone enabled: $enabled")
            } catch (error: Exception) {
                Log.e("OndaCall", "Could not change microphone state.", error)
            }
        }
    }

    private fun startPresenceHeartbeat() {
        if (roomId.isNullOrBlank() || guestId.isNullOrBlank() || presenceUrl.isNullOrBlank()) {
            return
        }

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                postPresence("heartbeat")
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
                val body = """{"roomId":"$room","guestId":"$guest"}"""
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
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Onda")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Encerrar", stopPending)
            .build()
    }
}