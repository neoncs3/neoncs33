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

class AutoDaddyliveApp : NeonMainAPI() {
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
        neonHeaders + mapOf(
            "Referer" to referer,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
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

    // ------------------------------------------------------------------
    // 1. KANAL LİSTESİ (player10.json)
    // ------------------------------------------------------------------
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

        if (body.isBlank()) {
            Log.w(name.uppercase().take(23), "API empty: $apiUrl")
            return emptyList()
        }

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
        }.onFailure {
            Log.e(name.uppercase().take(23), "Error parsing $apiUrl", it)
        }

        Log.d(name.uppercase().take(23), "API parsed -> items=${items.size}")
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
        Log.d(name.uppercase().take(23), "Combined channels total=${combined.size}")
        return combined
    }

    // ------------------------------------------------------------------
    // 2. TVNOW247 EMBED ÇÖZÜMLEYİCİ
    // ------------------------------------------------------------------
    private fun extractLiveStreamFromHtml(html: String): String? {
        val text = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")

        val m3u8s = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            .findAll(text).map { it.value }.toList()

        val live = m3u8s.firstOrNull {
            it.contains("live", true) || it.contains("stream", true) || it.contains("chunk", true)
        }
        if (live != null) return live
        if (m3u8s.isNotEmpty()) return m3u8s.first()

        val patterns = listOf(
            Regex("""(?:src|file|source|stream|url|hls|playlist)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']+\.m3u8[^"']*)["']"""),
        )
        for (p in patterns) {
            p.find(text)?.let { return it.groupValues[1] }
        }

        val b64Pattern = Regex("""["']([A-Za-z0-9+/=]{50,})["']""")
        for (m in b64Pattern.findAll(text)) {
            val decoded = runCatching {
                String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull()
            if (decoded != null && decoded.contains(".m3u8", true)) {
                Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .find(decoded)?.let { return it.value }
            }
        }
        return null
    }

    private suspend fun resolveTvNow247(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name.uppercase().take(23), "tvnow247 çözümleniyor: $embedUrl")

        val embedResponse = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 15_000L,
            )
        }.getOrNull() ?: return false

        val pageHtml = embedResponse.text
        if (pageHtml.isBlank()) return false

        val channelId = sequenceOf(
            Regex("""channelId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""channel_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""streamId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""stream_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""/resolve-dlstream/(\d+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""data-(?:channel|stream)-id=["'](\d+)["']""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(\d+)$""").find(embedUrl)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull() ?: return false

        val src = sequenceOf(
            Regex("""src\s*[:=]\s*["'](plus|tv\d*|dl|main|alt)["']""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""[?&]src=(\w+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            if (embedUrl.contains("-plus-", ignoreCase = true) ||
                embedUrl.contains("/plus-", ignoreCase = true)
            ) "plus" else null,
        ).filterNotNull().firstOrNull() ?: "plus"

        val apiHost = sequenceOf(
            Regex("""["'](https?://chat\.[a-z0-9.-]+)["']""")
                .find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(https?://[a-z0-9.-]*cfbu[a-z0-9.-]*)""")
                .find(pageHtml)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull() ?: "https://chat.cfbu247.sbs"

        Log.d(name.uppercase().take(23), "tvnow247: id=$channelId src=$src apiHost=$apiHost")

        val resolveUrl = "${apiHost.trimEnd('/')}/api/resolve-dlstream/$channelId?src=$src"
        val resolveBody = runCatching {
            app.get(
                resolveUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                    "Accept" to "application/json, text/plain, */*",
                ),
                timeout = 15_000L,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (resolveBody.isBlank()) return false

        val json = runCatching { JSONObject(resolveBody) }.getOrNull() ?: return false

        val m3u8Url = json.optString("m3u8").trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("proxyPlaylistUrl").trim()
                .takeIf { it.isNotBlank() && it != "null" }
        val proxyPlayerUrl = json.optString("proxyPlayerUrl").trim()
            .takeIf { it.isNotBlank() && it != "null" }
        val apiReferer = json.optString("referer").trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "https://dembed.st/premiumtv/daddy.php?id=$channelId"

        var emitted = 0

        // Önce proxyPlayerUrl'den gerçek canlı akışı çıkarmayı dene
        if (!proxyPlayerUrl.isNullOrBlank()) {
            val playerHtml = runCatching {
                app.get(
                    proxyPlayerUrl,
                    headers = mapOf(
                        "Referer" to "https://tvnow247.top/",
                        "User-Agent" to playerUserAgent,
                        "Accept" to "text/html,application/xhtml+xml,*/*",
                    ),
                    timeout = 15_000L,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull().orEmpty()

            if (playerHtml.isNotBlank()) {
                val liveStream = extractLiveStreamFromHtml(playerHtml)
                if (!liveStream.isNullOrBlank()) {
                    Log.d(name.uppercase().take(23), "tvnow247: gerçek akış=$liveStream")
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
                                "Accept" to "*/*",
                            ),
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    emitted++
                }
            }
        }

        // Doğrudan m3u8'i 3 farklı başlık kombinasyonuyla sun
        if (!m3u8Url.isNullOrBlank()) {
            data class Combo(val referer: String, val origin: String?, val label: String)
            val combos = listOf(
                Combo(apiReferer, "https://dembed.st", "Daddylive (api+origin)"),
                Combo(apiReferer, null, "Daddylive (api, no-origin)"),
                Combo("https://tvnow247.top/", "https://tvnow247.top", "Daddylive (tv)"),
            )

            for (c in combos) {
                val h = mutableMapOf(
                    "Referer" to c.referer,
                    "User-Agent" to playerUserAgent,
                    "Accept" to "*/*",
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

        Log.d(name.uppercase().take(23), "tvnow247: emitted=$emitted variants")
        return emitted > 0
    }

    // ------------------------------------------------------------------
    // 3. ZORUNLU METODLAR
    // ------------------------------------------------------------------

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

        val base = request.data
        val document = runCatching {
            app.get(base, headers = headers(base), timeout = 12_000L).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = resultsFrom(document, base)
        return newHomePageResponse(request.name, results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = normalizeUrl(url)
        if (!isValidHttpUrl(normalized)) return null

        val document = if (normalized.contains(".m3u8", ignoreCase = true)) {
            Document("")
        } else {
            runCatching {
                app.get(normalized, headers = headers(normalized), timeout = 12_000L).document
            }.getOrNull() ?: Document("")
        }

        val title = document.title().takeIf { it.isNotBlank() }
            ?: runCatching {
                URI(normalized).path.orEmpty()
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                    .replace(Regex("""[-_]+"""), " ")
                    .trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Canlı Yayın"

        return newLiveStreamLoadResponse(title, normalized, normalized)
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
            if (resolveTvNow247(normalized, callback)) return true
            Log.w(name.uppercase().take(23), "tvnow247 çözümlenemedi")
        }

        if (normalized.contains(".m3u8", ignoreCase = true) || neonIsMediaUrl(normalized)) {
            val host = runCatching {
                URI(normalized).host.orEmpty().lowercase()
            }.getOrDefault("").ifBlank { "daddylive.app" }
            val referer = "https://$host/"

            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = normalized,
                    referer = referer,
                    quality = Qualities.Unknown.value,
                    headers = mapOf(
                        "Referer" to referer,
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
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val urls = listOf(
            mainUrl + "/?s=" + encoded,
            mainUrl + "/?q=" + encoded,
        )
        for (searchUrl in urls) {
            val response = runCatching {
                app.get(searchUrl, headers = headers(searchUrl), timeout = 12_000L)
            }.getOrNull() ?: continue
            val matches = resultsFrom(response.document, searchUrl)
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // 4. YARDIMCI FONKSİYONLAR (URL / HTML)
    // ------------------------------------------------------------------

    private fun normalizeUrl(raw: String, base: String = mainUrl): String =
        runCatching {
            if (raw.startsWith("http", true)) raw
            else {
                val b = URI(base)
                val resolved = b.resolve(raw)
                resolved.toString()
            }
        }.getOrDefault(raw)

    private fun isNonContentAnchor(anchor: Element, baseUrl: String): Boolean {
        val rawHref = anchor.attr("href").trim()
        if (rawHref.startsWith("#")) return true
        val target = runCatching { URI(normalizeUrl(rawHref, baseUrl)) }.getOrNull() ?: return false
        val base = runCatching { URI(normalizeUrl(baseUrl)) }.getOrNull() ?: return false
        return !target.rawFragment.isNullOrBlank() &&
            target.scheme.equals(base.scheme, ignoreCase = true) &&
            target.host.equals(base.host, ignoreCase = true) &&
            target.path.orEmpty().trimEnd('/') == base.path.orEmpty().trimEnd('/') &&
            target.rawQuery.orEmpty() == base.rawQuery.orEmpty()
    }

    private fun isDetailUrl(raw: String, base: String = mainUrl): Boolean {
        val url = normalizeUrl(raw, base)
        if (!url.startsWith("http", true) || siteHost(url) != siteHost(mainUrl)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.isBlank() || path == "/") return false
        if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
                ".css", ".js", ".m3u8", ".mpd", ".mp4", ".webm", ".m4v",
                ".ts", ".m4s").any { path.endsWith(it) }) return false
        return path.trim('/').isNotBlank()
    }

    private fun containerFor(anchor: Element): Element {
        var node: Element? = anchor
        repeat(5) {
            if (node == null) return@repeat
            if (node!!.selectFirst("img") != null) return node!!
            node = node!!.parent()
        }
        return anchor
    }

    private fun posterFor(anchor: Element, baseUrl: String): String? {
        var node: Element? = anchor
        for (depth in 0..4) {
            val current = node ?: break
            if (depth > 0 && current.select("a[href]").any { it !== anchor }) return null
            val image = current.selectFirst("img")
            val src = image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")?.takeIf { it.isNotBlank() }
            if (src != null && !src.startsWith("data:", true)) {
                val normalized = normalizeUrl(src, baseUrl)
                if (normalized.startsWith("http", true)) return normalized
            }
            node = current.parent()
        }
        return null
    }

    private fun candidateAnchors(document: Document): List<Element> {
        val mediaSelector =
            "a[href]:has(img), a[href][data-poster], a[href][data-src], a[href][data-lazy-src]"
        val anchors = ArrayList<Element>()
        val seen = LinkedHashSet<String>()
        for (anchor in document.select(mediaSelector)) {
            if (anchors.size >= 200) break
            if (isNonContentAnchor(anchor, mainUrl)) continue
            if (posterFor(anchor, mainUrl) == null) continue
            val href = normalizeUrl(anchor.attr("href"), mainUrl)
            if (seen.add(href)) anchors.add(anchor)
        }
        return anchors
    }

    private fun resultsFrom(document: Document, baseUrl: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        for (anchor in candidateAnchors(document)) {
            if (results.size >= 120) break
            val href = normalizeUrl(anchor.attr("href"), baseUrl)
            if (!isDetailUrl(href, baseUrl)) continue
            val container = containerFor(anchor)
            val image = anchor.selectFirst("img") ?: container.selectFirst("img")
            val title = sequenceOf(
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.selectFirst("h1, h2, h3, h4, .title")?.text(),
                image?.attr("alt"),
                anchor.text(),
            ).mapNotNull { it?.trim() }
                .firstOrNull { it.length in 2..160 } ?: continue

            val poster = posterFor(anchor, baseUrl) ?: continue
            val series = Regex("""(?i)(dizi|series|season|sezon)""")
                .containsMatchIn(title + " " + href)

            val item = if (series) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                }
            }
            if (!results.containsKey(item.url)) results[item.url] = item
        }
        return results.values.toList()
    }
}
