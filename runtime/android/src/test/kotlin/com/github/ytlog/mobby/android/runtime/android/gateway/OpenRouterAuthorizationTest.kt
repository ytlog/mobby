package com.github.ytlog.mobby.android.runtime.android.gateway

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class OpenRouterAuthorizationTest {
    private open class Http(val status: Int = 200, val body: String = """{"key":"synthetic-authorized-key"}""") : HttpURLConnection(URL("https://openrouter.ai/api/v1/auth/keys")) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    }
    private fun params(uri: URI) = uri.rawQuery.split('&').associate { part ->
        part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
    }
    private fun callback(url: String, query: String? = null): String {
        val auth = params(URI(url))
        val address = URI(auth.getValue("callback_url"))
        return Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(("GET ${address.path}?${query ?: "state=${auth.getValue("state")}&code=synthetic-code"} HTTP/1.1\r\n" +
                "Host: ${address.host}:${address.port}\r\nConnection: close\r\n\r\n").toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `browser grant exchanges a one use code with S256 and returns only the credential`() = runBlocking {
        val http = Http()
        var authorization = ""
        var callbackResponse = ""
        var calls = 0
        val credential = coroutineScope {
            OpenRouterAuthorization(open = { url -> assertEquals("https://openrouter.ai/api/v1/auth/keys", url.toString()); calls++; http }).connect { url ->
                authorization = url
                launch(Dispatchers.IO) { callbackResponse = callback(url) }
            }
        }
        assertEquals("synthetic-authorized-key", credential.concatToString())
        credential.fill('\u0000')
        assertEquals(1, calls)
        assertTrue(callbackResponse.startsWith("HTTP/1.1 200"))
        assertFalse(callbackResponse.contains("synthetic-code"))
        val auth = params(URI(authorization))
        val payload = Json.parseToJsonElement(http.sent.toString()).jsonObject
        val verifier = payload.getValue("code_verifier").jsonPrimitive.content
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        assertEquals(expected, auth["code_challenge"])
        assertEquals("S256", auth["code_challenge_method"])
        assertEquals("S256", payload.getValue("code_challenge_method").jsonPrimitive.content)
        assertEquals("synthetic-code", payload.getValue("code").jsonPrimitive.content)
        assertEquals("POST", http.requestMethod)
        assertFalse(http.instanceFollowRedirects)
        assertTrue(http.closed)
        assertFalse(authorization.contains("synthetic-authorized-key"))
    }
    @Test fun `wrong state duplicate code error and foreign callback paths never exchange credentials`() = runBlocking {
        for (query in listOf("state=wrong&code=synthetic-code", "state=wrong&error=access_denied", "state=wrong&code=a&code=b")) {
            val calls = AtomicInteger()
            try {
                coroutineScope {
                    OpenRouterAuthorization(open = { calls.incrementAndGet(); Http() }).connect { url ->
                        launch(Dispatchers.IO) { assertTrue(callback(url, query).startsWith("HTTP/1.1 400")) }
                    }
                }
                fail("Invalid callback was accepted")
            } catch (e: GatewayAuthorizationException) { assertEquals(GatewayAuthorizationFailure.INVALID_CALLBACK, e.failure) }
            assertEquals(0, calls.get())
        }
        for (target in listOf("https://foreign.invalid/callback?state=s&code=c", "/other?state=s&code=c", "/callback?state=s&code=a&code=b", "/callback?state=s&error=access_denied")) {
            assertThrows(GatewayAuthorizationException::class.java) { OpenRouterAuthorization.callbackCode(target, "s") }
        }
    }
    @Test fun `upstream rejection redirect malformed or oversized responses are not credentials`() = runBlocking {
        for (http in listOf(Http(302, "sensitive redirect"), Http(403, "sensitive upstream error"), Http(body = "<html>error</html>"),
            Http(body = """{"error":"sensitive error","key":"synthetic-key"}"""), Http(body = """{"key":""}"""),
            Http(body = """{"key":123}"""), Http(body = """{"key":"invalid key"}"""), Http(body = "x".repeat(20_000)))) {
            try {
                coroutineScope { OpenRouterAuthorization(open = { http }).connect { url -> launch(Dispatchers.IO) { callback(url) } } }
                fail("Invalid response was accepted")
            } catch (e: GatewayAuthorizationException) {
                assertTrue(e.failure in setOf(GatewayAuthorizationFailure.HTTP_ERROR, GatewayAuthorizationFailure.INVALID_RESPONSE))
                assertFalse(e.toString().contains("sensitive"))
                assertFalse(e.toString().contains("synthetic-key"))
            }
            assertTrue(http.closed)
        }
    }
    @Test fun `cancelled browser wait closes callback port and performs no key exchange`() = runBlocking {
        val opened = CompletableDeferred<String>()
        val calls = AtomicInteger()
        val job = launch {
            OpenRouterAuthorization(open = { calls.incrementAndGet(); Http() }).connect { opened.complete(it) }
            fail("Cancellation returned a credential")
        }
        val auth = params(URI(withTimeout(2_000) { opened.await() }))
        val callback = URI(auth.getValue("callback_url"))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0, calls.get())
        assertThrows(java.io.IOException::class.java) { Socket(callback.host, callback.port).close() }
        Unit
    }
    @Test fun `cancelling an in flight key exchange closes the connection and returns no credential`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val http = object : Http() {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.complete(Unit)
                    released.await()
                    return -1
                }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val job = launch {
            OpenRouterAuthorization(open = { http }).connect { url -> launch(Dispatchers.IO) { callback(url) } }
            fail("Cancellation returned a credential")
        }
        withTimeout(2_000) { reading.await() }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(http.closed)
    }
    @Test fun `authorization timeout is distinct from caller cancellation`() = runBlocking {
        try { OpenRouterAuthorization(timeoutMillis = 150, open = { error("No exchange expected") }).connect {} ; fail("Timeout returned a credential") }
        catch (e: GatewayAuthorizationException) { assertEquals(GatewayAuthorizationFailure.TIMEOUT, e.failure) }
    }
}
