package dev.tester.mock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

object TachiyomiMock {
    private val threadPrefs = ThreadLocal<InMemorySharedPreferences>()
    private var initialized = false

    @Synchronized
    fun init(client: OkHttpClient? = null) {
        if (initialized) return

        val mockApplication = mockk<Application>(relaxed = true)
        every { mockApplication.getSharedPreferences(any<String>(), any<Int>()) } answers {
            currentPreferences()
        }
        val tempDir = java.nio.file.Files.createTempDirectory("tachiyomi-mock").toFile().apply { deleteOnExit() }
        val cacheDir = java.io.File(tempDir, "cache").apply { mkdirs() }
        val filesDir = java.io.File(tempDir, "files").apply { mkdirs() }
        every { mockApplication.cacheDir } returns cacheDir
        every { mockApplication.filesDir } returns filesDir
        every { mockApplication.applicationContext } returns mockApplication

        Injekt.addSingleton<Application>(mockApplication)
        Injekt.addSingleton<Context>(mockApplication)

        // Inject NetworkHelper
        val testClient = client ?: OkHttpClient.Builder().build()
        val mockNetwork = mockk<NetworkHelper>(relaxed = true)
        every { mockNetwork.client } returns testClient
        every { mockNetwork.cloudflareClient } returns testClient
        every { mockNetwork.clientWithTimeOut(any(), any(), any()) } returns testClient
        every { mockNetwork.defaultUserAgentProvider() } answers {
            currentHeaders()["User-Agent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:156.0) Gecko/20100101 Firefox/156.0"
        }
        Injekt.addSingleton<NetworkHelper>(mockNetwork)

        // Inject Json
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }
        Injekt.addSingleton<Json>(json)

        initialized = true
    }

    @Volatile
    private var suiteHeaders: Map<String, String> = emptyMap()
    @Volatile
    private var suiteCookies: Map<String, String> = emptyMap()

    @PublishedApi
    internal val testHeaders = ThreadLocal<Map<String, String>>()
    @PublishedApi
    internal val testCookies = ThreadLocal<Map<String, String>>()

    fun setSuiteContext(headers: Map<String, String>, cookies: Map<String, String>) {
        suiteHeaders = headers
        suiteCookies = cookies
    }

    fun clearSuiteContext() {
        suiteHeaders = emptyMap()
        suiteCookies = emptyMap()
    }

    suspend fun <T> withTestContext(headers: Map<String, String>, cookies: Map<String, String>, block: suspend () -> T): T {
        testHeaders.set(headers)
        testCookies.set(cookies)
        return try {
            block()
        } finally {
            testHeaders.remove()
            testCookies.remove()
        }
    }

    fun currentHeaders(): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        merged.putAll(suiteHeaders)
        testHeaders.get()?.let { merged.putAll(it) }
        return merged
    }

    fun currentCookies(): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        merged.putAll(suiteCookies)
        testCookies.get()?.let { merged.putAll(it) }
        return merged
    }

    fun <T> withPreferences(prefs: Map<String, JsonElement>, block: () -> T): T {
        val memoryPrefs = InMemorySharedPreferences(prefs)
        threadPrefs.set(memoryPrefs)
        return try {
            block()
        } finally {
            threadPrefs.remove()
        }
    }

    fun currentPreferences(): SharedPreferences {
        return threadPrefs.get() ?: InMemorySharedPreferences(emptyMap())
    }
}
