package com.hermeslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service foreground (type microphone) : porte la session live et l'écoute
 * permanente du wake word. L'app n'a pas besoin d'être ouverte — le service
 * démarre au boot si l'écoute est activée, et « Hey Hermes » connecte la session.
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_live"
        const val NOTIFICATION_ID = 1
        const val PREFS = "hermes_live"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }
    }

    interface UiListener {
        fun onUiStateChanged()
    }

    inner class LocalBinder : Binder() {
        val service: WakeWordService get() = this@WakeWordService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<UiListener>()

    // Session live
    private var session: LiveSession? = null
    private var audio: AudioEngine? = null
    private var wake: WakeWordDetector? = null
    private var hangup: HangupDetector? = null
    private var outputSampleRate = AudioEngine.OUTPUT_SAMPLE_RATE
    private var resumeSessionId: String? = null

    // Transcription rendue par l'UI
    val transcriptLines = CopyOnWriteArrayList<String>()
    private var pendingSpeaker: String? = null
    private var pendingText = StringBuilder()

    var statusText: String = ""
        private set
    var sessionActive: Boolean = false
        private set

    /** Niveau RMS courant du micro (diagnostic wake word), mis à jour par le détecteur. */
    @Volatile var lastMicRms: Double = 0.0
    private var lastLevelUiPush = 0L

    private val sessionListener = object : LiveSession.Listener {
        override fun onReady(sessionId: String, model: String, sampleRate: Int, conversationId: String?) {
            mainHandler.post {
                outputSampleRate = sampleRate
                sessionActive = true
                setStatus(getString(R.string.status_ready, model))
                audio?.startMic()
                if (hangupVoiceEnabled()) hangup?.start()
                updateNotification()
            }
        }

        override fun onTranscript(speaker: String, text: String, final: Boolean) {
            mainHandler.post { appendTranscript(speaker, text, final) }
        }

        override fun onAudio(pcm: ByteArray, mimeType: String) {
            if (audio?.playbackActive != true) audio?.startPlayback(outputSampleRate)
            audio?.writePcm(pcm)
        }

        override fun onSpeechStarted() {
            mainHandler.post {
                // Barge-in confirmé par le serveur : on coupe la lecture locale
                // ET on annule la réponse côté serveur (sinon Marie finit de
                // synthétiser dans le vide avant de traiter le nouveau tour).
                session?.sendCancel()
                audio?.stopPlayback()
            }
        }

        override fun onResponseStarted() {
            mainHandler.post {
                setStatus(getString(R.string.status_thinking))
                updateNotification()
            }
        }

        override fun onResponseCompleted() {
            mainHandler.post {
                audio?.awaitDrainThenStop()
                setStatus(getString(R.string.status_listening))
            }
        }

        override fun onSessionError(message: String, recoverable: Boolean) {
            mainHandler.post {
                transcriptLines.add(getString(R.string.error_prefix, message))
                setStatus(getString(R.string.status_error, message))
                notifyUi()
            }
        }

        override fun onClosed(code: Int, reason: String) {
            mainHandler.post {
                transcriptLines.add(getString(R.string.disconnected_log, code, reason))
                onDisconnected(getString(R.string.status_disconnected))
            }
        }

        override fun onConnectionFailed(reason: String) {
            mainHandler.post {
                transcriptLines.add(getString(R.string.error_prefix, reason))
                onDisconnected(getString(R.string.status_error, reason))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audio = AudioEngine { frame ->
            // Half-duplex par défaut (évite l'écho haut-parleur) ; le barge-in
            // (réglage) garde le micro actif pendant la réponse : le serveur
            // détecte la parole, émet input.speech_started et coupe le TTS.
            val muted = audio?.playbackActive == true && !bargeIn()
            if (!muted) session?.sendAudioFrame(frame)
        }
        // Raccroché vocal : les trames brutes de la session alimentent aussi
        // un recognizer Vosk (« raccroche », « au revoir hermes », « merci hermes »).
        hangup = HangupDetector(this) {
            mainHandler.post { disconnect() }
        }
        audio?.rawSink = { chunk, len ->
            // Pas pendant la lecture (écho haut-parleur) : après un barge-in la
            // lecture s'arrête et la phrase de fin est entendue clairement.
            if (audio?.playbackActive != true) hangup?.feed(chunk, len)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ : le service micro ne peut passer foreground que depuis
        // le premier plan avec RECORD_AUDIO accordé (ou une exemption). Sinon
        // startForeground lève SecurityException de façon asynchrone — on
        // dégrade proprement (arrêt + notification) plutôt que de crasher.
        if (!hasMicPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
        } catch (_: Exception) {
            // Démarrage non éligible (boot, restart système en arrière-plan) :
            // on s'arrête, l'app rouverte relancera l'écoute depuis le premier plan.
            stopSelf()
            return START_NOT_STICKY
        }
        applyWakeWordState()
        updateNotification()
        maybeStop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        uiListeners.clear()
        maybeStop()
        return true
    }

    fun addListener(listener: UiListener) {
        uiListeners.add(listener)
    }

    fun removeListener(listener: UiListener) {
        uiListeners.remove(listener)
    }

    private fun notifyUi() {
        uiListeners.forEach { it.onUiStateChanged() }
    }

    // ----- API pour l'UI -----

    fun connect(resumeId: String?) {
        if (session != null) return
        val url = url()
        val token = token()
        if (url.isBlank() || token.isBlank()) {
            setStatus(getString(R.string.error_not_configured))
            notifyUi()
            return
        }
        wake?.stop()
        resumeSessionId = resumeId
        val wsUrl = if (url.contains("?")) url else "$url?token=$token"
        val s = LiveSession(wsUrl, sessionListener)
        session = s
        pendingSpeaker = null
        pendingText = StringBuilder()
        setStatus(getString(R.string.status_connecting))
        updateNotification()
        notifyUi()
        s.connect(resumeId)
    }

    fun disconnect() {
        hangup?.stop()
        session?.close()
        session = null
        sessionActive = false
        audio?.stopEverything()
        setStatus(getString(R.string.status_disconnected))
        updateNotification()
        notifyUi()
        maybeStop()
    }

    fun isWakeEnabled(): Boolean = prefs().getBoolean("wake_enabled", false)

    fun setWakeEnabled(enabled: Boolean) {
        prefs().edit().putBoolean("wake_enabled", enabled).apply()
        applyWakeWordState()
        updateNotification()
        notifyUi()
        // Décocher l'écoute doit TOUT éteindre quand il n'y a pas de session :
        // service foreground arrêté, notification supprimée, micro libéré.
        maybeStop()
    }

    fun isConfigured(): Boolean = url().isNotBlank() && token().isNotBlank()

    fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Nom de l'app actuellement au premier plan (contexte), ou null. */
    fun currentForegroundApp(): String? {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 15_000, now)
            var last: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = event.packageName
                }
            }
            return last
        } catch (_: Exception) {
            return null
        }
    }

    // ----- Wake word -----

    private fun applyWakeWordState() {
        val enabled = isWakeEnabled()
        if (enabled && session == null && hasMicPermission()) {
            if (wake == null) {
                wake = WakeWordDetector(
                    this,
                    onDetected = { mainHandler.post { connect(resumeSessionId) } },
                    onLevel = { rms ->
                        lastMicRms = rms
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLevelUiPush > 400) {
                            lastLevelUiPush = now
                            mainHandler.post { notifyUi() }
                        }
                    },
                )
            }
            wake?.start()
            if (session == null) setStatus(getString(R.string.status_wake))
        } else if (!enabled || session != null) {
            wake?.stop()
            if (session == null && enabled && !hasMicPermission()) {
                setStatus(getString(R.string.error_no_mic))
            }
        }
        notifyUi()
    }

    private fun onDisconnected(status: String) {
        hangup?.stop()
        audio?.stopEverything()
        session = null
        sessionActive = false
        setStatus(status)
        updateNotification()
        applyWakeWordState()
        notifyUi()
        maybeStop()
    }

    private fun maybeStop() {
        if (!isWakeEnabled() && session == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ----- Notification foreground -----

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(getString(R.string.notification_paused))
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val text = when {
            sessionActive -> getString(R.string.notification_session)
            isWakeEnabled() && hasMicPermission() -> getString(R.string.notification_listening)
            else -> getString(R.string.notification_paused)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(text: String) {
        statusText = text
        notifyUi()
    }

    // ----- Transcription -----

    private fun appendTranscript(speaker: String, text: String, final: Boolean) {
        if (speaker != "assistant" && speaker != "user") return
        if (pendingSpeaker != null && pendingSpeaker != speaker) commitPending()
        pendingSpeaker = speaker
        pendingText.append(text)
        if (final) commitPending()
    }

    private fun commitPending() {
        val speaker = pendingSpeaker ?: return
        val text = pendingText.toString().trim()
        pendingSpeaker = null
        pendingText = StringBuilder()
        if (text.isEmpty()) return
        transcriptLines.add(
            if (speaker == "user") {
                getString(R.string.transcript_user, text)
            } else {
                getString(R.string.transcript_assistant, text)
            },
        )
        notifyUi()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun url() = prefs().getString("url", "") ?: ""
    private fun token() = prefs().getString("token", "") ?: ""
    fun bargeIn(): Boolean = prefs().getBoolean("barge_in", true)

    /** Raccroché vocal : désactivé par défaut — la grammaire forcée de Vosk
     * peut mapper de la parole ordinaire sur une phrase de fin. */
    fun hangupVoiceEnabled(): Boolean = prefs().getBoolean("hangup_voice", false)
}
