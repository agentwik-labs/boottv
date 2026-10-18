package dev.boottv.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight host-based ad/tracker blocker. A request is blocked when its host, or any parent
 * domain of it, is in [BLOCKED_DOMAINS] (so "securepubads.g.doubleclick.net" matches "doubleclick.net").
 * Called from WebViewClient.shouldInterceptRequest on a background thread, so state is thread-safe.
 */
object AdBlocker {
    @Volatile var enabled: Boolean = true

    /** Requests blocked since the counter was last reset (per page load). */
    val blockedCount = AtomicInteger(0)

    fun isBlocked(host: String?): Boolean {
        if (!enabled || host.isNullOrEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in BLOCKED_DOMAINS) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            // Stop at the TLD: a bare "com"/"net" is never in the list anyway.
            if (h.indexOf('.') < 0) return false
        }
    }

    /** An empty response handed back to the WebView instead of the real ad/tracker resource. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 204, "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private val BLOCKED_DOMAINS: Set<String> = hashSetOf(
        // Google ad stack
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "googletagservices.com", "google-analytics.com",
        "googletagmanager.com", "imasdk.googleapis.com", "2mdn.net", "admob.com",
        "app-measurement.com", "adsense.com",
        // Amazon / Microsoft / Meta / Twitter / TikTok / Snap / LinkedIn / Pinterest ads & pixels
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "assoc-amazon.com", "bat.bing.com",
        "ads.microsoft.com", "clarity.ms", "ads.linkedin.com", "px.ads.linkedin.com", "analytics.twitter.com",
        "ads-twitter.com", "ads-api.twitter.com", "static.ads-twitter.com", "an.facebook.com",
        "connect.facebook.net", "pixel.facebook.com", "analytics.tiktok.com", "ads.tiktok.com",
        "tr.snapchat.com", "sc-static.net", "ct.pinterest.com", "ads.pinterest.com", "ads.yahoo.com",
        "analytics.yahoo.com", "advertising.com", "adtech.de", "ads.reddit.com", "events.redditmedia.com",
        // Native / content-recommendation networks
        "taboola.com", "taboolasyndication.com", "outbrain.com", "outbrainimg.com", "revcontent.com",
        "mgid.com", "zergnet.com", "content.ad", "nativo.com", "sharethrough.com", "triplelift.com",
        "3lift.com", "yieldmo.com", "teads.tv", "teads.com", "plista.com", "ligatus.com",
        // Exchanges, SSPs, DSPs
        "criteo.com", "criteo.net", "adnxs.com", "adnxs-simple.com", "rubiconproject.com",
        "pubmatic.com", "openx.net", "openx.com", "casalemedia.com", "indexexchange.com",
        "contextweb.com", "sovrn.com", "lijit.com", "smartadserver.com", "adform.net", "adform.com",
        "spotxchange.com", "spotx.tv", "springserve.com", "freewheel.tv", "fwmrm.net", "stickyadstv.com",
        "adsrvr.org", "bidswitch.net", "mathtag.com", "media.net", "turn.com", "rlcdn.com",
        "yieldlab.net", "districtm.io", "gumgum.com", "33across.com", "undertone.com", "sonobi.com",
        "emxdgt.com", "adsymptotic.com", "smaato.net", "inmobi.com", "applovin.com", "unityads.unity3d.com",
        "vungle.com", "chartboost.com", "adcolony.com", "mopub.com", "ironsrc.com", "supersonicads.com",
        "adroll.com", "rtbhouse.com", "quantserve.com", "quantcount.com", "zemanta.com", "lkqd.net",
        "tremorhub.com", "videohub.tv", "connatix.com", "primis.tech", "vidoomy.com", "aniview.com",
        "innovid.com", "flashtalking.com", "serving-sys.com", "sizmek.com", "eyereturn.com",
        "adition.com", "improvedigital.com", "360yield.com", "smartclip.net", "yieldmanager.com",
        "zedo.com", "propellerads.com", "popads.net", "popcash.net", "adcash.com", "exoclick.com",
        "juicyads.com", "trafficjunky.net", "hilltopads.net", "adsterra.com", "admaven.com",
        "onclickads.net", "adskeeper.co.uk", "bidvertiser.com", "infolinks.com", "kargo.com",
        "adhigh.net", "adkernel.com", "adbutler.com", "buysellads.com", "carbonads.com",
        // Pop-up / pop-under / push-notification ad networks
        "clickadu.com", "galaksion.com", "richads.com", "monetag.com", "popmyads.com", "trafficstars.com",
        "evadav.com", "rollerads.com", "a-ads.com", "adsco.re", "mondiad.com", "pushground.com",
        // Analytics, session replay, measurement, data brokers
        "scorecardresearch.com", "comscore.com", "imrworldwide.com", "nielsen.com", "chartbeat.com",
        "chartbeat.net", "hotjar.com", "hotjar.io", "mouseflow.com", "fullstory.com", "crazyegg.com",
        "luckyorange.com", "inspectlet.com", "mixpanel.com", "segment.io", "segment.com",
        "amplitude.com", "heap.io", "heapanalytics.com", "kissmetrics.com", "newrelic.com",
        "nr-data.net", "omtrdc.net", "demdex.net", "everesttech.net", "2o7.net", "krxd.net",
        "bluekai.com", "exelator.com", "agkn.com", "tapad.com", "crwdcntrl.net", "adsafeprotected.com",
        "doubleverify.com", "moatads.com", "moatpixel.com", "branch.io", "app.link", "adjust.com",
        "appsflyer.com", "kochava.com", "liftoff.io", "statcounter.com", "histats.com",
        "mc.yandex.ru", "adfox.ru", "parsely.com", "parse.ly", "permutive.com", "permutive.app",
        "onesignal.com", "pushwoosh.com", "pushcrew.com", "sumo.com",
        "addthis.com", "sharethis.com", "lotame.com", "id5-sync.com", "liveramp.com", "pippio.com",
        "33across.net", "zeotap.com", "eyeota.net", "intentiq.com", "openwebmp.com", "cxense.com",
    )
}
