package dev.boottv.livetv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class XmltvResult(
    /** Programmes keyed by lower-cased XMLTV channel id, sorted by start. */
    val programmes: Map<String, List<Programme>>,
    /** Lower-cased XMLTV channel id -> normalized display names. */
    val channelNames: Map<String, List<String>>,
)

/**
 * Streaming XMLTV parser built on XmlPullParser. Handles gzip transparently (by magic bytes) and
 * keeps memory bounded: only programmes inside [windowStart, windowEnd) for wanted channels are
 * kept, descriptions are truncated, and per-channel / total counts are capped.
 */
object XmltvParser {
    private const val MAX_DESC = 280
    private const val MAX_PER_CHANNEL = 200
    private const val MAX_TOTAL = 200_000

    fun normalizeName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * @param wantedIds lower-cased tvg-ids from the playlist.
     * @param wantedNames normalized names of playlist channels (used when tvg-id doesn't match).
     */
    fun parse(
        raw: InputStream,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
        windowStart: Long,
        windowEnd: Long,
    ): XmltvResult {
        val input = maybeGunzip(raw)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val channelNames = HashMap<String, MutableList<String>>()
        val programmes = HashMap<String, MutableList<Programme>>()
        // Decided per channel id on first sight (channels may be interleaved with programmes).
        val decided = HashMap<String, Boolean>()
        var total = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")?.trim()?.lowercase()
                        val names = readChannelNames(parser)
                        if (!id.isNullOrEmpty()) channelNames.getOrPut(id) { ArrayList(2) }.addAll(names)
                    }
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        val keep = ch != null && start != null && total < MAX_TOTAL &&
                            decided.getOrPut(ch) { accept(ch, channelNames, wantedIds, wantedNames) } &&
                            (stop ?: (start + 30 * 60_000L)) > windowStart && start < windowEnd
                        if (keep) {
                            val p = readProgramme(parser, start!!, stop ?: (start + 30 * 60_000L))
                            if (p != null) {
                                val list = programmes.getOrPut(ch!!) { ArrayList() }
                                if (list.size < MAX_PER_CHANNEL) {
                                    list.add(p); total++
                                }
                            }
                        } else {
                            skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
        val sorted = programmes.mapValues { (_, list) -> dedupe(list.sortedBy { it.start }) }
        return XmltvResult(sorted, channelNames)
    }

    private fun accept(
        id: String,
        channelNames: Map<String, List<String>>,
        wantedIds: Set<String>,
        wantedNames: Set<String>,
    ): Boolean {
        if (wantedIds.isEmpty() && wantedNames.isEmpty()) return true
        if (id in wantedIds) return true
        if (normalizeName(id) in wantedNames) return true
        return channelNames[id]?.any { it in wantedNames } == true
    }

    private fun dedupe(list: List<Programme>): List<Programme> {
        if (list.size < 2) return list
        val out = ArrayList<Programme>(list.size)
        for (p in list) {
            val last = out.lastOrNull()
            if (last != null && p.start < last.stop) {
                if (p.start == last.start) continue // duplicate slot
                out[out.size - 1] = last.copy(stop = p.start) // trim overlap
            }
            out.add(p)
        }
        return out
    }

    private fun readChannelNames(parser: XmlPullParser): List<String> {
        val names = ArrayList<String>(2)
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if (ev == XmlPullParser.START_TAG && parser.name == "display-name") {
                val t = readText(parser)
                if (t.isNotBlank()) names.add(normalizeName(t))
            }
        }
        return names
    }

    private fun readProgramme(parser: XmlPullParser, start: Long, stop: Long): Programme? {
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var cat: String? = null
        var icon: String? = null
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if (ev == XmlPullParser.START_TAG && parser.depth == depth + 1) {
                when (parser.name) {
                    "title" -> { val t = readText(parser); if (title == null && t.isNotBlank()) title = t.trim() }
                    "sub-title" -> { val t = readText(parser); if (sub == null && t.isNotBlank()) sub = t.trim() }
                    "desc" -> {
                        val t = readText(parser)
                        if (desc == null && t.isNotBlank()) {
                            val d = t.trim()
                            desc = if (d.length > MAX_DESC) d.substring(0, MAX_DESC).trimEnd() + "…" else d
                        }
                    }
                    "category" -> { val t = readText(parser); if (cat == null && t.isNotBlank()) cat = t.trim() }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")
                        if (icon == null && src != null && src.startsWith("http")) icon = src
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
        }
        val t = title ?: return null
        return Programme(start, stop, t, sub, desc, cat, icon)
    }

    /** Reads text content of the current element and leaves the parser on its END_TAG. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
            if ((ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) && sb.length < 2000) sb.append(parser.text)
        }
        return sb.toString()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    fun maybeGunzip(raw: InputStream): InputStream {
        val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    /** Parses XMLTV time "yyyyMMddHHmmss +hhmm" (seconds / offset optional) into epoch millis. */
    fun parseTime(s: String?): Long? {
        if (s == null) return null
        val t = s.trim()
        if (t.length < 12) return null
        fun num(from: Int, len: Int): Int? {
            if (from + len > t.length) return null
            var v = 0
            for (i in from until from + len) {
                val c = t[i]
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            return v
        }
        val year = num(0, 4) ?: return null
        val month = num(4, 2) ?: return null
        val day = num(6, 2) ?: return null
        val hour = num(8, 2) ?: return null
        val minute = num(10, 2) ?: return null
        val second = num(12, 2) ?: 0
        var offsetMin = 0
        val rest = t.substring(minOf(t.length, 14)).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val digits = rest.substring(1).replace(":", "")
            val oh = digits.take(2).toIntOrNull() ?: 0
            val om = digits.drop(2).take(2).toIntOrNull() ?: 0
            offsetMin = (oh * 60 + om) * if (rest[0] == '-') -1 else 1
        }
        val days = daysFromCivil(year, month, day)
        val secs = days * 86400L + hour * 3600L + minute * 60L + second - offsetMin * 60L
        return secs * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
