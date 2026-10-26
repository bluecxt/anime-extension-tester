package dev.tester.engine

import java.io.File
import java.net.URL
import java.net.URLClassLoader

object ExtensionClassLoader {
    private var customClassLoader: ClassLoader? = null
    var detectedExtensionFqn: String? = null
        private set

    fun init(extraPaths: List<String>, extensionDir: String? = null) {
        val urls = mutableListOf<URL>()

        for (path in extraPaths) {
            val file = File(path)
            if (file.exists()) {
                urls.add(file.toURI().toURL())
            }
        }

        if (extensionDir != null) {
            val rawFile = File(extensionDir).canonicalFile
            val extDirFile = if (rawFile.isFile) rawFile.parentFile else rawFile
            detectedExtensionFqn = parseExtClassFromGradle(rawFile) ?: parseExtClassFromGradle(extDirFile)

            if (extDirFile.exists()) {
                val extBuild = File(extDirFile, "build")
                if (extBuild.exists()) {
                    extBuild.walkTopDown().forEach { file ->
                        if (file.isDirectory && file.name == "classes") {
                            urls.add(file.toURI().toURL())
                        } else if (file.isFile && file.extension == "jar") {
                            urls.add(file.toURI().toURL())
                        }
                    }
                }

                // Look for core/build
                var candidateRepo: File? = extDirFile
                for (i in 0..4) {
                    candidateRepo = candidateRepo?.parentFile
                    val coreBuild = File(candidateRepo, "core/build")
                    if (coreBuild.exists()) {
                        coreBuild.walkTopDown().forEach { file ->
                            if (file.isDirectory && (file.name == "classes" || file.name.contains("bundleLibRuntimeToDir"))) {
                                urls.add(file.toURI().toURL())
                            } else if (file.isFile && file.extension == "jar") {
                                urls.add(file.toURI().toURL())
                            }
                        }
                        break
                    }
                }
            }
        }

        if (urls.isNotEmpty()) {
            customClassLoader = URLClassLoader(urls.toTypedArray(), Thread.currentThread().contextClassLoader)
        }
    }

    fun loadClass(fqn: String): Class<*> {
        val loader = customClassLoader ?: Thread.currentThread().contextClassLoader
        return Class.forName(fqn, true, loader)
    }

    private fun parseExtClassFromGradle(target: File): String? {
        val gradleFile = if (target.isFile && (target.name == "build.gradle" || target.name == "build.gradle.kts")) {
            target
        } else {
            val f = File(target, "build.gradle")
            val kts = File(target, "build.gradle.kts")
            when {
                f.exists() -> f
                kts.exists() -> kts
                else -> null
            }
        } ?: return null

        val content = gradleFile.readText()
        val match = Regex("""extClass\s*=\s*['"]([^'"]+)['"]""").find(content) ?: return null
        val extClass = match.groupValues[1]

        val moduleDir = gradleFile.parentFile
        val lang = moduleDir.parentFile?.name ?: "fr"
        val modName = moduleDir.name
        val pkg = "eu.kanade.tachiyomi.animeextension.$lang.$modName"

        return if (extClass.startsWith(".")) {
            "$pkg$extClass"
        } else if (extClass.contains(".")) {
            extClass
        } else {
            "$pkg.$extClass"
        }
    }
}
