package dev.tester.mapper

import dev.tester.model.AnimeSnapshotDto
import dev.tester.model.EpisodeSnapshotDto
import dev.tester.model.HosterSnapshotDto
import dev.tester.model.RequestSnapshotDto
import dev.tester.model.TrackDto
import dev.tester.model.VideoSnapshotDto
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Request

object SafeModelMapper {

    private inline fun <T> safeGet(block: () -> T): T? = try {
        block()
    } catch (_: LinkageError) {
        null
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun Any.getReflectiveProp(name: String): Any? = safeGet {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        this::class.java.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }?.invoke(this)
    }

    private fun Any.setReflectiveProp(name: String, value: Any?) {
        safeGet {
            val setterName = "set" + name.replaceFirstChar { it.uppercase() }
            val method = this::class.java.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
            method?.invoke(this, value)
        }
    }

    fun toDto(anime: SAnime): AnimeSnapshotDto = AnimeSnapshotDto(
        url = anime.url,
        title = anime.title,
        artist = anime.artist,
        author = anime.author,
        description = anime.description,
        genre = anime.genre,
        status = anime.status,
        thumbnailUrl = anime.thumbnail_url,
        backgroundUrl = anime.getReflectiveProp("background_url") as? String,
        fetchType = (anime.getReflectiveProp("fetch_type") as? Enum<*>)?.name
            ?: anime.getReflectiveProp("fetch_type")?.toString(),
        seasonNumber = (anime.getReflectiveProp("season_number") as? Number)?.toDouble(),
        initialized = anime.getReflectiveProp("initialized") as? Boolean,
    )

    fun toDomain(dto: AnimeSnapshotDto): SAnime = SAnime.create().apply {
        url = dto.url
        title = dto.title
        artist = dto.artist
        author = dto.author
        description = dto.description
        genre = dto.genre
        status = dto.status
        thumbnail_url = dto.thumbnailUrl
        if (dto.backgroundUrl != null) setReflectiveProp("background_url", dto.backgroundUrl)
        if (dto.seasonNumber != null) setReflectiveProp("season_number", dto.seasonNumber)
        if (dto.initialized != null) setReflectiveProp("initialized", dto.initialized)
    }

    fun toDto(episode: SEpisode): EpisodeSnapshotDto = EpisodeSnapshotDto(
        url = episode.url,
        name = episode.name,
        dateUpload = episode.date_upload,
        episodeNumber = episode.episode_number,
        scanlator = episode.scanlator,
        thumbnailUrl = episode.getReflectiveProp("thumbnail_url") as? String
            ?: episode.getReflectiveProp("preview_url") as? String,
        summary = episode.getReflectiveProp("summary") as? String,
        introStart = (episode.getReflectiveProp("intro_start") as? Number)?.toLong(),
        introEnd = (episode.getReflectiveProp("intro_end") as? Number)?.toLong(),
    )

    fun toDomain(dto: EpisodeSnapshotDto): SEpisode = SEpisode.create().apply {
        url = dto.url
        name = dto.name
        date_upload = dto.dateUpload
        episode_number = dto.episodeNumber
        scanlator = dto.scanlator
        if (dto.thumbnailUrl != null) setReflectiveProp("thumbnail_url", dto.thumbnailUrl)
        if (dto.summary != null) setReflectiveProp("summary", dto.summary)
        if (dto.introStart != null) setReflectiveProp("intro_start", dto.introStart)
        if (dto.introEnd != null) setReflectiveProp("intro_end", dto.introEnd)
    }

    fun toDto(hoster: Hoster): HosterSnapshotDto = HosterSnapshotDto(
        hosterName = hoster.hosterName,
        internalData = safeGet { hoster.internalData },
        hosterUrl = safeGet { hoster.hosterUrl },
    )

    fun toDto(video: Video): VideoSnapshotDto = VideoSnapshotDto(
        url = safeGet { video.videoUrl } ?: safeGet { video.getReflectiveProp("url") as? String }.orEmpty(),
        quality = safeGet { video.videoTitle } ?: safeGet { video.getReflectiveProp("quality") as? String }.orEmpty(),
        videoUrl = safeGet { video.videoUrl },
        headers = safeGet { video.headers?.toMultimap()?.mapValues { it.value.joinToString(", ") } } ?: emptyMap(),
        subtitleTracks = safeGet {
            video.subtitleTracks.map { TrackDto(it.url, it.lang) }
        } ?: emptyList(),
        audioTracks = safeGet {
            video.audioTracks.map { TrackDto(it.url, it.lang) }
        } ?: emptyList(),
    )

    fun toDto(request: Request): RequestSnapshotDto = RequestSnapshotDto(
        url = request.url.toString(),
        method = request.method,
        headers = request.headers.toMultimap().mapValues { it.value.joinToString(", ") },
    )
}
