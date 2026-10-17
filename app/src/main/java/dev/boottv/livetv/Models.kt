package dev.boottv.livetv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind { M3U, HDHOMERUN }

/** A user-added channel source: an M3U playlist (optionally with XMLTV) or an HDHomeRun tuner. */
@Serializable
data class LiveSource(
    val id: String,
    val name: String,
    val kind: SourceKind = SourceKind.M3U,
    /** Playlist URL for [SourceKind.M3U], tuner IP / host for [SourceKind.HDHOMERUN]. */
    val url: String,
    val epgUrl: String = "",
    val enabled: Boolean = true,
    val lastRefresh: Long = 0L,
    val lastAttempt: Long = 0L,
    val channelCount: Int = 0,
    val programmeCount: Int = 0,
    val lastError: String? = null,
)

@Serializable
data class Channel(
    val id: String,
    val sourceId: String,
    val name: String,
    val number: String? = null,
    val logo: String? = null,
    val groups: List<String> = emptyList(),
    val url: String,
    val tvgId: String? = null,
    /** Lower-cased XMLTV channel id resolved at refresh time (may be null when no guide data). */
    val epgId: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class Programme(
    @SerialName("s") val start: Long,
    @SerialName("e") val stop: Long,
    @SerialName("t") val title: String,
    @SerialName("u") val subtitle: String? = null,
    @SerialName("d") val desc: String? = null,
    @SerialName("c") val category: String? = null,
)

/** On-disk cache of one source's parsed data. Programmes keyed by lower-cased XMLTV channel id. */
@Serializable
data class SourceCache(
    val channels: List<Channel> = emptyList(),
    val programmes: Map<String, List<Programme>> = emptyMap(),
    val refreshedAt: Long = 0L,
)

@Serializable
data class LiveConfig(
    val sources: List<LiveSource> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val lastChannelId: String? = null,
    val previousChannelId: String? = null,
    val autoSkip: Boolean = false,
)

/** Snapshot consumed by the UI. */
data class LiveState(
    val loaded: Boolean = false,
    val sources: List<LiveSource> = emptyList(),
    /** Channels from enabled sources, in guide order. */
    val channels: List<Channel> = emptyList(),
    /** Programmes keyed by [Channel.id], sorted by start. */
    val programmes: Map<String, List<Programme>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val refreshing: Set<String> = emptySet(),
    val lastChannelId: String? = null,
    val previousChannelId: String? = null,
    val autoSkip: Boolean = false,
) {
    val groups: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        channels.forEach { c -> c.groups.forEach { seen.add(it) } }
        seen.toList()
    }

    fun channel(id: String?): Channel? = if (id == null) null else channels.firstOrNull { it.id == id }

    fun programmesFor(channelId: String): List<Programme> = programmes[channelId].orEmpty()

    fun nowAndNext(channelId: String, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = programmesFor(channelId)
        val idx = list.indexOfFirst { it.stop > now }
        if (idx < 0) return null to null
        val first = list[idx]
        return if (first.start <= now) first to list.getOrNull(idx + 1) else null to first
    }
}

data class SuggestedSource(val name: String, val m3u: String, val epg: String)

val SuggestedSources = listOf(
    SuggestedSource("Pluto TV (US)", "https://i.mjh.nz/PlutoTV/us.m3u8", "https://i.mjh.nz/PlutoTV/us.xml.gz"),
    SuggestedSource("Samsung TV Plus (US)", "https://i.mjh.nz/SamsungTVPlus/us.m3u8", "https://i.mjh.nz/SamsungTVPlus/us.xml.gz"),
    SuggestedSource("Plex Live TV (US)", "https://i.mjh.nz/Plex/us.m3u8", "https://i.mjh.nz/Plex/us.xml.gz"),
    SuggestedSource("Roku Channel (US)", "https://i.mjh.nz/Roku/all.m3u8", "https://i.mjh.nz/Roku/all.xml.gz"),
)
