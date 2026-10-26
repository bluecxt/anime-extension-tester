package dev.tester.engine

import dev.tester.mapper.SafeModelMapper
import dev.tester.mock.TachiyomiMock
import dev.tester.model.AnimeSnapshotDto
import dev.tester.model.ContractAssertion
import dev.tester.model.EpisodeSnapshotDto
import dev.tester.model.ExpectedSnapshot
import dev.tester.model.HosterSnapshotDto
import dev.tester.model.TargetMethod
import dev.tester.model.TestCaseSpec
import dev.tester.model.TestSuiteSpec
import dev.tester.model.VideoSnapshotDto
import dev.tester.net.TestNetworkHelper
import dev.tester.ui.ConsoleReporter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TestRunner(
    private val client: OkHttpClient,
    private val updateSnapshots: Boolean = false,
    private val filterPattern: Regex? = null,
    private val concurrency: Int = 4,
    private val verbose: Boolean = false,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun runSuiteFile(file: File): SuiteResult = withContext(Dispatchers.IO) {
        val content = file.readText()
        val suite = json.decodeFromString<TestSuiteSpec>(content)
        val suiteStartTime = System.currentTimeMillis()

        ConsoleReporter.printSuiteHeader(suite.name, suite.extension)

        val filteredTests = suite.tests.filter { test ->
            filterPattern == null || filterPattern.containsMatchIn("${suite.name} ${test.name}")
        }

        if (filteredTests.isEmpty()) {
            println("  (No tests matched filter)")
            return@withContext SuiteResult(0, 0, 0, 0, 0L)
        }

        val semaphore = Semaphore(concurrency)
        var passedCount = 0
        var failedCount = 0
        var updatedCount = 0

        val updatedTests = mutableListOf<TestCaseSpec>()

        coroutineScope {
            val deferreds = filteredTests.map { testCase ->
                async {
                    semaphore.withPermit {
                        runSingleTest(suite, testCase, file.parentFile)
                    }
                }
            }

            val results = deferreds.awaitAll()
            for ((index, result) in results.withIndex()) {
                val originalTest = filteredTests[index]
                if (result.isSuccess) {
                    if (result.wasUpdated) {
                        updatedCount++
                        updatedTests.add(originalTest.copy(expected = result.actualSnapshot))
                    } else {
                        passedCount++
                        updatedTests.add(originalTest)
                    }
                } else {
                    failedCount++
                    updatedTests.add(originalTest)
                }
            }
        }

        // If updateSnapshots is enabled and changes occurred, write back atomically
        if (updateSnapshots && updatedCount > 0) {
            val updatedSuite = suite.copy(tests = updatedTests)
            val updatedJson = json.encodeToString(updatedSuite)
            val tempFile = File.createTempFile("snapshot_", ".json", file.parentFile)
            tempFile.writeText(updatedJson)
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        val duration = System.currentTimeMillis() - suiteStartTime
        SuiteResult(filteredTests.size, passedCount, failedCount, updatedCount, duration)
    }

    private suspend fun runSingleTest(
        suite: TestSuiteSpec,
        testCase: TestCaseSpec,
        suiteDir: File,
    ): SingleTestResult {
        val start = System.currentTimeMillis()
        val source = ExtensionInstantiator.instantiate(suite, client)

        return try {
            val actualSnapshot = executeTarget(source, testCase, suiteDir)
            val duration = System.currentTimeMillis() - start

            // 1. Evaluate Contract Assertions if any
            testCase.contract?.let { contract ->
                validateContract(contract, actualSnapshot)
            }

            // 2. Evaluate Snapshot comparison
            if (updateSnapshots) {
                ConsoleReporter.printTestUpdated(testCase.name, duration)
                SingleTestResult(isSuccess = true, wasUpdated = true, actualSnapshot = actualSnapshot)
            } else if (testCase.expected != null) {
                val expectedNorm = json.encodeToString(testCase.expected)
                val actualNorm = json.encodeToString(actualSnapshot)
                if (expectedNorm != actualNorm) {
                    ConsoleReporter.printTestFailure(
                        testCase.name,
                        duration,
                        "Snapshot mismatch",
                        expectedJson = expectedNorm,
                        actualJson = actualNorm,
                    )
                    SingleTestResult(isSuccess = false, wasUpdated = false, actualSnapshot = actualSnapshot)
                } else {
                    ConsoleReporter.printTestSuccess(testCase.name, duration)
                    SingleTestResult(isSuccess = true, wasUpdated = false, actualSnapshot = actualSnapshot)
                }
            } else {
                // No expected snapshot, pure contract test
                ConsoleReporter.printTestSuccess(testCase.name, duration)
                SingleTestResult(isSuccess = true, wasUpdated = false, actualSnapshot = actualSnapshot)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            val realCause = if (e is java.lang.reflect.InvocationTargetException) e.targetException ?: e else e
            val message = "${realCause.javaClass.simpleName}: ${realCause.message ?: "No message"}"
            ConsoleReporter.printTestFailure(testCase.name, duration, message)
            if (verbose) {
                realCause.printStackTrace()
            }
            SingleTestResult(isSuccess = false, wasUpdated = false, actualSnapshot = null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeTarget(
        source: AnimeHttpSource,
        testCase: TestCaseSpec,
        suiteDir: File,
    ): ExpectedSnapshot = withContext(Dispatchers.IO) {
        val input = testCase.input

        when (testCase.target) {
            TargetMethod.GET_POPULAR -> {
                val page = source.getPopularAnime(input.page)
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.GET_LATEST -> {
                val page = source.getLatestUpdates(input.page)
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.GET_SEARCH -> {
                val page = source.getSearchAnime(input.page, input.query, AnimeFilterList())
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.GET_DETAILS -> {
                val domainAnime = SafeModelMapper.toDomain(input.anime ?: error("Target ${testCase.target} requires input.anime"))
                val details = source.getAnimeDetails(domainAnime)
                ExpectedSnapshot(anime = SafeModelMapper.toDto(details))
            }

            TargetMethod.GET_SEASON_LIST -> {
                val domainAnime = SafeModelMapper.toDomain(input.anime ?: error("Target ${testCase.target} requires input.anime"))
                val seasons = source.getSeasonList(domainAnime)
                ExpectedSnapshot(animes = seasons.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.GET_EPISODE_LIST -> {
                val domainAnime = SafeModelMapper.toDomain(input.anime ?: error("Target ${testCase.target} requires input.anime"))
                val episodes = source.getEpisodeList(domainAnime)
                ExpectedSnapshot(episodes = episodes.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.GET_HOSTER_LIST -> {
                val domainEpisode = SafeModelMapper.toDomain(input.episode ?: error("Target ${testCase.target} requires input.episode"))
                val hosters = source.getHosterList(domainEpisode)
                ExpectedSnapshot(hosters = hosters.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.GET_VIDEO_LIST -> {
                val videos = if (input.hoster != null) {
                    val domainHoster = Hoster(input.hoster.hosterName, input.hoster.internalData.orEmpty())
                    source.getVideoList(domainHoster)
                } else {
                    val domainEpisode = SafeModelMapper.toDomain(input.episode ?: error("Target ${testCase.target} requires input.episode or input.hoster"))
                    source.getVideoList(domainEpisode)
                }
                ExpectedSnapshot(videos = videos.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.POPULAR_REQUEST -> {
                val request = invokeProtected(source, "popularAnimeRequest", input.page) as Request
                ExpectedSnapshot(request = SafeModelMapper.toDto(request))
            }

            TargetMethod.LATEST_REQUEST -> {
                val request = invokeProtected(source, "latestUpdatesRequest", input.page) as Request
                ExpectedSnapshot(request = SafeModelMapper.toDto(request))
            }

            TargetMethod.SEARCH_REQUEST -> {
                val request = invokeProtected(source, "searchAnimeRequest", input.page, input.query, AnimeFilterList()) as Request
                ExpectedSnapshot(request = SafeModelMapper.toDto(request))
            }

            TargetMethod.POPULAR_PARSE -> {
                val originRequest = runCatching { invokeProtected(source, "popularAnimeRequest", input.page) as Request }
                    .getOrElse { Request.Builder().url(source.baseUrl).build() }
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val page = invokeProtected(source, "popularAnimeParse", response) as AnimesPage
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.LATEST_PARSE -> {
                val originRequest = runCatching { invokeProtected(source, "latestUpdatesRequest", input.page) as Request }
                    .getOrElse { Request.Builder().url(source.baseUrl).build() }
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val page = invokeProtected(source, "latestUpdatesParse", response) as AnimesPage
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.SEARCH_PARSE -> {
                val originRequest = runCatching { invokeProtected(source, "searchAnimeRequest", input.page, input.query, AnimeFilterList()) as Request }
                    .getOrElse { Request.Builder().url(source.baseUrl).build() }
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val page = invokeProtected(source, "searchAnimeParse", response) as AnimesPage
                ExpectedSnapshot(
                    animes = page.animes.map { SafeModelMapper.toDto(it) },
                    hasNextPage = page.hasNextPage,
                )
            }

            TargetMethod.DETAILS_PARSE -> {
                val originRequest = Request.Builder().url(input.anime?.url?.takeIf { it.startsWith("http") } ?: source.baseUrl).build()
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val anime = invokeProtected(source, "animeDetailsParse", response) as SAnime
                ExpectedSnapshot(anime = SafeModelMapper.toDto(anime))
            }

            TargetMethod.SEASON_LIST_PARSE -> {
                val originRequest = Request.Builder().url(input.anime?.url?.takeIf { it.startsWith("http") } ?: source.baseUrl).build()
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val seasons = invokeProtected(source, "seasonListParse", response) as List<SAnime>
                ExpectedSnapshot(animes = seasons.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.EPISODE_PARSE -> {
                val originRequest = Request.Builder().url(input.anime?.url?.takeIf { it.startsWith("http") } ?: source.baseUrl).build()
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val episodes = invokeProtected(source, "episodeListParse", response) as List<SEpisode>
                ExpectedSnapshot(episodes = episodes.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.HOSTER_PARSE -> {
                val originRequest = Request.Builder().url(input.episode?.url?.takeIf { it.startsWith("http") } ?: source.baseUrl).build()
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val hosters = invokeProtected(source, "hosterListParse", response) as List<Hoster>
                ExpectedSnapshot(hosters = hosters.map { SafeModelMapper.toDto(it) })
            }

            TargetMethod.VIDEO_PARSE -> {
                val originRequest = Request.Builder().url(input.episode?.url?.takeIf { it.startsWith("http") } ?: source.baseUrl).build()
                val response = buildMockResponse(testCase, originRequest, suiteDir)
                val hoster = input.hoster?.let { Hoster(it.hosterName, it.internalData.orEmpty()) }
                    ?: input.episode?.let { Hoster(it.name, it.url) }
                    ?: Hoster("Default", "")
                val videos = try {
                    invokeProtected(source, "videoListParse", response, hoster) as List<Video>
                } catch (_: Exception) {
                    invokeProtected(source, "videoListParse", response) as List<Video>
                }
                ExpectedSnapshot(videos = videos.map { SafeModelMapper.toDto(it) })
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeProtected(target: Any, name: String, vararg args: Any?): Any? {
        val method = generateSequence(target::class.java) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("Method '$name' with ${args.size} parameter(s) not found on ${target::class.java.name}")
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (ite: java.lang.reflect.InvocationTargetException) {
            throw ite.targetException ?: ite
        }
    }

    private fun buildMockResponse(testCase: TestCaseSpec, originRequest: Request, suiteDir: File): Response {
        val mockSpec = testCase.mockResponse ?: error("Parse target ${testCase.target} requires mockResponse specification")
        val fixtureFile = mockSpec.bodyFile?.let { File(suiteDir, it) }
        return TestNetworkHelper.buildMockOkHttpResponse(
            originRequest = originRequest,
            fixtureFile = fixtureFile,
            bodyString = mockSpec.bodyString,
            statusCode = mockSpec.status,
            contentType = mockSpec.contentType,
        )
    }

    private fun validateContract(contract: ContractAssertion, snapshot: ExpectedSnapshot) {
        val itemsList: List<Any> = snapshot.animes ?: snapshot.episodes ?: snapshot.hosters ?: snapshot.videos ?: emptyList()

        contract.minCount?.let { min ->
            if (itemsList.size < min) {
                error("Contract violation: expected min $min items, got ${itemsList.size}")
            }
        }

        contract.maxCount?.let { max ->
            if (itemsList.size > max) {
                error("Contract violation: expected max $max items, got ${itemsList.size}")
            }
        }

        if (contract.allThumbnailsNonNull == true) {
            val nullThumbnails = snapshot.animes?.filter { it.thumbnailUrl.isNullOrBlank() }
                ?: snapshot.episodes?.filter { it.thumbnailUrl.isNullOrBlank() }
                ?: emptyList()
            if (nullThumbnails.isNotEmpty()) {
                error("Contract violation: found ${nullThumbnails.size} items with null or blank thumbnails")
            }
        }

        if (contract.disallowedThumbnails.isNotEmpty()) {
            val disallowedSet = contract.disallowedThumbnails.toSet()
            val matches = snapshot.animes?.filter { it.thumbnailUrl in disallowedSet }
                ?: snapshot.episodes?.filter { it.thumbnailUrl in disallowedSet }
                ?: emptyList()
            if (matches.isNotEmpty()) {
                error("Contract violation: found items matching disallowed thumbnails: ${contract.disallowedThumbnails}")
            }
        }

        if (contract.containsTitles.isNotEmpty()) {
            val titles = snapshot.animes?.map { it.title } ?: snapshot.episodes?.map { it.name } ?: emptyList()
            for (expectedTitle in contract.containsTitles) {
                if (titles.none { it.contains(expectedTitle, ignoreCase = true) }) {
                    error("Contract violation: expected title '$expectedTitle' not found in $titles")
                }
            }
        }

        contract.urlPattern?.let { pattern ->
            val regex = Regex(pattern)
            val urls = snapshot.animes?.map { it.url }
                ?: snapshot.episodes?.map { it.url }
                ?: snapshot.videos?.map { it.url }
                ?: emptyList()
            for (url in urls) {
                if (!regex.containsMatchIn(url)) {
                    error("Contract violation: url '$url' does not match pattern '$pattern'")
                }
            }
        }
    }
}

data class SuiteResult(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val updated: Int,
    val durationMs: Long,
)

data class SingleTestResult(
    val isSuccess: Boolean,
    val wasUpdated: Boolean,
    val actualSnapshot: ExpectedSnapshot?,
)
