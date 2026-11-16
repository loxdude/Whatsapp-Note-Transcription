package com.local.voicenotes.inference

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OkHttpAwaitTest {
    private class FakeCall : Call {
        private val request = Request.Builder().url("https://example.invalid/v1/audio").build()
        @Volatile var callback: Callback? = null
        @Volatile var canceled = false
        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { canceled = true }
        override fun isExecuted(): Boolean = callback != null
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = FakeCall()

        fun respond(code: Int, body: ResponseBody) = callback!!.onResponse(
            this,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("status").body(body).build()
        )
    }

    private class TrackingBody(private val content: String) : ResponseBody() {
        @Volatile var closed = false
        private val source = Buffer().writeUtf8(content)
        override fun contentType() = null
        override fun contentLength() = content.length.toLong()
        override fun source(): BufferedSource = source
        override fun close() { closed = true; super.close() }
    }

    @Test fun deliversStatusAndBodyAndClosesTheResponse() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResult() }
        val body = TrackingBody("""{"text":"hello"}""")
        call.respond(200, body)
        assertEquals(HttpResult(200, """{"text":"hello"}"""), result.await())
        assertTrue(body.closed)
        assertFalse(call.canceled)
    }

    @Test fun propagatesNetworkFailures() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitResult() } }
        call.callback!!.onFailure(call, IOException("connection reset"))
        val failure = result.await().exceptionOrNull()
        assertTrue(failure.toString(), failure is IOException)
        assertEquals("connection reset", failure!!.message)
    }

    @Test fun cancellingTheCoroutineCancelsTheCallAndIgnoresALateFailure() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResult() }
        assertTrue(call.isExecuted())
        result.cancelAndJoin()
        assertTrue("HTTP call must be aborted with the coroutine", call.canceled)
        assertTrue(result.isCancelled)
        // OkHttp reports exactly one outcome for the aborted call; it must be harmless.
        call.callback!!.onFailure(call, IOException("Canceled"))
        yield()
    }

    @Test fun lateResponseAfterCancellationIsDiscardedAndClosed() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResult() }
        result.cancelAndJoin()
        assertTrue(call.canceled)
        val late = TrackingBody("late")
        call.respond(200, late)
        yield()
        assertTrue("late response body must not leak", late.closed)
        assertTrue(result.isCancelled)
    }

    @Test fun nonSuccessCodesAreReturnedForMapping() = runBlocking {
        val call = FakeCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResult() }
        call.respond(401, "denied".toResponseBody())
        val response = result.await()
        assertEquals(401, response.code)
        assertFalse(response.isSuccessful)
    }
}
