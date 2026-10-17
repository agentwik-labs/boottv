package dev.boottv.jellyfin

import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class JellyfinException(val code: Int, message: String) : IOException(message)

/** Low-level HTTP shared by authenticated and unauthenticated calls. */
internal object JfHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Fire-and-forget work that must outlive a screen (e.g. "playback stopped" reports). */
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val deviceName: String = (Build.MODEL ?: "Android TV").replace("\"", "")

    fun authHeader(deviceId: String, token: String?): String = buildString {
        append("MediaBrowser Client=\"BootTV\", Device=\"")
        append(deviceName)
        append("\", DeviceId=\"")
        append(deviceId)
        append("\", Version=\"1.0\"")
        if (!token.isNullOrBlank()) append(", Token=\"").append(token).append('"')
    }

    suspend fun call(
        method: String,
        url: String,
        deviceId: String,
        token: String?,
        body: JsonElement? = null,
    ): String = withContext(Dispatchers.IO) {
        val reqBody = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> "".toRequestBody(JSON)
            else -> null
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(deviceId, token))
            .header("Accept", "application/json")
            .method(method, reqBody)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw JellyfinException(resp.code, "HTTP ${resp.code}")
            text
        }
    }

    fun query(vararg pairs: Pair<String, Any?>): String =
        pairs.filter { it.second != null && it.second.toString().isNotEmpty() }
            .joinToString("&") { "${it.first}=${Uri.encode(it.second.toString())}" }
}

/** Normalizes what the user typed into a base URL like http://192.168.1.5:8096 */
fun normalizeServerUrl(input: String): String {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    val uri = Uri.parse(s)
    if (uri.port == -1 && uri.scheme.equals("http", true) && (uri.path.isNullOrEmpty() || uri.path == "/")) {
        s = "$s:8096"
    }
    return s
}

/** Calls that don't need a signed-in user: server probing, login, Quick Connect. */
object JellyfinAuth {
    suspend fun publicInfo(server: String): PublicSystemInfo {
        val deviceId = JellyfinStore.deviceId()
        return JfJson.decodeFromString(JfHttp.call("GET", "$server/System/Info/Public", deviceId, null))
    }

    suspend fun authenticateByName(server: String, user: String, password: String): AuthResult {
        val deviceId = JellyfinStore.deviceId()
        val body = buildJsonObject { put("Username", user); put("Pw", password) }
        return JfJson.decodeFromString(JfHttp.call("POST", "$server/Users/AuthenticateByName", deviceId, null, body))
    }

    suspend fun quickConnectEnabled(server: String): Boolean {
        val deviceId = JellyfinStore.deviceId()
        return runCatching {
            JfHttp.call("GET", "$server/QuickConnect/Enabled", deviceId, null).trim().equals("true", true)
        }.getOrDefault(false)
    }

    suspend fun quickConnectInitiate(server: String): QuickConnectResult {
        val deviceId = JellyfinStore.deviceId()
        val text = try {
            JfHttp.call("POST", "$server/QuickConnect/Initiate", deviceId, null)
        } catch (e: JellyfinException) {
            // Servers older than 10.9 use GET.
            if (e.code == 404 || e.code == 405) JfHttp.call("GET", "$server/QuickConnect/Initiate", deviceId, null) else throw e
        }
        return JfJson.decodeFromString(text)
    }

    suspend fun quickConnectState(server: String, secret: String): QuickConnectResult {
        val deviceId = JellyfinStore.deviceId()
        return JfJson.decodeFromString(
            JfHttp.call("GET", "$server/QuickConnect/Connect?${JfHttp.query("Secret" to secret)}", deviceId, null)
        )
    }

    suspend fun quickConnectAuthenticate(server: String, secret: String): AuthResult {
        val deviceId = JellyfinStore.deviceId()
        val body = buildJsonObject { put("Secret", secret) }
        return JfJson.decodeFromString(
            JfHttp.call("POST", "$server/Users/AuthenticateWithQuickConnect", deviceId, null, body)
        )
    }
}

/** Authenticated REST client for one session. */
class JellyfinClient(val session: JellyfinSession) {
    val server = session.server
    private val uid = session.userId

    companion object {
        private const val FIELDS = "Overview,PrimaryImageAspectRatio,Genres,Taglines"
        private const val IMAGE_TYPES = "Primary,Backdrop,Thumb,Logo"

        /** Servers that 404 the 10.9+ routes fall back to the legacy /Users/{id}/... routes. */
        private val legacyServers = mutableSetOf<String>()

        @Volatile private var cached: JellyfinClient? = null

        /** Client for the stored session, or null when not signed in. */
        suspend fun current(): JellyfinClient? {
            val s = JellyfinStore.session() ?: run { cached = null; return null }
            cached?.let { if (it.session == s) return it }
            return JellyfinClient(s).also { cached = it }
        }
    }

    private suspend fun raw(method: String, path: String, body: JsonElement? = null): String =
        JfHttp.call(method, server + path, session.deviceId, session.token, body)

    /** Tries the modern route first, then the legacy one (remembered per server). */
    private suspend fun dual(newPath: String, legacyPath: String, method: String = "GET", body: JsonElement? = null): String {
        if (server !in legacyServers) {
            try {
                return raw(method, newPath, body)
            } catch (e: JellyfinException) {
                if (e.code != 404) throw e
                synchronized(legacyServers) { legacyServers += server }
            }
        }
        return raw(method, legacyPath, body)
    }

    private val listParams get() = JfHttp.query("Fields" to FIELDS, "EnableImageTypes" to IMAGE_TYPES, "ImageTypeLimit" to 1, "EnableUserData" to true)

    // ---------------------------------------------------------------- images

    fun imageUrl(itemId: String, type: String, tag: String? = null, maxWidth: Int = 600, index: Int? = null): String {
        val idx = index?.let { "/$it" } ?: ""
        return "$server/Items/$itemId/Images/$type$idx?" + JfHttp.query("maxWidth" to maxWidth, "tag" to tag, "quality" to 90)
    }

    fun posterUrl(item: BaseItem, maxWidth: Int = 400): String? = when {
        item.isEpisode && item.seriesId != null && item.seriesPrimaryImageTag != null ->
            imageUrl(item.seriesId, "Primary", item.seriesPrimaryImageTag, maxWidth)
        item.imageTags?.get("Primary") != null -> imageUrl(item.id, "Primary", item.imageTags["Primary"], maxWidth)
        item.seriesId != null && item.seriesPrimaryImageTag != null -> imageUrl(item.seriesId, "Primary", item.seriesPrimaryImageTag, maxWidth)
        else -> null
    }

    fun backdropUrl(item: BaseItem, maxWidth: Int = 1280): String? = when {
        !item.backdropImageTags.isNullOrEmpty() -> imageUrl(item.id, "Backdrop", item.backdropImageTags.first(), maxWidth, 0)
        item.parentBackdropItemId != null && !item.parentBackdropImageTags.isNullOrEmpty() ->
            imageUrl(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags.first(), maxWidth, 0)
        item.imageTags?.get("Thumb") != null -> imageUrl(item.id, "Thumb", item.imageTags["Thumb"], maxWidth)
        item.isEpisode && item.imageTags?.get("Primary") != null -> imageUrl(item.id, "Primary", item.imageTags["Primary"], maxWidth)
        else -> null
    }

    /** 16:9 artwork for landscape cards. */
    fun wideUrl(item: BaseItem, maxWidth: Int = 500): String? = when {
        item.isEpisode && item.imageTags?.get("Primary") != null -> imageUrl(item.id, "Primary", item.imageTags["Primary"], maxWidth)
        item.imageTags?.get("Thumb") != null -> imageUrl(item.id, "Thumb", item.imageTags["Thumb"], maxWidth)
        item.parentThumbItemId != null && item.parentThumbImageTag != null ->
            imageUrl(item.parentThumbItemId, "Thumb", item.parentThumbImageTag, maxWidth)
        item.isFolder == true && item.type == "CollectionFolder" && item.imageTags?.get("Primary") != null ->
            imageUrl(item.id, "Primary", item.imageTags["Primary"], maxWidth)
        else -> backdropUrl(item, maxWidth) ?: item.imageTags?.get("Primary")?.let { imageUrl(item.id, "Primary", it, maxWidth) }
    }

    fun logoUrl(item: BaseItem, maxWidth: Int = 600): String? = when {
        item.imageTags?.get("Logo") != null -> imageUrl(item.id, "Logo", item.imageTags["Logo"], maxWidth)
        item.parentLogoItemId != null && item.parentLogoImageTag != null ->
            imageUrl(item.parentLogoItemId, "Logo", item.parentLogoImageTag, maxWidth)
        else -> null
    }

    // ---------------------------------------------------------------- browse

    suspend fun views(): List<BaseItem> =
        JfJson.decodeFromString<ItemsResult>(dual("/UserViews?userId=$uid", "/Users/$uid/Views")).items

    suspend fun resume(limit: Int = 20): List<BaseItem> {
        val q = "Limit=$limit&MediaTypes=Video&$listParams"
        return JfJson.decodeFromString<ItemsResult>(
            dual("/UserItems/Resume?userId=$uid&$q", "/Users/$uid/Items/Resume?$q")
        ).items
    }

    suspend fun nextUp(limit: Int = 20, seriesId: String? = null): List<BaseItem> {
        val q = JfHttp.query("userId" to uid, "Limit" to limit, "SeriesId" to seriesId, "EnableResumable" to false)
        return JfJson.decodeFromString<ItemsResult>(raw("GET", "/Shows/NextUp?$q&$listParams")).items
    }

    suspend fun latest(parentId: String, limit: Int = 20): List<BaseItem> {
        val q = "ParentId=$parentId&Limit=$limit&$listParams"
        return JfJson.decodeFromString(dual("/Items/Latest?userId=$uid&$q", "/Users/$uid/Items/Latest?$q"))
    }

    suspend fun items(vararg params: Pair<String, Any?>): ItemsResult {
        val q = JfHttp.query(*params) + "&$listParams"
        return JfJson.decodeFromString(dual("/Items?userId=$uid&$q", "/Users/$uid/Items?$q"))
    }

    suspend fun item(id: String): BaseItem {
        val q = "Fields=$FIELDS,MediaStreams"
        return JfJson.decodeFromString(dual("/Items/$id?userId=$uid&$q", "/Users/$uid/Items/$id?$q"))
    }

    suspend fun seasons(seriesId: String): List<BaseItem> =
        JfJson.decodeFromString<ItemsResult>(raw("GET", "/Shows/$seriesId/Seasons?userId=$uid&$listParams")).items

    suspend fun episodes(seriesId: String, seasonId: String? = null, startItemId: String? = null, limit: Int? = null): List<BaseItem> {
        val q = JfHttp.query("userId" to uid, "SeasonId" to seasonId, "StartItemId" to startItemId, "Limit" to limit)
        return JfJson.decodeFromString<ItemsResult>(raw("GET", "/Shows/$seriesId/Episodes?$q&$listParams")).items
    }

    suspend fun similar(id: String, limit: Int = 16): List<BaseItem> =
        JfJson.decodeFromString<ItemsResult>(raw("GET", "/Items/$id/Similar?userId=$uid&Limit=$limit&$listParams")).items

    suspend fun search(term: String, limit: Int = 20): List<BaseItem> = items(
        "searchTerm" to term,
        "Recursive" to true,
        "IncludeItemTypes" to "Movie,Series,Episode",
        "Limit" to limit,
    ).items

    /** The episode after [episode] in the series, if any. */
    suspend fun nextEpisode(episode: BaseItem): BaseItem? {
        val seriesId = episode.seriesId ?: return null
        val list = episodes(seriesId, startItemId = episode.id, limit = 2)
        val idx = list.indexOfFirst { it.id == episode.id }
        return if (idx >= 0) list.getOrNull(idx + 1) else null
    }

    suspend fun setPlayed(id: String, played: Boolean): UserData {
        val m = if (played) "POST" else "DELETE"
        return JfJson.decodeFromString(dual("/UserPlayedItems/$id?userId=$uid", "/Users/$uid/PlayedItems/$id", m))
    }

    suspend fun setFavorite(id: String, favorite: Boolean): UserData {
        val m = if (favorite) "POST" else "DELETE"
        return JfJson.decodeFromString(dual("/UserFavoriteItems/$id?userId=$uid", "/Users/$uid/FavoriteItems/$id", m))
    }

    // ---------------------------------------------------------------- playback

    suspend fun playbackInfo(
        id: String,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        allowDirect: Boolean,
    ): PlaybackInfoResponse {
        val body = buildJsonObject {
            put("UserId", uid)
            put("DeviceProfile", DeviceProfiles.build())
            put("MaxStreamingBitrate", DeviceProfiles.MAX_BITRATE)
            audioStreamIndex?.let { put("AudioStreamIndex", it) }
            subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
            put("EnableDirectPlay", allowDirect)
            put("EnableDirectStream", allowDirect)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", true)
        }
        return JfJson.decodeFromString(raw("POST", "/Items/$id/PlaybackInfo?userId=$uid", body))
    }

    private fun progressBody(
        itemId: String, mediaSourceId: String?, playSessionId: String?, positionTicks: Long,
        paused: Boolean, playMethod: String, audio: Int?, subtitle: Int?,
    ): JsonObject = buildJsonObject {
        put("ItemId", itemId)
        mediaSourceId?.let { put("MediaSourceId", it) }
        playSessionId?.let { put("PlaySessionId", it) }
        put("PositionTicks", positionTicks)
        put("IsPaused", paused)
        put("CanSeek", true)
        put("PlayMethod", playMethod)
        audio?.let { put("AudioStreamIndex", it) }
        put("SubtitleStreamIndex", subtitle ?: -1)
    }

    suspend fun reportStart(
        itemId: String, mediaSourceId: String?, playSessionId: String?, positionTicks: Long,
        playMethod: String, audio: Int?, subtitle: Int?,
    ) {
        runCatching {
            raw("POST", "/Sessions/Playing",
                progressBody(itemId, mediaSourceId, playSessionId, positionTicks, false, playMethod, audio, subtitle))
        }
    }

    suspend fun reportProgress(
        itemId: String, mediaSourceId: String?, playSessionId: String?, positionTicks: Long,
        paused: Boolean, playMethod: String, audio: Int?, subtitle: Int?,
    ) {
        runCatching {
            raw("POST", "/Sessions/Playing/Progress",
                progressBody(itemId, mediaSourceId, playSessionId, positionTicks, paused, playMethod, audio, subtitle))
        }
    }

    suspend fun reportStopped(itemId: String, mediaSourceId: String?, playSessionId: String?, positionTicks: Long, transcoding: Boolean) {
        runCatching {
            raw("POST", "/Sessions/Playing/Stopped", buildJsonObject {
                put("ItemId", itemId)
                mediaSourceId?.let { put("MediaSourceId", it) }
                playSessionId?.let { put("PlaySessionId", it) }
                put("PositionTicks", positionTicks)
            })
        }
        if (transcoding && playSessionId != null) {
            runCatching {
                raw("DELETE", "/Videos/ActiveEncodings?" + JfHttp.query("deviceId" to session.deviceId, "playSessionId" to playSessionId))
            }
        }
    }
}
