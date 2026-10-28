package com.hermeslive

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.AdapterView
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
 * UI de l'assistant : binding au WakeWordService (session + micro + wake word
 * vivent dans le service, l'app n'a pas besoin de rester ouverte). Affiche
 * l'état, la transcription, l'app au premier plan (contexte) et les réglages.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_AUDIO = 41
    }

    private lateinit var statusView: TextView
    private lateinit var contextView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var transcriptScroller: ScrollView
    private lateinit var connectButton: Button
    private lateinit var wakeSwitch: Switch
    private lateinit var spinner: Spinner
    private lateinit var settingsButton: Button
    private lateinit var micLevelBar: android.widget.ProgressBar
    private lateinit var liveTranscriptView: TextView

    private var service: WakeWordService? = null
    private var conversations: List<Conversation> = emptyList()
    private var lastRenderedLines = 0
    private var showingHistory = false
    private var assistConnectPending = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as WakeWordService.LocalBinder).service
            service?.addListener(uiListener)
            render()
            loadConversations()
            maybeConnectFromAssistant()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service?.removeListener(uiListener)
            service = null
            render()
        }
    }

    private val uiListener = object : WakeWordService.UiListener {
        override fun onUiStateChanged() {
            runOnUiThread { render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        contextView = findViewById(R.id.context)
        transcriptView = findViewById(R.id.transcript)
        transcriptScroller = findViewById(R.id.transcript_scroller)
        connectButton = findViewById(R.id.connect_button)
        wakeSwitch = findViewById(R.id.wake_switch)
        spinner = findViewById(R.id.conversations)
        settingsButton = findViewById(R.id.settings_button)
        micLevelBar = findViewById(R.id.mic_level)
        liveTranscriptView = findViewById(R.id.live_transcript)

        connectButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.sessionActive) s.disconnect() else {
                if (ensurePermissions()) connectFromUi()
            }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                onConversationSelected(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        settingsButton.setOnClickListener { showSettings() }
        wakeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (ensurePermissions()) {
                    startService()
                    service?.setWakeEnabled(true)
                } else {
                    wakeSwitch.isChecked = false
                }
            } else {
                service?.setWakeEnabled(false)
            }
            render()
        }

        if (prefs().getBoolean("wake_enabled", false) && hasMic()) {
            startService()
        }
        assistConnectPending = intent?.action == Intent.ACTION_ASSIST
        if (!isConfigured()) showSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            assistConnectPending = true
            maybeConnectFromAssistant()
        }
    }

    /** Ouverture via le geste assistant (appui long power) : connecte direct. */
    private fun maybeConnectFromAssistant() {
        if (!assistConnectPending) return
        val s = service ?: return
        if (s.sessionActive) {
            assistConnectPending = false
            return
        }
        if (hasMic()) {
            assistConnectPending = false
            connectFromUi()
        } else {
            ensurePermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, WakeWordService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.removeListener(uiListener)
        unbindService(connection)
    }

    private fun startService() {
        try {
            WakeWordService.start(this)
        } catch (_: Exception) {
            // Certaines restrictions (démarrage background type micro) : l'app ouverte suffit.
        }
    }

    private fun connectFromUi() {
        val pos = spinner.selectedItemPosition
        val resumeId = if (pos <= 0) null else conversations.getOrNull(pos - 1)?.id
        // Passe le service en FGS (éligible depuis le premier plan) pour que la
        // session survive à la fermeture de l'app.
        startService()
        service?.connect(resumeId)
    }

    // ----- Permissions -----

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), REQ_AUDIO)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        // Le micro est la première permission demandée ; les notifications sont optionnelles.
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startService()
            service?.setWakeEnabled(wakeSwitch.isChecked)
            maybeConnectFromAssistant()
            render()
        } else {
            Toast.makeText(this, R.string.error_no_mic, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ----- Rendu -----

    private fun render() {
        val s = service
        if (s == null) {
            statusView.setText(R.string.status_service_starting)
            return
        }
        statusView.text = s.statusText.ifEmpty { getString(R.string.status_disconnected) }

        // Couleur du statut selon l'état
        val statusColor = when (s.currentState) {
            WakeWordService.UiState.LISTENING -> 0xFF2E7D32.toInt()    // vert
            WakeWordService.UiState.TRANSCRIBING -> 0xFF1565C0.toInt()  // bleu
            WakeWordService.UiState.THINKING -> 0xFF6A1B9A.toInt()      // violet
            WakeWordService.UiState.SPEAKING -> 0xFFE65100.toInt()      // orange
            WakeWordService.UiState.DISCONNECTED -> 0xFF757575.toInt()  // gris
        }
        statusView.setTextColor(statusColor)

        // Barre de niveau micro (visible quand wake word actif ou session en écoute)
        val showMicBar = (s.isWakeEnabled() && !s.sessionActive) ||
            (s.sessionActive && s.currentState == WakeWordService.UiState.LISTENING)
        if (showMicBar && s.lastMicRms > 0.0) {
            micLevelBar.visibility = android.view.View.VISIBLE
            // RMS 0-5000 → 0-100 %
            val pct = (s.lastMicRms / 5000.0 * 100).toInt().coerceIn(0, 100)
            micLevelBar.progress = pct
        } else {
            micLevelBar.visibility = android.view.View.GONE
        }

        // Transcription en cours (affichage live)
        if (s.liveTranscript.isNotEmpty()) {
            liveTranscriptView.visibility = android.view.View.VISIBLE
            liveTranscriptView.text = getString(R.string.live_transcript_user, s.liveTranscript)
        } else {
            liveTranscriptView.visibility = android.view.View.GONE
        }

        wakeSwitch.isChecked = s.isWakeEnabled()
        connectButton.isEnabled = true
        connectButton.setText(if (s.sessionActive) R.string.disconnect else R.string.connect)

        val foreground = s.currentForegroundApp()
        contextView.text = if (hasUsageAccess()) {
            foreground?.let { getString(R.string.context_app, appName(it)) } ?: getString(R.string.context_none)
        } else {
            getString(R.string.context_no_access)
        }

        // Transcript incrémental. Tant qu'un historique de conversation est affiché
        // et que la session live ne produit rien de nouveau, on ne touche à rien.
        val lines = s.transcriptLines
        if (showingHistory && lines.size <= lastRenderedLines) {
            // Historique affiché, pas de nouvelle ligne live.
        } else {
            showingHistory = false
            while (lastRenderedLines < lines.size) {
                transcriptView.append(lines[lastRenderedLines])
                transcriptView.append("\n\n")
                lastRenderedLines++
            }
            if (lines.isEmpty()) {
                transcriptView.setText(R.string.transcript_empty)
                lastRenderedLines = 0
            }
        }
        transcriptScroller.post { transcriptScroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ----- Conversations -----

    private fun loadConversations() {
        Thread {
            val url = prefs().getString("url", "") ?: ""
            val token = prefs().getString("token", "") ?: ""
            if (url.isBlank() || token.isBlank()) return@Thread
            val api = ConversationsApi(baseHttpUrl(url), token)
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

    /** Sélection dans le spinner : affiche l'historique sans connecter. */
    private fun onConversationSelected(position: Int) {
        if (position <= 0) {
            // « Nouvelle conversation » : on abandonne l'historique affiché, sauf si
            // une session live est en cours (le transcript appartient à la session).
            showingHistory = false
            val s = service
            if (s == null || !s.sessionActive) {
                transcriptView.setText(R.string.transcript_empty)
                lastRenderedLines = s?.transcriptLines?.size ?: 0
            }
            return
        }
        val conv = conversations.getOrNull(position - 1) ?: return
        loadHistory(conv)
    }

    private fun loadHistory(conv: Conversation) {
        showingHistory = true
        Thread {
            val url = prefs().getString("url", "") ?: ""
            val token = prefs().getString("token", "") ?: ""
            val messages = if (url.isBlank() || token.isBlank()) null
            else ConversationsApi(baseHttpUrl(url), token).getMessages(conv.id)
            runOnUiThread {
                if (messages == null) {
                    showingHistory = false
                    Toast.makeText(this, R.string.history_error, Toast.LENGTH_SHORT).show()
                    render()
                    return@runOnUiThread
                }
                val s = service
                // Les lignes live déjà rendues ne doivent pas réapparaître sous l'historique.
                lastRenderedLines = s?.transcriptLines?.size ?: 0
                transcriptView.text = messages.joinToString("\n\n") { m ->
                    if (m.role == "user") getString(R.string.transcript_user, m.content)
                    else getString(R.string.transcript_assistant, m.content)
                }.ifEmpty { getString(R.string.transcript_empty) }
                transcriptScroller.post { transcriptScroller.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }

    // ----- Contexte (usage stats) -----

    private fun hasUsageAccess(): Boolean {
        try {
            val manager = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = manager.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName,
            )
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            return false
        }
    }

    private fun appName(packageName: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
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
        val bargeSwitch = Switch(this).apply {
            text = getString(R.string.settings_barge_in)
            isChecked = prefs().getBoolean("barge_in", false)
        }
        val hangupSwitch = Switch(this).apply {
            text = getString(R.string.settings_hangup_voice)
            isChecked = prefs().getBoolean("hangup_voice", false)
        }
        val noisySwitch = Switch(this).apply {
            text = getString(R.string.settings_noisy_mode)
            isChecked = prefs().getBoolean("noisy_mode", false)
        }
        layout.addView(urlInput)
        layout.addView(tokenInput)
        layout.addView(bargeSwitch)
        layout.addView(hangupSwitch)
        layout.addView(noisySwitch)
        val usageButton = Button(this).apply {
            setText(R.string.settings_usage_access)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        layout.addView(usageButton)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val oldNoisy = prefs().getBoolean("noisy_mode", false)
                val newNoisy = noisySwitch.isChecked
                prefs().edit()
                    .putString("url", urlInput.text.toString().trim())
                    .putString("token", tokenInput.text.toString().trim())
                    .putBoolean("barge_in", bargeSwitch.isChecked)
                    .putBoolean("hangup_voice", hangupSwitch.isChecked)
                    .putBoolean("noisy_mode", newNoisy)
                    .apply()
                // Si le mode bruyant a changé et que le wake word est actif,
                // on le redémarre pour appliquer la nouvelle sensibilité.
                if (oldNoisy != newNoisy && service?.isWakeEnabled() == true) {
                    service?.setWakeEnabled(false)
                    service?.setWakeEnabled(true)
                }
                loadConversations()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ----- Utilitaires -----

    private fun prefs() = getSharedPreferences(WakeWordService.PREFS, Context.MODE_PRIVATE)
    private fun isConfigured(): Boolean =
        !(prefs().getString("url", "") ?: "").isBlank() && !(prefs().getString("token", "") ?: "").isBlank()

    /** wss://hôte/v1/live -> https://hôte */
    private fun baseHttpUrl(url: String): String {
        return when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
            else -> url
        }.substringBefore("/v1/").substringBefore("?").trimEnd('/')
    }
}
