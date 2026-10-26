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
        every { mockApplication.applicationContext } returns mockApplication

        Injekt.addSingleton<Application>(mockApplication)
        Injekt.addSingleton<Context>(mockApplication)

        // Inject NetworkHelper
        val testClient = client ?: OkHttpClient.Builder().build()
        val mockNetwork = mockk<NetworkHelper>(relaxed = true)
        every { mockNetwork.client } returns testClient
        every { mockNetwork.cloudflareClient } returns testClient
        every { mockNetwork.clientWithTimeOut(any(), any(), any()) } returns testClient
        every { mockNetwork.defaultUserAgentProvider() } returns "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
