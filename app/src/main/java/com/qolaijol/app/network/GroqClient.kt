// GroqClient - minimal Groq API client (speech-to-text and JSON chat)
//
// Plain HttpURLConnection + org.json, no extra libraries. Models and parameters were verified with
// real requests in Phase 3 (see CLOUD_APIS.md). The API key goes only into the Authorization header
// and is never logged. Every call runs on Dispatchers.IO and fails with a typed GroqException.

package com.qolaijol.app.network

import com.qolaijol.app.config.ApiKeys
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GroqClient(private val apiKey: () -> String? = { ApiKeys.groq }) {

  companion object {
    private const val BASE_URL = "https://api.groq.com/openai/v1/"
    const val STT_MODEL = "whisper-large-v3-turbo"
    const val CHAT_MODEL = "openai/gpt-oss-20b"
    // The prototype's qwen/qwen3.6-27b no longer exists on Groq (HTTP 404, Phase 3 check).
    const val VISION_MODEL = "qwen/qwen3.8-27b"

    /** "33.217s", "4m19.2s" or "7" (Retry-After) -> whole seconds, rounded up. */
    fun parseResetSeconds(value: String?): Int? {
      if (value.isNullOrBlank()) return null
      value.trim().toDoubleOrNull()?.let { return kotlin.math.ceil(it).toInt() }
      val match = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?$").find(value.trim()) ?: return null
      val (h, m, s) = match.destructured
      if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
      val total =
          (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toDoubleOrNull() ?: 0.0)
      return kotlin.math.ceil(total.toDouble()).toInt()
    }
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
  }

  enum class Kind {
    NO_KEY,
    NO_NETWORK,
    /** HTTP 429: the key's per-minute token or request limit is used up. */
    RATE_LIMITED,
    HTTP,
    BAD_RESPONSE,
  }

  class GroqException(
      val kind: Kind,
      message: String,
      cause: Throwable? = null,
      /** For RATE_LIMITED: seconds until the limit resets, if the server said so. */
      val retryAfterSeconds: Int? = null,
  ) : Exception(message, cause)

  /** Transcribes a WAV recording with Whisper. Returns the recognized text (may be empty). */
  suspend fun transcribe(wav: ByteArray, language: String = "en"): String =
      withContext(Dispatchers.IO) {
        val boundary = "QolAIJol-${System.nanoTime()}"
        val connection = open("audio/transcriptions")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val body =
            ByteArrayOutputStream().apply {
              fun field(name: String, value: String) =
                  write(
                      ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                          .toByteArray(),
                  )
              field("model", STT_MODEL)
              field("language", language)
              field("temperature", "0")
              field("response_format", "json")
              write(
                  ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; " +
                          "filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                      .toByteArray(),
              )
              write(wav)
              write("\r\n--$boundary--\r\n".toByteArray())
            }
        val response = send(connection, body.toByteArray())
        try {
          JSONObject(response).optString("text").trim()
        } catch (e: Exception) {
          throw GroqException(Kind.BAD_RESPONSE, "Unexpected transcription response", e)
        }
      }

  /** Sends [prompt] to the chat model and returns its answer parsed as a JSON object. */
  suspend fun chatJson(prompt: String): JSONObject =
      withContext(Dispatchers.IO) {
        val request =
            JSONObject()
                .put("model", CHAT_MODEL)
                .put("temperature", 0)
                // gpt-oss reasons before answering: 100 tokens gave empty answers (Phase 3 check).
                .put("max_tokens", 300)
                .put("reasoning_effort", "low")
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val connection = open("chat/completions")
        connection.setRequestProperty("Content-Type", "application/json")
        val response = send(connection, request.toString().toByteArray())
        try {
          val content =
              JSONObject(response)
                  .getJSONArray("choices")
                  .getJSONObject(0)
                  .getJSONObject("message")
                  .optString("content")
          JSONObject(content)
        } catch (e: Exception) {
          throw GroqException(Kind.BAD_RESPONSE, "Unexpected chat response", e)
        }
      }

  /** Describes a JPEG image following [prompt] with the vision model; returns the raw answer. */
  suspend fun describeImage(jpeg: ByteArray, prompt: String): String =
      withContext(Dispatchers.IO) {
        val dataUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)
        val content =
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUrl)),
                )
        val request =
            JSONObject()
                .put("model", VISION_MODEL)
                .put("temperature", 0)
                .put("max_tokens", 300)
                // Without this the model may put its reasoning into the answer.
                .put("reasoning_effort", "none")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val connection = open("chat/completions")
        connection.setRequestProperty("Content-Type", "application/json")
        val response = send(connection, request.toString().toByteArray())
        try {
          JSONObject(response)
              .getJSONArray("choices")
              .getJSONObject(0)
              .getJSONObject("message")
              .optString("content")
        } catch (e: Exception) {
          throw GroqException(Kind.BAD_RESPONSE, "Unexpected vision response", e)
        }
      }

  private fun open(path: String): HttpURLConnection {
    val key = apiKey() ?: throw GroqException(Kind.NO_KEY, "Groq API key is not configured")
    return (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      doOutput = true
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      setRequestProperty("Authorization", "Bearer $key")
      setRequestProperty("Accept", "application/json")
    }
  }

  private fun send(connection: HttpURLConnection, body: ByteArray): String {
    try {
      connection.setFixedLengthStreamingMode(body.size)
      connection.outputStream.use { it.write(body) }
      val code = connection.responseCode
      if (code == 429) {
        val resetSeconds =
            parseResetSeconds(connection.getHeaderField("retry-after"))
                ?: parseResetSeconds(connection.getHeaderField("x-ratelimit-reset-tokens"))
        throw GroqException(Kind.RATE_LIMITED, "HTTP 429 rate limited", retryAfterSeconds = resetSeconds)
      }
      if (code !in 200..299) {
        val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val message =
            runCatching { JSONObject(error).getJSONObject("error").optString("message") }
                .getOrNull()
                .orEmpty()
        throw GroqException(Kind.HTTP, "HTTP $code ${message.take(200)}")
      }
      return connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: GroqException) {
      throw e
    } catch (e: IOException) {
      throw GroqException(Kind.NO_NETWORK, "Network error: ${e.javaClass.simpleName}", e)
    } finally {
      connection.disconnect()
    }
  }
}
