package dev.boottv.jellyfin

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Jellyfin uses PascalCase JSON; map camelCase Kotlin properties onto it. */
@OptIn(ExperimentalSerializationApi::class)
internal val JfJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    namingStrategy = JsonNamingStrategy { _, _, name -> name.replaceFirstChar { it.uppercaseChar() } }
}

const val TICKS_PER_MS = 10_000L

@Serializable
data class UserData(
    val playbackPositionTicks: Long = 0,
    val playedPercentage: Double? = null,
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val unplayedItemCount: Int? = null,
)

@Serializable
data class MediaStream(
    val index: Int = 0,
    val type: String? = null,
    val codec: String? = null,
    val language: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val isExternal: Boolean = false,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isTextSubtitleStream: Boolean = false,
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
    val channels: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class BaseItem(
    val id: String = "",
    val name: String? = null,
    val type: String? = null,
    val overview: String? = null,
    val productionYear: Int? = null,
    val communityRating: Float? = null,
    val officialRating: String? = null,
    val runTimeTicks: Long? = null,
    val imageTags: Map<String, String>? = null,
    val backdropImageTags: List<String>? = null,
    val parentBackdropItemId: String? = null,
    val parentBackdropImageTags: List<String>? = null,
    val parentThumbItemId: String? = null,
    val parentThumbImageTag: String? = null,
    val parentLogoItemId: String? = null,
    val parentLogoImageTag: String? = null,
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesPrimaryImageTag: String? = null,
    val seasonId: String? = null,
    val seasonName: String? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val userData: UserData? = null,
    val collectionType: String? = null,
    val genres: List<String>? = null,
    val isFolder: Boolean? = null,
    val childCount: Int? = null,
    val premiereDate: String? = null,
    val endDate: String? = null,
    val status: String? = null,
    val mediaType: String? = null,
    val container: String? = null,
    val mediaStreams: List<MediaStream>? = null,
    val taglines: List<String>? = null,
) {
    val isSeries get() = type == "Series"
    val isEpisode get() = type == "Episode"
    val isSeason get() = type == "Season"

    /** Containers that open as a grid rather than a detail page. */
    val isBrowsableFolder
        get() = type in setOf("CollectionFolder", "Folder", "UserView", "PhotoAlbum", "Playlist", "ManualPlaylistsFolder")

    val resumeTicks get() = userData?.playbackPositionTicks ?: 0L

    val progress: Float
        get() {
            val pos = userData?.playbackPositionTicks ?: 0
            val total = runTimeTicks ?: 0
            if (pos <= 0 || total <= 0) return 0f
            return (pos.toFloat() / total).coerceIn(0f, 1f)
        }

    val episodeLabel: String?
        get() {
            if (!isEpisode) return null
            val s = parentIndexNumber
            val e = indexNumber
            return when {
                s != null && e != null -> "S$s:E$e"
                e != null -> "E$e"
                else -> null
            }
        }

    /** Title for cards: episodes show the series name. */
    val cardTitle: String get() = if (isEpisode) seriesName ?: name.orEmpty() else name.orEmpty()

    val cardSubtitle: String?
        get() = when {
            isEpisode -> listOfNotNull(episodeLabel, name).joinToString(" · ")
            isSeries -> productionYear?.toString()
            else -> productionYear?.toString()
        }

    val runtimeText: String?
        get() = runTimeTicks?.takeIf { it > 0 }?.let { formatRuntime(it / TICKS_PER_MS) }

    val remainingText: String?
        get() {
            val total = runTimeTicks ?: return null
            val pos = userData?.playbackPositionTicks ?: return null
            if (pos <= 0) return null
            val left = (total - pos) / TICKS_PER_MS
            return if (left > 0) "${formatRuntime(left)} left" else null
        }
}

@Serializable
data class ItemsResult(
    val items: List<BaseItem> = emptyList(),
    val totalRecordCount: Int = 0,
    val startIndex: Int = 0,
)

@Serializable
data class JfUser(val id: String = "", val name: String? = null)

@Serializable
data class AuthResult(val user: JfUser? = null, val accessToken: String? = null, val serverId: String? = null)

@Serializable
data class PublicSystemInfo(val serverName: String? = null, val version: String? = null, val id: String? = null)

@Serializable
data class QuickConnectResult(
    val secret: String? = null,
    val code: String? = null,
    val authenticated: Boolean = false,
)

@Serializable
data class MediaSourceInfo(
    val id: String? = null,
    val container: String? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    val transcodingUrl: String? = null,
    val transcodingSubProtocol: String? = null,
    val mediaStreams: List<MediaStream> = emptyList(),
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val runTimeTicks: Long? = null,
)

@Serializable
data class PlaybackInfoResponse(
    val mediaSources: List<MediaSourceInfo> = emptyList(),
    val playSessionId: String? = null,
    val errorCode: String? = null,
)

@Serializable
data class DiscoveredServer(val address: String = "", val name: String? = null, val id: String? = null)

fun formatRuntime(ms: Long): String {
    val totalMin = (ms / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${maxOf(m, 1)}m"
    }
}

fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
