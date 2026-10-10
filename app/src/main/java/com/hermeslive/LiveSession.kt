package com.hermeslive

import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Client WebSocket pour la gateway Hermes Live Voice (protocole v6).
 *
 * Le serveur gère le tour de parole (VAD + gate de complétude côté serveur) :
 * le client streame le micro en continu et rend les événements reçus.
 */
class LiveSession(
    private val wsUrl: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(sessionId: String, model: String, sampleRate: Int, conversationId: String?)
        fun onTranscript(speaker: String, text: String, final: Boolean)
        fun onAudio(pcm: ByteArray, mimeType: String)
        fun onSpeechStarted()
        fun onResponseStarted()
        fun onResponseCompleted()
        fun onSessionError(message: String, recoverable: Boolean)
        fun onClosed()
        fun onConnectionFailed(reason: String)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val requestIds = AtomicLong(1)
    @Volatile private var userClosed = false
    @Volatile private var ready = false

    val isReady: Boolean get() = ready

    fun connect(conversationSessionId: String?) {
        userClosed = false
        ready = false
        val request = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val conversation = if (conversationSessionId != null) {
                    JSONObject().put("mode", "resume").put("sessionId", conversationSessionId)
                } else {
                    JSONObject().put("mode", "new")
                }
                val start = JSONObject()
                    .put("type", "session.start")
                    .put("id", "req-${requestIds.getAndIncrement()}")
                    .put("protocolVersion", 6)
                    .put("conversation", conversation)
                webSocket.send(start.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                if (userClosed) {
                    listener.onClosed()
                } else {
                    listener.onConnectionFailed(t.message ?: "connection failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                listener.onClosed()
            }
        })
    }

    private fun handleMessage(raw: String) {
        val msg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        when (msg.optString("type")) {
            "session.ready" -> {
                ready = true
                val sampleRate = parseSampleRate(msg.optJSONObject("realtime"))
                listener.onReady(
                    msg.optString("sessionId"),
                    msg.optString("model"),
                    sampleRate,
                    msg.optJSONObject("conversation")?.optString("sessionId")?.takeIf { it.isNotEmpty() },
                )
            }
            "session.error" -> listener.onSessionError(msg.optString("message"), msg.optBoolean("recoverable", false))
            "transcript.delta" -> listener.onTranscript(
                msg.optString("speaker", "user"),
                msg.optString("text"),
                msg.optBoolean("final", false),
            )
            "input.speech_started" -> listener.onSpeechStarted()
            "audio.output" -> {
                val data = msg.optString("data")
                if (data.isNotEmpty()) {
                    val pcm = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
                    listener.onAudio(pcm, msg.optString("mimeType"))
                }
            }
            "response.started" -> listener.onResponseStarted()
            "response.completed" -> listener.onResponseCompleted()
            "response.failed" -> listener.onSessionError(msg.optString("error"), true)
            "task.snapshot", "log" -> Unit
            else -> Unit
        }
    }

    /** mimeType "audio/pcm;rate=24000" -> 24000 ; défaut 24000. */
    private fun parseSampleRate(realtime: JSONObject?): Int {
        val mime = realtime?.optJSONObject("audio")?.optJSONObject("output")?.optString("mimeType") ?: return 24000
        val match = Regex("rate=(\\d+)").find(mime) ?: return 24000
        return match.groupValues[1].toIntOrNull() ?: 24000
    }

    /** pcm : trame PCM16 little-endian à la fréquence d'entrée (24 kHz). */
    fun sendAudioFrame(pcm: ByteArray) {
        if (!ready) return
        val payload = JSONObject()
            .put("type", "audio.input")
            .put("id", "a-${requestIds.getAndIncrement()}")
            .put("data", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=24000")
        socket?.send(payload.toString())
    }

    fun sendText(text: String) {
        if (!ready || text.isBlank()) return
        val payload = JSONObject()
            .put("type", "text.input")
            .put("id", "t-${requestIds.getAndIncrement()}")
            .put("text", text.trim())
        socket?.send(payload.toString())
    }

    /** Barge-in : demande à la gateway d'annuler la réponse en cours (TTS compris). */
    fun sendCancel() {
        if (!ready) return
        val payload = JSONObject()
            .put("type", "response.cancel")
            .put("id", "c-${requestIds.getAndIncrement()}")
        socket?.send(payload.toString())
    }

    fun close() {
        userClosed = true
        ready = false
        socket?.close(1000, "client close")
        socket = null
    }
}
