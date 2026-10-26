package dev.tester.model

import kotlinx.serialization.Serializable

@Serializable
data class AnimeSnapshotDto(
    val url: String,
    val title: String,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Int = 0,
    val thumbnailUrl: String? = null,
    // Fields specific to AniZen v16 / v17 (null for v14)
    val backgroundUrl: String? = null,
    val fetchType: String? = null,
    val seasonNumber: Double? = null,
    val initialized: Boolean? = null,
)

@Serializable
data class EpisodeSnapshotDto(
    val url: String,
    val name: String,
    val dateUpload: Long = 0L,
    val episodeNumber: Float = -1f,
    val scanlator: String? = null,
    // Fields specific to AniZen v16 / v17
    val thumbnailUrl: String? = null,
    val summary: String? = null,
    val introStart: Long? = null,
    val introEnd: Long? = null,
)

@Serializable
data class HosterSnapshotDto(
    val hosterName: String,
    val internalData: String? = null,
    val hosterUrl: String? = null,
    val videoList: List<VideoSnapshotDto>? = null,
)

@Serializable
data class VideoSnapshotDto(
    val url: String,
    val quality: String,
    val videoUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitleTracks: List<TrackDto> = emptyList(),
    val audioTracks: List<TrackDto> = emptyList(),
)

@Serializable
data class TrackDto(
    val url: String,
    val lang: String,
)

@Serializable
data class RequestSnapshotDto(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
)
