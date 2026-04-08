package com.burntcones.sonostream

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class StreamerService : Service() {

    private var apiServer: ApiServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSessionCompat? = null

    companion object {
        private const val CHANNEL_ID = "sonostream_channel"
        private const val NOTIFICATION_ID = 1

        // Singleton reference so ApiServer/UI can update playback state
        var instance: StreamerService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Keep CPU awake so HTTP server can serve audio while screen is off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sonostream:server").apply {
            acquire()
        }

        // Keep WiFi radio active at full performance for streaming
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(wifiMode, "sonostream:wifi").apply {
            acquire()
        }

        // Start the API + audio server
        apiServer = ApiServer(applicationContext, MainActivity.SERVER_PORT)
        try {
            apiServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SonoStream").apply {
            // Handle media button events (play/pause from notification, headphones, etc.)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    // Send play command to the API server via HTTP (same as UI does)
                    Thread {
                        try {
                            val url = java.net.URL("http://127.0.0.1:${MainActivity.SERVER_PORT}/api/control")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            // Use first available Sonos speaker
                            val speaker = SonosManager.speakers.keys.firstOrNull() ?: return@Thread
                            conn.outputStream.write("""{"speaker":"$speaker","action":"Play"}""".toByteArray())
                            conn.responseCode // trigger request
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }.start()
                }

                override fun onPause() {
                    Thread {
                        try {
                            val url = java.net.URL("http://127.0.0.1:${MainActivity.SERVER_PORT}/api/control")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            val speaker = SonosManager.speakers.keys.firstOrNull() ?: return@Thread
                            conn.outputStream.write("""{"speaker":"$speaker","action":"Pause"}""".toByteArray())
                            conn.responseCode
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }.start()
                }

                override fun onStop() {
                    Thread {
                        try {
                            val url = java.net.URL("http://127.0.0.1:${MainActivity.SERVER_PORT}/api/control")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            val speaker = SonosManager.speakers.keys.firstOrNull() ?: return@Thread
                            conn.outputStream.write("""{"speaker":"$speaker","action":"Stop"}""".toByteArray())
                            conn.responseCode
                            conn.disconnect()
                        } catch (_: Exception) {}
                    }.start()
                }

                override fun onSkipToNext() {
                    // Trigger next track via WebView JavaScript bridge
                    MainActivity.instance?.runOnUiThread {
                        MainActivity.instance?.evaluateJs("playNext(1)")
                    }
                }

                override fun onSkipToPrevious() {
                    MainActivity.instance?.runOnUiThread {
                        MainActivity.instance?.evaluateJs("playNext(-1)")
                    }
                }
            })

            // Allow transport controls
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

            // Set initial idle state
            setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .build())

            isActive = true
        }
    }

    /**
     * Called from the web UI (via polling or playback events) to update the
     * notification with current track info.
     */
    fun updatePlaybackState(
        trackTitle: String,
        speakerName: String,
        isPlaying: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0
    ) {
        val session = mediaSession ?: return

        // Update metadata (track title, duration)
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, speakerName)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "SonoStream")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build())

        // Update playback state
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, positionMs, if (isPlaying) 1f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .build())

        // Refresh the notification with new info
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(trackTitle, speakerName, isPlaying))
    }

    private fun buildNotification(
        trackTitle: String = "SonoStream",
        speakerName: String = "Ready",
        isPlaying: Boolean = false
    ): Notification {
        val session = mediaSession

        // Pending intent to open the app when notification is tapped
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(trackTitle)
            .setContentText(speakerName)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (session != null) {
            // Add media transport controls
            builder.addAction(NotificationCompat.Action.Builder(
                R.drawable.ic_notification, "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            ).build())

            if (isPlaying) {
                builder.addAction(NotificationCompat.Action.Builder(
                    R.drawable.ic_notification, "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
                ).build())
            } else {
                builder.addAction(NotificationCompat.Action.Builder(
                    R.drawable.ic_notification, "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
                ).build())
            }

            builder.addAction(NotificationCompat.Action.Builder(
                R.drawable.ic_notification, "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            ).build())

            // MediaStyle makes it look like a media player notification
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, next
            )
        }

        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle media button intents
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        apiServer?.stop()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SonoStream Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
