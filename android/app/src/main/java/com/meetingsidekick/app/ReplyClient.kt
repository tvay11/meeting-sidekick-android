package com.meetingsidekick.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.EOFException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ReplyClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(85, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun validate(url: String, token: String) {
        val parsed = url.trim().toHttpUrlOrNull() ?: error("Enter a valid backend URL.")
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) { "Use a plain backend URL without credentials or query parameters." }
        val debugLocal = BuildConfig.DEBUG && parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(parsed.isHttps || debugLocal) { "Use HTTPS. Debug builds also allow USB-forwarded localhost." }
        require(token.length >= 24 && !token.contains('\n') && !token.contains('\r')) { "Enter the backend access token (at least 24 characters)." }
    }

    suspend fun reply(url: String, token: String, transcript: String, question: String, context: String, delta: (String) -> Unit) {
        validate(url, token)
        val body = JSONObject().put("transcript", transcript.takeLast(16000)).put("question", question.take(2000)).put("context", context.take(4000))
        val request = Request.Builder().url(url.trim().trimEnd('/') + "/v1/replies")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach the backend. Check the URL and connection."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(it.isSuccessful) { when (it.code) {
                                401 -> "Access token rejected. Check Settings."
                                429 -> "Please wait before requesting another reply."
                                else -> "Backend returned HTTP ${it.code}."
                            } }
                            val source = it.body?.source() ?: error("The backend returned no reply.")
                            var done = false
                            val parser = SseParser { name, data ->
                                val json = JSONObject(data)
                                when (name) {
                                    "delta" -> if (continuation.isActive) delta(json.getString("text"))
                                    "done" -> done = true
                                    "error" -> error(json.optString("message", "Reply failed."))
                                }
                            }
                            while (continuation.isActive && !done) {
                                val line = source.readUtf8LineStrict(65536)
                                parser.line(line)
                            }
                            if (!continuation.isActive) throw CancellationException()
                            check(done) { "The reply ended early. Please retry." }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is EOFException) IOException("The reply ended early. Please retry.") else e)
                    }
                }
            })
        }
    }
}
