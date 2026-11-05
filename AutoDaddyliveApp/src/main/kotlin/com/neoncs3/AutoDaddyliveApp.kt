package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AutoDaddyliveApp : MainAPI() {
    override var mainUrl = "https://daddylive.app"
    override var name = "Daddylive"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val channelApiUrls = listOf(
        "https://daddylive.app/player/player10.json",
    )

    private val channelsPerPage = 50

    private val playerUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "https://daddylive.app/" to "Ana Sayfa"
    )

    private fun headers(referer: String = mainUrl + "/"): Map<String, String> =
        mapOf(
            "Referer" to referer,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "User-Agent" to playerUserAgent,
        )

    private fun siteHost(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    private fun isValidHttpUrl(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)

    private suspend fun parseChannelsFromUrl(apiUrl: String): List<SearchResponse> {
        val body = runCatching {
            app.get(
                apiUrl,
                headers = headers(apiUrl) + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 20_000L,
                allowRedirects = true,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (body.isBlank()) return emptyList()

        val items = ArrayList<SearchResponse>()
        runCatching {
            val trimmed = body.trim()
            val jsonArray: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data")
                        ?: obj.optJSONArray("channels")
                        ?: obj.optJSONArray("list")
                        ?: obj.optJSONArray("items")
                        ?: JSONArray()
                }
                else -> JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val channelName = sequenceOf(
                    obj.optString("name"),
                    obj.optString("channel_name"),
                    obj.optString("title"),
                ).map { it.trim() }
                    .firstOrNull { it.isNotBlank() } ?: continue

                val streamUrl = sequenceOf(
                    obj.optString("url"),
                    obj.optString("stream_url"),
                    obj.optString("link"),
                ).map { it.trim() }
                    .firstOrNull { it.isNotBlank() } ?: continue

                if (!isValidHttpUrl(streamUrl)) continue

                items += newLiveSearchResponse(channelName, streamUrl, TvType.Live) {
                    posterUrl = mainUrl.trimEnd('/') + "/favicon.ico"
                }
            }
        }

        return items
    }

    private suspend fun loadChannelsFromApis(): List<SearchResponse> {
        val combined = ArrayList<SearchResponse>()
        val seenKeys = HashSet<String>()
        for (apiUrl in channelApiUrls) {
            for (item in parseChannelsFromUrl(apiUrl)) {
                val key = item.name.trim().lowercase() + "|" + item.url.trim()
                if (seenKeys.add(key)) {
                    combined += item
                }
            }
        }
        return combined
    }

    // ------------------------------------------------------------------
    // DAHA AGRESİF CANLI AKIŞ ÇIKARICI
    // ------------------------------------------------------------------
    private fun extractLiveStreamFromHtml(html: String): String? {
        val text = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")

        // 1. Tüm .m3u8 URL'lerini topla
        val m3u8s = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
            .findAll(text).map { it.value }.toList()

        Log.d("DADDYLIVE", "extractLive: bulunan m3u8=${m3u8s.size}")
        m3u8s.forEach { Log.d("DADDYLIVE", "  -> $it") }

        // 2. Doğrudan canlı akış gibi görünenleri tercih et
        val liveKeywords = listOf("live", "stream", "play", "chunk", "edge", "cdn", "hls")
        for (kw in liveKeywords) {
            val match = m3u8s.firstOrNull { it.contains(kw, true) }
            if (match != null) {
                Log.d("DADDYLIVE", "extractLive: keyword '$kw' ile eşleşti")
                return match
            }
        }

        // 3. İlk .m3u8
        if (m3u8s.isNotEmpty()) {
            Log.d("DADDYLIVE", "extractLive: ilk m3u8 kullanıldı")
            return m3u8s.first()
        }

        // 4. iframe src
        val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        iframeRegex.find(text)?.let { m ->
            val iframe = m.groupValues[1]
            Log.d("DADDYLIVE", "extractLive: iframe=$iframe")
            if (iframe.startsWith("http", true)) return iframe
        }

        // 5. video src
        val videoRegex = Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        videoRegex.find(text)?.let { m ->
            val video = m.groupValues[1]
            Log.d("DADDYLIVE", "extractLive: video=$video")
            if (video.startsWith("http", true)) return video
        }

        // 6. JS değişkenleri
        val jsPatterns = listOf(
            Regex("""(?:src|file|source|stream|url|hls|playlist|video)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )
        for (p in jsPatterns) {
            for (m in p.findAll(text)) {
                val v = m.groupValues[1]
                if (v.startsWith("http", true) &&
                    (v.contains(".m3u8", true) || v.contains("stream", true) || v.contains("live", true))) {
                    Log.d("DADDYLIVE", "extractLive: js var=$v")
                    return v
                }
            }
        }

        return null
    }

    // ------------------------------------------------------------------
    // proxyPlayerUrl'yi birden fazla yolla dene
    // ------------------------------------------------------------------
    private suspend fun resolveTvNow247(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("DADDYLIVE", "=== resolveTvNow247 START: $embedUrl ===")

        val embedResponse = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 15_000L,
            )
        }.getOrNull() ?: run {
            Log.d("DADDYLIVE", "Embed sayfası alınamadı")
            return false
        }

        val pageHtml = embedResponse.text
        if (pageHtml.isBlank()) return false

        val channelId = sequenceOf(
            Regex("""channelId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""channel_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""/resolve-dlstream/(\d+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(\d+)$""").find(embedUrl)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull() ?: run {
            Log.d("DADDYLIVE", "channelId bulunamadı")
            return false
        }

        val src = if (embedUrl.contains("-plus-", true)) "plus" else "plus"

        val apiHost = "https://chat.cfbu247.sbs"

        Log.d("DADDYLIVE", "channelId=$channelId src=$src")

        val resolveUrl = "$apiHost/api/resolve-dlstream/$channelId?src=$src"
        val resolveBody = runCatching {
            app.get(
                resolveUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                    "Accept" to "application/json",
                ),
                timeout = 15_000L,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (resolveBody.isBlank()) {
            Log.d("DADDYLIVE", "resolve-dlstream boş")
            return false
        }

        Log.d("DADDYLIVE", "resolveBody=${resolveBody.take(400)}")

        val json = runCatching { JSONObject(resolveBody) }.getOrNull() ?: return false

        val m3u8Url = json.optString("m3u8").trim().takeIf { it.isNotBlank() && it != "null" }
        val proxyPlayerUrl = json.optString("proxyPlayerUrl").trim().takeIf { it.isNotBlank() && it != "null" }
        val apiReferer = json.optString("referer").trim().takeIf { it.isNotBlank() && it != "null" }
            ?: "https://dembed.st/premiumtv/daddy.php?id=$channelId"

        Log.d("DADDYLIVE", "m3u8=$m3u8Url")
        Log.d("DADDYLIVE", "proxyPlayer=$proxyPlayerUrl")

        var emitted = 0

        // proxyPlayerUrl'yi dene
        if (!proxyPlayerUrl.isNullOrBlank()) {
            val playerHtml = runCatching {
                app.get(
                    proxyPlayerUrl,
                    headers = mapOf(
                        "Referer" to "https://tvnow247.top/",
                        "User-Agent" to playerUserAgent,
                        "Accept" to "text/html,*/*",
                    ),
                    timeout = 15_000L,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull().orEmpty()

            Log.d("DADDYLIVE", "playerHtml uzunluk=${playerHtml.length}")

            if (playerHtml.isNotBlank()) {
                val liveStream = extractLiveStreamFromHtml(playerHtml)
                if (!liveStream.isNullOrBlank()) {
                    Log.d("DADDYLIVE", "✓ GERÇEK AKIŞ BULUNDU: $liveStream")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "Daddylive Live",
                            url = liveStream,
                            referer = apiReferer,
                            quality = Qualities.Unknown.value,
                            headers = mapOf(
                                "Referer" to apiReferer,
                                "Origin" to "https://dembed.st",
                                "User-Agent" to playerUserAgent,
                            ),
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    emitted++
                } else {
                    Log.d("DADDYLIVE", "✗ playerHtml'de akış yok")
                }
            }
        }

        // Fallback: m3u8 - 3 başlık kombinasyonu
        if (!m3u8Url.isNullOrBlank()) {
            data class Combo(val referer: String, val origin: String?, val label: String)
            val combos = listOf(
                Combo(apiReferer, "https://dembed.st", "Daddylive (api+origin)"),
                Combo(apiReferer, null, "Daddylive (no-origin)"),
                Combo("https://tvnow247.top/", "https://tvnow247.top", "Daddylive (tv)"),
            )

            for (c in combos) {
                val h = mutableMapOf(
                    "Referer" to c.referer,
                    "User-Agent" to playerUserAgent,
                )
                if (c.origin != null) h["Origin"] = c.origin

                callback(
                    ExtractorLink(
                        source = name,
                        name = c.label,
                        url = m3u8Url,
                        referer = c.referer,
                        quality = Qualities.Unknown.value,
                        headers = h,
                        type = ExtractorLinkType.M3U8,
                    )
                )
                emitted++
            }
        }

        Log.d("DADDYLIVE", "=== resolveTvNow247 END: emitted=$emitted ===")
        return emitted > 0
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page <= 1) {
            val liveItems = loadChannelsFromApis()
            if (liveItems.isNotEmpty()) {
                val pages = liveItems.chunked(channelsPerPage).mapIndexed { index, chunk ->
                    HomePageList("Canlı Kanallar ${index + 1}", chunk)
                }
                return newHomePageResponse(pages, hasNext = false)
            }
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = url.trim()
        if (!isValidHttpUrl(normalized)) return null
        return newLiveStreamLoadResponse("Canlı Yayın", normalized, normalized)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = data.trim()
        if (normalized.isBlank()) return false

        if (normalized.contains("tvnow247.top/embed/", ignoreCase = true)) {
            return resolveTvNow247(normalized, callback)
        }

        if (normalized.contains(".m3u8", true) || normalized.contains(".mp4", true)) {
            val host = runCatching { URI(normalized).host.orEmpty() }.getOrDefault("")
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = normalized,
                    referer = "https://$host/",
                    quality = Qualities.Unknown.value,
                    headers = mapOf(
                        "Referer" to "https://$host/",
                        "User-Agent" to playerUserAgent,
                    ),
                    type = ExtractorLinkType.M3U8,
                )
            )
            return true
        }

        return false
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }
}
