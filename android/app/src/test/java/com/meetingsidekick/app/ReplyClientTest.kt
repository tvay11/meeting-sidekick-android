package com.meetingsidekick.app

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReplyClientTest {
    private val testKey = "test-only-deepseek-key"
    private fun chunk(text: String) = "data: " + JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", text)))).toString() + "\n\n"
    private val done = "data: [DONE]\n\n"

    private fun fixture(block: (MockWebServer, ReplyClient, AtomicReference<Request>, AtomicReference<Call>) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val sent = AtomicReference<Request>()
            val activeCall = AtomicReference<Call>()
            val http = OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { activeCall.set(call) }
                })
                .addInterceptor { chain ->
                    sent.set(chain.request())
                    chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build())
                }.build()
            try { block(server, ReplyClient(http), sent, activeCall) }
            finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
    }

    @Test fun `sends key only in authorization to fixed provider with bounded meeting data`() = fixture { server, client, sent, _ ->
        server.enqueue(MockResponse().setBody(chunk("Hello 👋") + done))
        val answer = StringBuilder()
        runBlocking { client.reply("  $testKey  ", "x".repeat(16020), "q".repeat(2020), "c".repeat(4020)) { answer.append(it) } }
        val request = sent.get()
        assertEquals("https://api.deepseek.com/chat/completions", request.url.toString())
        assertEquals("Bearer $testKey", request.header("Authorization"))
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains(testKey))
        val body = JSONObject(raw)
        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(250, body.getInt("max_tokens"))
        val input = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(16000, input.getString("transcript").length)
        assertEquals(2000, input.getString("question").length)
        assertEquals(4000, input.getString("context").length)
        assertEquals("Hello 👋", answer.toString())
    }

    @Test fun `ignores keepalives reasoning and empty choices while decoding streamed text`() = fixture { server, client, _, _ ->
        val stream = ": keep-alive\n\ndata: {\"choices\":[]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\n\n" + chunk("café") + done
        server.enqueue(MockResponse().setChunkedBody(stream, 1))
        val answer = StringBuilder()
        runBlocking { client.reply(testKey, "", "Hello", "") { answer.append(it) } }
        assertEquals("café", answer.toString())
    }

    @Test fun `rejects missing or unsafe keys and empty input before network`() = fixture { server, client, _, _ ->
        listOf("", " ", "key\nheader", "key with spaces", "kéy").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { client.validate(key) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.reply(testKey, " ", "", "background alone") {} }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `HTTP errors explain key balance or rate problems without reflecting response bodies`() = fixture { server, client, _, _ ->
        mapOf(401 to "key", 402 to "balance", 429 to "wait", 503 to "unavailable").forEach { (code, expected) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("secret-$testKey"))
            val error = assertThrows(Exception::class.java) { runBlocking { client.reply(testKey, "", "Hi", "") {} } }
            assertTrue(error.message.orEmpty().lowercase().contains(expected))
            assertFalse(error.message.orEmpty().contains(testKey))
        }
    }

    @Test fun `never follows redirects carrying the API key`() = fixture { server, client, _, _ ->
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/elsewhere")))
        server.enqueue(MockResponse().setBody(chunk("wrong destination") + done))
        assertThrows(Exception::class.java) { runBlocking { client.reply(testKey, "", "Hi", "") {} } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `rejects truncated empty error and malformed streams with sanitized errors`() = fixture { server, client, _, _ ->
        listOf(chunk("partial"), done, "data: {\"error\":\"$testKey\"}\n\n", "data: $testKey\n\n").forEach { stream ->
            server.enqueue(MockResponse().setBody(stream))
            val error = assertThrows(Exception::class.java) { runBlocking { client.reply(testKey, "", "Hi", "") {} } }
            assertFalse(error.message.orEmpty().contains(testKey))
        }
    }

    @Test fun `bounds received reply text`() = fixture { server, client, _, _ ->
        server.enqueue(MockResponse().setBody(chunk("a".repeat(10001)) + done))
        var count = 0
        assertThrows(Exception::class.java) { runBlocking { client.reply(testKey, "", "Hi", "") { count += it.length } } }
        assertTrue(count <= 10000)
    }

    @Test fun `cancelling a reply cancels its network call`() = fixture { server, client, _, call ->
        server.enqueue(MockResponse().setBody(chunk("late") + done).setBodyDelay(2, TimeUnit.SECONDS))
        runBlocking {
            val task = launch { client.reply(testKey, "", "Hi", "") {} }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            task.cancelAndJoin()
            assertTrue(call.get().isCanceled())
        }
    }
}
