package dev.boottv.archive

import android.net.Uri
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A film or short in one of the Internet Archive's curated public-domain collections. */
data class ArchiveItem(val id: String, val title: String, val year: String?) {
    val thumbUrl get() = "https://archive.org/services/img/$id"
}

data class ArchiveDetails(
    val item: ArchiveItem,
    val description: String?,
    val runtime: String?,
    val creator: String?,
    /** "Public domain", "Creative Commons" or "Free to watch", from the item's license. */
    val license: String,
    /** Best MP4 to stream (H.264 when available). */
    val videoUrl: String?,
    /** Smaller full-length MP4 (up to 480p) to save to the device. */
    val downloadUrl: String?,
    val downloadMd5: String?,
    /** The Archive's own BitTorrent file for this item. */
    val torrentUrl: String?,
)

/** A row on the Free Movies screen: one curated archive.org collection. */
data class ArchiveShelf(val title: String, val collection: String)

/**
 * Only the Archive's staff-curated public-domain collections, never open user-upload ones,
 * and only well-rated titles (popularity alone surfaces junk).
 */
val ArchiveShelves = listOf(
    ArchiveShelf("Classic Cartoons", "classic_cartoons"),
    ArchiveShelf("Feature Films", "feature_films"),
    ArchiveShelf("Film Noir", "Film_Noir"),
    ArchiveShelf("Sci-Fi & Horror", "SciFi_Horror"),
    ArchiveShelf("Silent Films", "silent_films"),
)

object ArchiveClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val collections = ArchiveShelves.joinToString(" OR ") { it.collection }
    private const val QUALITY = "avg_rating:[4 TO 5] AND num_reviews:[3 TO 100000]"

    suspend fun shelf(shelf: ArchiveShelf, rows: Int = 40): List<ArchiveItem> =
        query("collection:${shelf.collection} AND mediatype:movies AND $QUALITY", rows)

    /** Title search across the curated collections. */
    suspend fun search(text: String, rows: Int = 30): List<ArchiveItem> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val title = words.joinToString(" AND ") { "title:$it*" }
        return query("($title) AND collection:($collections) AND mediatype:movies", rows)
    }

    private suspend fun query(q: String, rows: Int): List<ArchiveItem> {
        val url = Uri.parse("https://archive.org/advancedsearch.php").buildUpon()
            .appendQueryParameter("q", q)
            .appendQueryParameter("fl[]", "identifier")
            .appendQueryParameter("fl[]", "title")
            .appendQueryParameter("fl[]", "year")
            .appendQueryParameter("sort[]", "num_reviews desc")
            .appendQueryParameter("rows", rows.toString())
            .appendQueryParameter("output", "json")
            .build().toString()
        val root = getJson(url).jsonObject
        val docs = root["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
        return docs.mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            val id = o.str("identifier") ?: return@mapNotNull null
            ArchiveItem(id, o.str("title") ?: id, o.str("year"))
        }.distinctBy { it.title.lowercase() }
    }

    suspend fun details(id: String): ArchiveDetails {
        val root = getJson("https://archive.org/metadata/${Uri.encode(id)}").jsonObject
        val meta = root["metadata"]?.jsonObject ?: throw IOException("This title isn't available.")
        val files = root["files"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        fun file(pred: (JsonObject) -> Boolean) = files.filter(pred)
        // Items often hold extras and clips too: keep MP4s close to the full running time, then take the
        // sharpest one up to 720p (smooth over Wi-Fi), else the smallest larger one.
        val mp4s = file { it.str("name")?.endsWith(".mp4", true) == true }
        fun len(f: JsonObject) = f.str("length")?.toDoubleOrNull() ?: 0.0
        fun height(f: JsonObject) = f.str("height")?.toIntOrNull() ?: 0
        val longest = mp4s.maxOfOrNull { len(it) } ?: 0.0
        val full = mp4s.filter { len(it) >= longest * 0.9 }
        val mp4 = full.filter { height(it) in 1..720 }.maxByOrNull { height(it) }
            ?: full.filter { height(it) > 720 }.minByOrNull { height(it) }
            ?: full.maxByOrNull { it.str("size")?.toLongOrNull() ?: 0L }
        // Saving to a stick with little storage: up to 480p, else the smallest full-length file.
        val small = full.filter { height(it) in 1..480 }.maxByOrNull { height(it) }
            ?: full.minByOrNull { it.str("size")?.toLongOrNull() ?: Long.MAX_VALUE }
        val torrent = file { it.str("format") == "Archive BitTorrent" }.firstOrNull()
        fun download(f: JsonObject?) = f?.str("name")?.let { "https://archive.org/download/${Uri.encode(id)}/${Uri.encode(it, "/")}" }
        val description = meta.str("description")?.let {
            HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
        }?.takeIf { it.isNotEmpty() }
        return ArchiveDetails(
            item = ArchiveItem(id, meta.str("title") ?: id, meta.str("year") ?: meta.str("date")?.take(4)),
            description = description,
            runtime = meta.str("runtime"),
            creator = meta.str("creator"),
            license = meta.str("licenseurl").orEmpty().lowercase().let {
                when {
                    "publicdomain" in it -> "Public domain"
                    "creativecommons" in it -> "Creative Commons"
                    else -> "Free to watch"
                }
            },
            videoUrl = download(mp4),
            downloadUrl = download(small),
            downloadMd5 = small?.str("md5"),
            torrentUrl = download(torrent),
        )
    }

    private suspend fun getJson(url: String): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("archive.org returned ${resp.code}")
            json.parseToJsonElement(resp.body?.string().orEmpty())
        }
    }

    /** Metadata fields are a string or an array of strings. */
    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }
}
