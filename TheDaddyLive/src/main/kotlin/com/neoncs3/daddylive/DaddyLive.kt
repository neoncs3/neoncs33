package com.neoncs3.daddylive

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * DaddyLive 24/7 channel catalogue.
 *
 * The site's JSON channels endpoint requires a private API key, so this provider
 * reads the public HTML catalogue instead. It does not attempt to bypass the
 * protected API or any player authentication.
 */
class DaddyLive : MainAPI() {
    override var mainUrl = "https://thedaddy.li"
    override var name = "DaddyLive 24/7"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = true
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )

    companion object {
        private const val PAGE_SIZE = 100
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val channelNames = ConcurrentHashMap<String, String>()
        private val channelIdRegex = Regex("""[?&]id=(\d+)""")
    }

    private suspend fun getChannels(): List<LiveSearchResponse> {
        val document = app.get(
            "$mainUrl/24-7-channels.php",
            headers = requestHeaders
        ).document

        val cards = document.select(
            "div.grid > a, a.card, a[href*='watch.php?id=']"
        )

        val uniqueChannels = linkedMapOf<String, LiveSearchResponse>()
        for (card in cards) {
            val href = card.attr("href").ifBlank {
                card.selectFirst("a[href]")?.attr("href").orEmpty()
            }
            val channelId = channelIdRegex.find(href)?.groupValues?.getOrNull(1)
                ?: continue

            val channelName = card.selectFirst(
                ".card__title, .card-title, .channel-title, .channel-name"
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: card.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.text().trim()

            if (channelName.isBlank()) continue

            val channelUrl = card.absUrl("href").ifBlank {
                "$mainUrl/${href.trimStart('/')}"
            }

            channelNames[channelUrl] = channelName
            uniqueChannels[channelId] = newLiveSearchResponse(
                channelName,
                channelUrl,
                TvType.Live
            )
        }

        return uniqueChannels.values.sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val channels = getChannels()
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val pageChannels = channels.drop(start).take(PAGE_SIZE)

        return newHomePageResponse(
            listOf(HomePageList("24/7 Channels", pageChannels, false)),
            start + pageChannels.size < channels.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getChannels().filter {
            it.name.contains(query.trim(), ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("DaddyLive channel ID not found")

        val title = channelNames[url] ?: runCatching {
            app.get(url, headers = requestHeaders)
                .document
                .selectFirst("h1, h2")
                ?.text()
                ?.substringBefore("(")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Channel $channelId"

        // The site documents six public folders, all using stream-<id>.php.
        val playerTemplate = "$mainUrl/{folder}/stream-$channelId.php"
        return newLiveStreamLoadResponse(title, url, playerTemplate)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerFolders = listOf(
            "stream",   // Player 1
            "cast",     // Player 2
            "watch",    // Player 3
            "plus",     // Player 4
            "casting",  // Player 5
            "player",   // Player 6
            "hub"       // Player 7
        )

        var found = false
        for (folder in playerFolders) {
            val playerUrl = data.replace("{folder}", folder)
            val loaded = runCatching {
                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            found = found || loaded
        }
        return found
    }
}

/**
 * Handles the public DaddyLive player pages. It first looks for direct media
 * URLs, then delegates known embeds to CloudStream extractors, and finally
 * uses WebViewResolver to observe ordinary .m3u8 requests from the player.
 */
class DaddyLivePlayerExtractor : ExtractorApi() {
    override val mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive Player"
    override val requiresReferer = false

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val m3u8Regex = Regex(
            """https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    }

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    private fun absoluteUrl(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        return runCatching { URI(base).resolve(raw).toString() }.getOrNull()
    }

    private suspend fun emitM3u8(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = runCatching {
            val uri = URI(mediaUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)

        callback(
            newExtractorLink(
                source = name,
                name = "DaddyLive",
                url = mediaUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to origin
                )
            }
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(url, headers = headers(referer ?: "$mainUrl/"))
        }.getOrNull() ?: return

        val document = response.document
        val normalizedHtml = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val directUrls = linkedSetOf<String>()
        m3u8Regex.findAll(normalizedHtml).forEach {
            directUrls.add(it.value.trimEnd(';', ')', ']'))
        }

        document.select("video source[src], video[src]").forEach { element ->
            val source = element.absUrl("src").ifBlank { element.attr("src") }
            if (source.contains(".m3u8", ignoreCase = true)) {
                directUrls.add(source)
            }
        }

        val direct = directUrls.firstOrNull()
        if (direct != null) {
            emitM3u8(direct, url, callback)
            return
        }

        val iframeUrls = document.select("iframe[src]")
            .mapNotNull { element ->
                absoluteUrl(element.attr("src"), url)
            }
            .distinct()

        for (iframeUrl in iframeUrls) {
            var emitted = 0
            val delegated = runCatching {
                loadExtractor(iframeUrl, url, subtitleCallback) {
                    callback(it)
                    emitted++
                }
            }.getOrDefault(false)

            if (emitted > 0) return

            // Generic embeds (including dembed player pages) may not have a
            // registered extractor. Observe their normal HLS request instead.
            val sniffedUrl = runCatching {
                app.get(
                    iframeUrl,
                    referer = url,
                    headers = headers(url),
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                        timeout = 30_000L
                    )
                ).url
            }.getOrNull()

            if (!sniffedUrl.isNullOrBlank() &&
                sniffedUrl.contains(".m3u8", ignoreCase = true)
            ) {
                emitM3u8(sniffedUrl, iframeUrl, callback)
                return
            }

            if (delegated) continue
        }
    }
}

