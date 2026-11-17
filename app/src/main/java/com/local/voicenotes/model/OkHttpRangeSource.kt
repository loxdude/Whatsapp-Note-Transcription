package com.local.voicenotes.model

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP range source for [ModelDownload]. [onCall] receives the in-flight call so a coroutine
 * cancellation can abort a read that is blocked on the network.
 */
class OkHttpRangeSource(private val onCall: (Call) -> Unit = {}) : RangeSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    override fun open(url: String, offset: Long): RangeResponse {
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = client.newCall(request)
        onCall(call)
        val response = call.execute()
        val body = response.body
        if (body == null || (response.code != 200 && response.code != 206)) {
            response.close()
            throw IOException("Download failed (HTTP ${response.code}).")
        }
        return if (response.code == 206) {
            // Content-Range: bytes <start>-<end>/<total>
            val range = response.header("Content-Range").orEmpty()
            val match = Regex("bytes (\\d+)-\\d+/(\\d+|\\*)").find(range)
            val start = match?.groupValues?.get(1)?.toLongOrNull()
            if (start == null) {
                response.close()
                throw IOException("Download failed: invalid range response.")
            }
            val total = match.groupValues[2].toLongOrNull() ?: -1L
            RangeResponse(body.byteStream(), start, total)
        } else {
            RangeResponse(body.byteStream(), 0L, body.contentLength())
        }
    }
}
