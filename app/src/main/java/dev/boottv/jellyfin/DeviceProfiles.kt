package dev.boottv.jellyfin

import android.media.MediaCodecList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Builds a Jellyfin DeviceProfile from the codecs this device can actually decode. */
internal object DeviceProfiles {
    const val MAX_BITRATE = 120_000_000

    private val decoderMimes: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.map(String::lowercase) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun has(mime: String) = mime in decoderMimes

    val videoCodecs: List<String> by lazy {
        buildList {
            add("h264")
            if (has("video/hevc")) add("hevc")
            if (has("video/x-vnd.on2.vp9")) add("vp9")
            if (has("video/x-vnd.on2.vp8")) add("vp8")
            if (has("video/av01")) add("av1")
            if (has("video/mpeg2")) add("mpeg2video")
        }
    }

    val audioCodecs: List<String> by lazy {
        buildList {
            addAll(listOf("aac", "mp3", "mp2", "opus", "flac", "vorbis", "alac", "pcm_s16le", "pcm_s24le"))
            // AC3/EAC3 are decoded on most Google TV devices or passed through over HDMI.
            add("ac3"); add("eac3")
            if (has("audio/vnd.dts") || has("audio/vnd.dts.hd")) add("dts")
            if (has("audio/true-hd")) add("truehd")
        }
    }

    fun build(): JsonObject {
        val video = videoCodecs.joinToString(",")
        val audio = audioCodecs.joinToString(",")
        return buildJsonObject {
            put("Name", "BootTV")
            put("MaxStreamingBitrate", MAX_BITRATE)
            put("MaxStaticBitrate", MAX_BITRATE)
            put("MusicStreamingTranscodingBitrate", 384_000)
            putJsonArray("DirectPlayProfiles") {
                addJsonObject {
                    put("Container", "mp4,m4v,mkv,webm,mov,ts,mpegts,m2ts")
                    put("Type", "Video")
                    put("VideoCodec", video)
                    put("AudioCodec", audio)
                }
                addJsonObject {
                    put("Container", "mp3,aac,m4a,flac,ogg,opus,wav,webma")
                    put("Type", "Audio")
                }
            }
            putJsonArray("TranscodingProfiles") {
                addJsonObject {
                    put("Container", "ts")
                    put("Type", "Video")
                    put("VideoCodec", if ("hevc" in videoCodecs) "h264,hevc" else "h264")
                    put("AudioCodec", "aac,ac3,eac3,mp3")
                    put("Context", "Streaming")
                    put("Protocol", "hls")
                    put("MaxAudioChannels", "6")
                    put("MinSegments", 1)
                    put("BreakOnNonKeyFrames", true)
                }
                addJsonObject {
                    put("Container", "aac")
                    put("Type", "Audio")
                    put("AudioCodec", "aac")
                    put("Context", "Streaming")
                    put("Protocol", "http")
                    put("MaxAudioChannels", "2")
                }
            }
            put("ContainerProfiles", JsonArray(emptyList()))
            put("CodecProfiles", JsonArray(emptyList()))
            put("SubtitleProfiles", buildJsonArray {
                for (f in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")) {
                    addJsonObject { put("Format", f); put("Method", "External") }
                }
                for (f in listOf("pgs", "pgssub", "dvdsub", "dvbsub", "sub", "idx")) {
                    addJsonObject { put("Format", f); put("Method", "Encode") }
                }
            })
        }
    }
}
