package dev.tester.engine

import dev.tester.mock.TachiyomiMock
import dev.tester.model.TestSuiteSpec
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.OkHttpClient

object ExtensionInstantiator {

    fun instantiate(suite: TestSuiteSpec, customClient: OkHttpClient? = null): AnimeHttpSource {
        return TachiyomiMock.withPreferences(suite.preferences) {
            val targetFqn = suite.extension.ifBlank {
                ExtensionClassLoader.detectedExtensionFqn
                    ?: error("Extension class FQN not specified in test suite nor auto-detected from build.gradle")
            }
            val clazz = ExtensionClassLoader.loadClass(targetFqn)

            val rawInstance = try {
                val constructor = clazz.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance()
            } catch (e: NoSuchMethodException) {
                val langConstructor = clazz.getDeclaredConstructor(String::class.java)
                langConstructor.isAccessible = true
                langConstructor.newInstance(suite.lang ?: "fr")
            }

            val source = when (rawInstance) {
                is AnimeHttpSource -> rawInstance
                is AnimeSourceFactory -> {
                    val sources = rawInstance.createSources().filterIsInstance<AnimeHttpSource>()
                    val selected = sources.firstOrNull { src ->
                        (suite.sourceId != null && src.id == suite.sourceId) ||
                            (suite.lang != null && src.lang.equals(suite.lang, ignoreCase = true))
                    } ?: sources.firstOrNull() ?: error("No matching AnimeHttpSource found in factory ${suite.extension}")
                    selected
                }
                else -> error("Class ${suite.extension} does not implement AnimeHttpSource or AnimeSourceFactory")
            }

            // Attempt to inject client if customClient is provided
            if (customClient != null) {
                runCatching {
                    val clientField = clazz.declaredFields.firstOrNull { it.name == "client" }
                        ?: clazz.superclass?.declaredFields?.firstOrNull { it.name == "client" }
                    clientField?.let {
                        it.isAccessible = true
                        it.set(source, customClient)
                    }
                }
            }

            source
        }
    }
}
