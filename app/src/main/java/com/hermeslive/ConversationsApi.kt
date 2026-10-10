package com.hermeslive

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Conversation Hermes exposée par GET /v1/conversations (Bearer token). */
data class Conversation(val id: String, val title: String, val lastActiveAt: Long)

class ConversationsApi(private val baseUrl: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Bloquant : appeler hors du thread UI. Retourne null en cas d'erreur. */
    fun list(): List<Conversation>? {
        return try {
            val url = baseUrl.trimEnd('/') + "/v1/conversations"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = JSONObject(response.body?.string() ?: return null)
                val arr = body.optJSONArray("conversations") ?: return emptyList()
                val result = ArrayList<Conversation>(arr.length())
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    result.add(
                        Conversation(
                            id = c.optString("id"),
                            title = c.optString("title").ifEmpty { c.optString("id") },
                            lastActiveAt = c.optLong("lastActiveAt", 0),
                        ),
                    )
                }
                result.sortedByDescending { it.lastActiveAt }
            }
        } catch (_: Exception) {
            null
        }
    }
}
