package dev.boottv.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream

/**
 * i.mjh.nz no longer publishes its ready-made M3U playlists for the free FAST services, but it still
 * publishes the channel metadata (`.channels.json.gz`) that those playlists were generated from,
 * and the jmp2.uk redirector still resolves each channel to the service's official stream.
 * This rebuilds the same playlist on-device so the suggested sources keep working.
 */
internal object MjhChannels {
    data class Target(val provider: String, val region: String) {
        val jsonUrl get() = "https://i.mjh.nz/$provider/.channels.json.gz"
    }

    private val urlRegex = Regex("""^https?://i\.mjh\.nz/([A-Za-z0-9_]+)/([A-Za-z0-9_-]+)\.m3u8?(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun target(url: String): Target? {
        val m = urlRegex.find(url.trim()) ?: return null
        return Target(m.groupValues[1], m.groupValues[2].lowercase())
    }

    private fun slugFor(provider: String, root: JsonObject): String {
        root["slug"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return when (provider.lowercase()) {
            "plutotv" -> "plu-{id}.m3u8"
            "samsungtvplus" -> "stvp-{id}"
            "roku" -> "rok-{id}.m3u8"
            "plex" -> "plex-{id}.m3u8"
            else -> throw IOException("Unsupported i.mjh.nz provider: $provider")
        }
    }

    fun parse(input: InputStream, target: Target, json: Json): List<M3uEntry> {
        val text = XmltvParser.maybeGunzip(input).readBytes().toString(Charsets.UTF_8)
        val root = json.parseToJsonElement(text).jsonObject
        val slug = slugFor(target.provider, root)
        val ua = (root["headers"] as? JsonObject)?.entries
            ?.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value?.jsonPrimitive?.contentOrNull

        val regions = root["regions"] as? JsonObject
        val regionObj = regions?.get(target.region) as? JsonObject
        val channels: Map<String, JsonObject> = when {
            regionObj?.get("channels") is JsonObject ->
                (regionObj["channels"] as JsonObject).mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap()
            root["channels"] is JsonObject -> {
                val all = (root["channels"] as JsonObject).mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
                if (regionObj != null) all.filter { (_, c) ->
                    (c["regions"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == target.region } ?: true
                }.toMap() else all.toMap()
            }
            regions != null -> regions.values.mapNotNull { (it as? JsonObject)?.get("channels") as? JsonObject }
                .flatMap { r -> r.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } } }.toMap()
            else -> emptyMap()
        }
        if (channels.isEmpty()) throw IOException("No channels for region '${target.region}'")

        return channels.map { (id, c) ->
            val groups = buildList {
                c["group"]?.jsonPrimitive?.contentOrNull?.let { add(it) }
                (c["groups"] as? JsonArray)?.forEach { g -> (g as? JsonPrimitive)?.contentOrNull?.let { add(it) } }
            }.filter { it.isNotBlank() }.distinct()
            val chno = c["chno"]?.jsonPrimitive?.contentOrNull
            M3uEntry(
                name = c["name"]?.jsonPrimitive?.contentOrNull ?: id,
                url = "https://jmp2.uk/" + slug.replace("{id}", id),
                tvgId = id,
                tvgName = null,
                logo = c["logo"]?.jsonPrimitive?.contentOrNull,
                groups = groups,
                number = chno,
                userAgent = ua,
                headers = emptyMap(),
            )
        }
    }
}
