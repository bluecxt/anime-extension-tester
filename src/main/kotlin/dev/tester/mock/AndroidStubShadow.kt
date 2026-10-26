package dev.tester.mock

import android.net.Uri
import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import java.net.URI
import java.util.Base64 as JavaBase64

object AndroidStubShadow {
    private var installed = false
    var verbose: Boolean = false

    @Synchronized
    fun install(isVerbose: Boolean = false) {
        verbose = isVerbose
        if (installed) return

        try {
            Dispatchers.setMain(Dispatchers.Default)
        } catch (_: Exception) {}

        runCatching {
            mockkStatic(Log::class)
            every { Log.v(any(), any()) } answers {
                if (verbose) println("[\u001B[35mVERBOSE\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()}")
                0
            }
            every { Log.d(any(), any()) } answers {
                if (verbose) println("[\u001B[36mDEBUG\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()}")
                0
            }
            every { Log.i(any(), any()) } answers {
                if (verbose) println("[\u001B[32mINFO\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()}")
                0
            }
            every { Log.w(any(), any<String>()) } answers {
                println("[\u001B[33mWARN\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()}")
                0
            }
            every { Log.e(any(), any<String>()) } answers {
                println("[\u001B[31mERROR\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()}")
                0
            }
            every { Log.e(any(), any<String>(), any()) } answers {
                println("[\u001B[31mERROR\u001B[0m] ${firstArg<String>()}: ${secondArg<String>()} (${thirdArg<Throwable?>()?.message})")
                0
            }
        }

        runCatching {
            mockkStatic(Base64::class)
            every { Base64.decode(any<String>(), any()) } answers {
                val str = firstArg<String>().trim()
                try {
                    JavaBase64.getDecoder().decode(str)
                } catch (_: Exception) {
                    JavaBase64.getUrlDecoder().decode(str)
                }
            }
            every { Base64.decode(any<ByteArray>(), any()) } answers {
                val bytes = firstArg<ByteArray>()
                try {
                    JavaBase64.getDecoder().decode(bytes)
                } catch (_: Exception) {
                    JavaBase64.getUrlDecoder().decode(bytes)
                }
            }
            every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
                JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
            }
        }

        runCatching {
            mockkStatic(Uri::class)
            every { Uri.parse(any()) } answers {
                val raw = firstArg<String>()
                val javaUri = runCatching { URI.create(raw) }.getOrNull()
                val mockUri = mockk<Uri>(relaxed = true)
                every { mockUri.toString() } returns raw
                every { mockUri.scheme } returns javaUri?.scheme
                every { mockUri.host } returns javaUri?.host
                every { mockUri.path } returns javaUri?.path
                every { mockUri.query } returns javaUri?.query
                every { mockUri.getQueryParameter(any()) } answers {
                    val key = firstArg<String>()
                    javaUri?.query?.split("&")
                        ?.map { it.split("=", limit = 2) }
                        ?.firstOrNull { it.getOrNull(0) == key }
                        ?.getOrNull(1)
                }
                mockUri
            }
        }

        installed = true
    }
}
