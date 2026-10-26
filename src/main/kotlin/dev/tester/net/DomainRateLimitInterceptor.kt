package dev.tester.net

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class DomainRateLimitInterceptor(
    private val minIntervalMs: Long = 600L,
    private val maxRetries: Int = 3,
) : Interceptor {
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val lastRequestTimestamp = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val lock = hostLocks.computeIfAbsent(host) { Any() }

        var attempt = 0
        while (true) {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val last = lastRequestTimestamp[host] ?: 0L
                val waitTime = (last + minIntervalMs) - now
                if (waitTime > 0) {
                    Thread.sleep(waitTime)
                }
                lastRequestTimestamp[host] = System.currentTimeMillis()
            }

            val response = chain.proceed(request)
            if (response.code != 429 && response.code !in 502..504) {
                return response
            }
            if (attempt >= maxRetries) {
                return response
            }

            response.close()
            attempt++
            val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
            val backoffMs = if (retryAfterHeader != null) {
                retryAfterHeader * 1000L
            } else {
                (1000L * (1 shl attempt)) + Random.nextLong(0, 500)
            }
            Thread.sleep(backoffMs)
        }
    }
}
