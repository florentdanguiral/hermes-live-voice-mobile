package com.hermeslive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Assistant vocal Hermes Live : streame le micro vers la gateway Hermes Live
 * Voice (URL + jeton configurés dans l'app), joue le TTS reçu, gère la liste
 * des conversations et le wake word « hey hermes ».
 *
 * Tour de parole : côté serveur (VAD + gate de complétude). Pendant que la
 * réponse est lue, l'envoi du micro est suspendu (half-duplex) pour éviter
 * que le haut-parleur se réécoute ; à la fin de la lecture le micro repart.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREFS = "hermes_live"
        private const val REQ_AUDIO = 41
    }

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroller: ScrollView
    private lateinit var connectButton: Button
    private lateinit var wakeSwitch: Switch
    private lateinit var spinner: Spinner
    private lateinit var settingsButton: Button

    private var session: LiveSession? = null
    private var audio: AudioEngine? = null
    private var wake: WakeWordDetector? = null
    private var conversations: List<Conversation> = emptyList()
    private var outputSampleRate = AudioEngine.OUTPUT_SAMPLE_RATE

    // Transcription en cours par orateur
    private var pendingSpeaker: String? = null
    private var pendingText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        transcriptView = findViewById(R.id.transcript)
        transcriptScroller = findViewById(R.id.transcript_scroller)
        connectButton = findViewById(R.id.connect_button)
        wakeSwitch = findViewById(R.id.wake_switch)
        spinner = findViewById(R.id.conversations)
        settingsButton = findViewById(R.id.settings_button)

        audio = AudioEngine { frame ->
            // Half-duplex : pas d'envoi pendant la lecture du TTS.
            if (audio?.playbackActive != true) session?.sendAudioFrame(frame)
        }

        connectButton.setOnClickListener { toggleConnection() }
        settingsButton.setOnClickListener { showSettings() }
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean("wake_enabled", checked).apply()
            applyWakeWordState()
        }
        wakeSwitch.isChecked = prefs().getBoolean("wake_enabled", false)

        loadConversations()
        setStatus(getString(R.string.status_disconnected))
        applyWakeWordState()
        if (url().isBlank() || token().isBlank()) showSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        wake?.stop()
    }

    // ----- Connexion -----

    private fun toggleConnection() {
        if (session?.isReady == true || session != null) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun connect() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        val url = url()
        val token = token()
        if (url.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.error_not_configured, Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        val wsUrl = if (url.contains("?")) url else "$url?token=$token"
        val resumeId = selectedConversationId()

        // Le wake word ne doit pas tenir le micro pendant la session.
        wake?.stop()
        audio?.stopPlayback()

        val s = LiveSession(wsUrl, sessionListener)
        session = s
        pendingSpeaker = null
        pendingText = StringBuilder()
        setStatus(getString(R.string.status_connecting))
        connectButton.isEnabled = false
        s.connect(resumeId)
    }

    private val sessionListener = object : LiveSession.Listener {
        override fun onReady(sessionId: String, model: String, sampleRate: Int, conversationId: String?) {
            runOnUiThread {
                outputSampleRate = sampleRate
                setStatus(getString(R.string.status_ready, model))
                connectButton.isEnabled = true
                connectButton.setText(R.string.disconnect)
                audio?.startMic()
            }
        }

        override fun onTranscript(speaker: String, text: String, final: Boolean) {
            runOnUiThread { appendTranscript(speaker, text, final) }
        }

        override fun onAudio(pcm: ByteArray, mimeType: String) {
            // Les callbacks WS arrivent sur le thread OkHttp ; AudioTrack est thread-safe.
            startPlaybackIfNeeded()
            audio?.writePcm(pcm)
        }

        override fun onSpeechStarted() {
            runOnUiThread { audio?.stopPlayback() }
        }

        override fun onResponseStarted() {
            runOnUiThread {
                startPlaybackIfNeeded()
                setStatus(getString(R.string.status_thinking))
            }
        }

        override fun onResponseCompleted() {
            runOnUiThread {
                audio?.awaitDrainThenStop()
                setStatus(getString(R.string.status_listening))
            }
        }

        override fun onSessionError(message: String, recoverable: Boolean) {
            runOnUiThread {
                appendLine(getString(R.string.error_prefix, message))
                setStatus(getString(R.string.status_error, message))
            }
        }

        override fun onClosed() {
            runOnUiThread { onDisconnected(getString(R.string.status_disconnected)) }
        }

        override fun onConnectionFailed(reason: String) {
            runOnUiThread {
                appendLine(getString(R.string.error_prefix, reason))
                onDisconnected(getString(R.string.status_error, reason))
            }
        }
    }

    private fun startPlaybackIfNeeded() {
        if (audio?.playbackActive != true) {
            audio?.startPlayback(outputSampleRate)
        }
    }

    private fun onDisconnected(status: String) {
        audio?.stopEverything()
        session = null
        setStatus(status)
        connectButton.isEnabled = true
        connectButton.setText(R.string.connect)
        applyWakeWordState()
    }

    private fun disconnect() {
        session?.close()
        session = null
        audio?.stopEverything()
        setStatus(getString(R.string.status_disconnected))
        connectButton.isEnabled = true
        connectButton.setText(R.string.connect)
    }

    // ----- Transcription -----

    private fun appendTranscript(speaker: String, text: String, final: Boolean) {
        if (speaker != "assistant" && speaker != "user") return
        if (pendingSpeaker != null && pendingSpeaker != speaker) {
            commitPending()
        }
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
        val line = if (speaker == "user") {
            getString(R.string.transcript_user, text)
        } else {
            getString(R.string.transcript_assistant, text)
        }
        appendLine(line)
    }

    private fun appendLine(line: String) {
        transcriptView.append(line)
        transcriptView.append("\n\n")
        transcriptScroller.post { transcriptScroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ----- Conversations -----

    private fun loadConversations() {
        Thread {
            val api = ConversationsApi(baseHttpUrl(), token())
            val list = api.list() ?: emptyList()
            runOnUiThread {
                conversations = list
                val labels = ArrayList<String>()
                labels.add(getString(R.string.new_conversation))
                labels.addAll(list.map { it.title })
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
            }
        }.start()
    }

    private fun selectedConversationId(): String? {
        val pos = spinner.selectedItemPosition
        return if (pos <= 0) null else conversations.getOrNull(pos - 1)?.id
    }

    // ----- Wake word -----

    private fun applyWakeWordState() {
        val enabled = wakeSwitch.isChecked
        if (enabled && session == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (wake == null) wake = WakeWordDetector(this) {
                runOnUiThread { connect() }
            }
            wake?.start()
            if (session == null) setStatus(getString(R.string.status_wake))
        } else if (!enabled) {
            wake?.stop()
            if (session == null) setStatus(getString(R.string.status_disconnected))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                connect()
            } else {
                Toast.makeText(this, R.string.error_no_mic, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ----- Réglages -----

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.settings_url_hint)
            setText(prefs().getString("url", ""))
        }
        val tokenInput = EditText(this).apply {
            hint = getString(R.string.settings_token_hint)
            setText(prefs().getString("token", ""))
        }
        layout.addView(urlInput)
        layout.addView(tokenInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs().edit()
                    .putString("url", urlInput.text.toString().trim())
                    .putString("token", tokenInput.text.toString().trim())
                    .apply()
                loadConversations()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Utilitaires -----

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun url() = prefs().getString("url", "") ?: ""
    private fun token() = prefs().getString("token", "") ?: ""

    /** wss://hôte/v1/live -> https://hôte */
    private fun baseHttpUrl(): String {
        val u = url()
        return when {
            u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
            u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
            else -> u
        }.substringBefore("/v1/").substringBefore("?").trimEnd('/')
    }

    private fun setStatus(text: String) {
        statusView.text = text
    }
}
