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
                headers = headers(apiUrl) + mapOf("Accept" to "application/json"),
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
                        ?: JSONArray()
                }
                else -> JSONArray()
            }
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim().ifBlank {
                    obj.optString("channel_name").trim()
                }.ifBlank { continue }
                val url = obj.optString("url").trim().ifBlank { continue }
                if (!isValidHttpUrl(url)) continue
                items += newLiveSearchResponse(name, url, TvType.Live) {
                    posterUrl = mainUrl.trimEnd('/') + "/favicon.ico"
                }
            }
        }
        return items
    }

    private suspend fun loadChannelsFromApis(): List<SearchResponse> {
        val combined = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        for (apiUrl in channelApiUrls) {
            for (item in parseChannelsFromUrl(apiUrl)) {
                if (seen.add(item.name.lowercase() + "|" + item.url)) combined += item
            }
        }
        return combined
    }

    // TÜM .m3u8 URL'lerini çıkarır
    private fun findAllM3u8(html: String): List<String> {
        val text = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")

        val results = LinkedHashSet<String>()
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
            .findAll(text).forEach { results.add(it.value) }
        Regex("""["']([^"']+\.m3u8[^"']*)["']""")
            .findAll(text).forEach { m ->
                val v = m.groupValues[1]
                if (v.startsWith("http", true)) results.add(v)
            }
        return results.toList()
    }

    // TÜM iframe src'lerini çıkarır
    private fun findAllIframes(html: String): List<String> {
        return Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
    }

    private suspend fun resolveTvNow247(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("DADDYLIVE", "START: $embedUrl")

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
            Log.d("DADDYLIVE", "Embed alınamadı")
            return false
        }

        val pageHtml = embedResponse.text
        if (pageHtml.isBlank()) return false

        val channelId = sequenceOf(
            Regex("""channelId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""channel_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""/resolve-dlstream/(\d+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(\d+)$""").find(embedUrl)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull() ?: return false

        val resolveUrl = "https://chat.cfbu247.sbs/api/resolve-dlstream/$channelId?src=plus"
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

        Log.d("DADDYLIVE", "channelId=$channelId resolveLen=${resolveBody.length}")
        if (resolveBody.isBlank()) return false

        val json = runCatching { JSONObject(resolveBody) }.getOrNull() ?: return false

        val m3u8Url = json.optString("m3u8").trim().takeIf { it.isNotBlank() && it != "null" }
        val proxyPlayerUrl = json.optString("proxyPlayerUrl").trim().takeIf { it.isNotBlank() && it != "null" }
        val apiReferer = json.optString("referer").trim().takeIf { it.isNotBlank() && it != "null" }
            ?: "https://dembed.st/premiumtv/daddy.php?id=$channelId"

        Log.d("DADDYLIVE", "m3u8=${m3u8Url?.take(120)}")
        Log.d("DADDYLIVE", "proxyPlayer=${proxyPlayerUrl?.take(120)}")

        var emitted = 0
        val triedUrls = mutableSetOf<String>()

        // 1) proxyPlayerUrl'yi aç
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

            // playerHtml içindeki TÜM m3u8'leri al
            val m3u8s = findAllM3u8(playerHtml)
            Log.d("DADDYLIVE", "playerHtml'de ${m3u8s.size} m3u8 bulundu")
            m3u8s.forEach { Log.d("DADDYLIVE", "  m3u8 -> ${it.take(150)}") }

            // Canlı akış tercih et
            val liveStream = m3u8s.firstOrNull {
                it.contains("live", true) || it.contains("stream", true) || it.contains("chunk", true)
            } ?: m3u8s.firstOrNull()

            if (liveStream != null && triedUrls.add(liveStream)) {
                Log.d("DADDYLIVE", "✓ EMIT (live): ${liveStream.take(150)}")
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
            }

            // 2) iframe'leri takip et (ikinci seviye)
            val iframes = findAllIframes(playerHtml)
            Log.d("DADDYLIVE", "playerHtml'de ${iframes.size} iframe bulundu")
            for (iframe in iframes.take(2)) {
                val resolved = if (iframe.startsWith("http", true)) iframe
                    else runCatching { URI(proxyPlayerUrl).resolve(iframe).toString() }.getOrNull() ?: continue
                Log.d("DADDYLIVE", "iframe -> ${resolved.take(120)}")

                val innerHtml = runCatching {
                    app.get(
                        resolved,
                        headers = mapOf(
                            "Referer" to "https://tvnow247.top/",
                            "User-Agent" to playerUserAgent,
                        ),
                        timeout = 15_000L,
                    ).takeIf { it.isSuccessful }?.text
                }.getOrNull().orEmpty()

                if (innerHtml.isNotBlank()) {
                    val innerM3u8s = findAllM3u8(innerHtml)
                    Log.d("DADDYLIVE", "iframe içinde ${innerM3u8s.size} m3u8")
                    val innerLive = innerM3u8s.firstOrNull {
                        it.contains("live", true) || it.contains("stream", true)
                    } ?: innerM3u8s.firstOrNull()

                    if (innerLive != null && triedUrls.add(innerLive)) {
                        Log.d("DADDYLIVE", "✓ EMIT (iframe): ${innerLive.take(150)}")
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "Daddylive (iframe)",
                                url = innerLive,
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
                    }
                }
            }
        }

        // 3) Doğrudan m3u8'i dene (son çare)
        if (!m3u8Url.isNullOrBlank() && triedUrls.add(m3u8Url)) {
            Log.d("DADDYLIVE", "EMIT (fallback): ${m3u8Url.take(150)}")
            callback(
                ExtractorLink(
                    source = name,
                    name = "Daddylive (fallback)",
                    url = m3u8Url,
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
        }

        Log.d("DADDYLIVE", "END: emitted=$emitted")
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
        if (!isValidHttpUrl(url)) return null
        return newLiveStreamLoadResponse("Canlı Yayın", url, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = data.trim()
        if (normalized.isBlank()) return false

        if (normalized.contains("tvnow247.top/embed/", true)) {
            return resolveTvNow247(normalized, callback)
        }

        if (normalized.contains(".m3u8", true)) {
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

    override suspend fun search(query: String): List<SearchResponse> = emptyList()
}
