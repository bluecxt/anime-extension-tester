package dev.tester.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TestSuiteSpec(
    val name: String,
    val extension: String = "", // FQN of AnimeHttpSource or AnimeSourceFactory, optional if auto-detected from build.gradle
    val lang: String? = "fr",
    val sourceId: Long? = null,
    val preferences: Map<String, JsonElement> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val tests: List<TestCaseSpec> = emptyList(),
)

@Serializable
data class TestCaseSpec(
    val name: String,
    val target: TargetMethod,
    val input: TestInput = TestInput(),
    val mockResponse: MockResponseSpec? = null,
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val contract: ContractAssertion? = null,
    val expected: ExpectedSnapshot? = null,
)

@Serializable
data class TestInput(
    val page: Int = 1,
    val query: String = "",
    val filters: Map<String, JsonElement> = emptyMap(),
    val anime: AnimeSnapshotDto? = null,
    val episode: EpisodeSnapshotDto? = null,
    val hoster: HosterSnapshotDto? = null,
)

@Serializable
data class MockResponseSpec(
    val status: Int = 200,
    val bodyFile: String? = null,
    val bodyString: String? = null,
    val contentType: String = "text/html; charset=utf-8",
)

@Serializable
data class ContractAssertion(
    val minCount: Int? = null,
    val maxCount: Int? = null,
    val allThumbnailsNonNull: Boolean? = null,
    val disallowedThumbnails: List<String> = emptyList(),
    val requireNonBlankFields: List<String> = emptyList(),
    val containsTitles: List<String> = emptyList(),
    val urlPattern: String? = null,
)

@Serializable
data class ExpectedSnapshot(
    val request: RequestSnapshotDto? = null,
    val animes: List<AnimeSnapshotDto>? = null,
    val hasNextPage: Boolean? = null,
    val anime: AnimeSnapshotDto? = null,
    val episodes: List<EpisodeSnapshotDto>? = null,
    val hosters: List<HosterSnapshotDto>? = null,
    val videos: List<VideoSnapshotDto>? = null,
)
