package dev.tester.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

object TestNetworkHelper {

    fun buildClient(
        rateLimitMs: Long = 600L,
        maxRetries: Int = 3,
        proxyUrl: String? = null,
        timeoutSec: Long = 30L,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .addInterceptor(DomainRateLimitInterceptor(minIntervalMs = rateLimitMs, maxRetries = maxRetries))

        if (!proxyUrl.isNullOrBlank()) {
            val uri = URI.create(proxyUrl)
            val proxyType = when (uri.scheme?.lowercase()) {
                "socks", "socks5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            val port = if (uri.port != -1) uri.port else if (proxyType == Proxy.Type.SOCKS) 1080 else 8080
            builder.proxy(Proxy(proxyType, InetSocketAddress(uri.host, port)))
        }

        return builder.build()
    }

    fun buildMockOkHttpResponse(
        originRequest: Request,
        fixtureFile: File?,
        bodyString: String?,
        statusCode: Int = 200,
        contentType: String = "text/html; charset=utf-8",
    ): Response {
        val rawBytes = when {
            fixtureFile != null && fixtureFile.exists() -> fixtureFile.readBytes()
            bodyString != null -> bodyString.toByteArray(Charsets.UTF_8)
            else -> ByteArray(0)
        }

        return Response.Builder()
            .request(originRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("OK (Mock Fixture)")
            .body(rawBytes.toResponseBody(contentType.toMediaType()))
            .build()
    }
}
