package dev.tester

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import dev.tester.engine.ExtensionClassLoader
import dev.tester.engine.TestRunner
import dev.tester.mock.AndroidStubShadow
import dev.tester.mock.TachiyomiMock
import dev.tester.net.TestNetworkHelper
import dev.tester.ui.ConsoleReporter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.PathMatcher
import java.nio.file.Paths
import kotlin.system.exitProcess

class AnimeExtensionTesterCli : CliktCommand(
    name = "anime-extension-tester",
) {
    private val specs by option("-s", "--specs", help = "Path(s) or glob pattern(s) to JSON test suite specifications")
        .multiple(required = true)

    private val proxy by option("-p", "--proxy", help = "HTTP or SOCKS5 proxy URL (e.g. http://127.0.0.1:8080)")

    private val updateSnapshots by option("-u", "--update-snapshots", help = "Update expected snapshots with actual results")
        .flag(default = false)

    private val filter by option("-f", "--filter", help = "Regex filter for suite or test names")

    private val concurrency by option("-c", "--concurrency", help = "Maximum concurrent tests per suite")
        .int().default(4)

    private val rateLimitMs by option("-r", "--rate-limit-ms", help = "Minimum delay in ms between requests per host")
        .long().default(600L)

    private val maxRetries by option("--max-retries", help = "Maximum retries for 429 and server errors")
        .int().default(3)

    private val timeoutSec by option("-t", "--timeout", help = "Network timeout in seconds")
        .long().default(30L)

    private val classpath by option("-cp", "--classpath", help = "Additional classpath directories or JARs")
        .multiple()

    private val extensionDir by option("-e", "--extension-dir", help = "Directory of extension module to auto-resolve classes")

    private val verbose by option("-v", "--verbose", help = "Enable verbose debug logs")
        .flag(default = false)

    override fun run() = runBlocking {
        println("${ConsoleReporter.BOLD}${ConsoleReporter.CYAN}🚀 Anime Extension Tester v0.0.1${ConsoleReporter.RESET}")
        println("  Concurrency: $concurrency | Rate Limit: ${rateLimitMs}ms | Timeout: ${timeoutSec}s | Update Snapshots: $updateSnapshots")
        if (proxy != null) println("  Proxy: $proxy")
        if (extensionDir != null) println("  Extension Dir: $extensionDir")

        // 1. Build OkHttp client
        val client = TestNetworkHelper.buildClient(
            rateLimitMs = rateLimitMs,
            maxRetries = maxRetries,
            proxyUrl = proxy,
            timeoutSec = timeoutSec,
        )

        // 2. Initialize Android & Tachiyomi mocks
        AndroidStubShadow.install(isVerbose = verbose)
        TachiyomiMock.init(client)

        // 3. Initialize ClassLoader for extension classes
        ExtensionClassLoader.init(classpath, extensionDir)

        // 4. Resolve spec files
        val specFiles = resolveSpecFiles(specs)
        if (specFiles.isEmpty()) {
            System.err.println("${ConsoleReporter.RED}Error: No JSON test files found matching: $specs${ConsoleReporter.RESET}")
            exitProcess(1)
        }
        println("  Found ${specFiles.size} spec file(s) to execute.")

        // 5. Run tests
        val runner = TestRunner(
            client = client,
            updateSnapshots = updateSnapshots,
            filterPattern = filter?.let { Regex(it, RegexOption.IGNORE_CASE) },
            concurrency = concurrency,
            verbose = verbose,
        )

        var totalTests = 0
        var totalPassed = 0
        var totalFailed = 0
        var totalUpdated = 0
        val startTime = System.currentTimeMillis()

        for (file in specFiles) {
            try {
                val result = runner.runSuiteFile(file)
                totalTests += result.total
                totalPassed += result.passed
                totalFailed += result.failed
                totalUpdated += result.updated
            } catch (e: Exception) {
                System.err.println("${ConsoleReporter.RED}Error loading suite ${file.name}: ${e.message ?: e.javaClass.name}${ConsoleReporter.RESET}")
                e.printStackTrace()
                totalFailed++
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        ConsoleReporter.printSummary(totalTests, totalPassed, totalFailed, totalUpdated, totalDuration)

        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()

        if (totalFailed > 0) {
            exitProcess(1)
        } else {
            exitProcess(0)
        }
    }

    private fun resolveSpecFiles(patterns: List<String>): List<File> {
        val files = mutableListOf<File>()
        for (pattern in patterns) {
            val directFile = File(pattern)
            if (directFile.isFile) {
                files.add(directFile.canonicalFile)
                continue
            }
            if (directFile.isDirectory) {
                directFile.walkTopDown().filter { it.isFile && it.extension.equals("json", ignoreCase = true) }.forEach {
                    files.add(it.canonicalFile)
                }
                continue
            }

            // Treat as glob
            val separatorIndex = pattern.indexOfAny(charArrayOf('*', '?', '{', '['))
            val baseDir = if (separatorIndex != -1) {
                val sub = pattern.substring(0, separatorIndex)
                val lastSlash = sub.lastIndexOf(File.separatorChar)
                if (lastSlash != -1) File(sub.substring(0, lastSlash)) else File(".")
            } else {
                File(".")
            }

            if (baseDir.exists()) {
                val matcher: PathMatcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                Files.walk(baseDir.toPath()).forEach { path ->
                    if (matcher.matches(path) && Files.isRegularFile(path)) {
                        files.add(path.toFile().canonicalFile)
                    }
                }
            }
        }
        return files.distinctBy { it.absolutePath }
    }
}

fun main(args: Array<String>) = AnimeExtensionTesterCli().main(args)
