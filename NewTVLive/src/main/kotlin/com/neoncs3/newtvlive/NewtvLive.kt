package com.neoncs3.newtvlive

// ============================================================================
// Kanal listesi: https://newtvproxy.newtane.com/api/channels
//   [{ "channel_id": "31", "channel_name": "...", "logo_url": "..." }, ...]
// Yayın bulma, sitenin kendi sayfaları ve embed'ler üzerinden yapılır; token /
// kimlik doğrulama adımları (playlist-token, newkso.ru) BİLEREK yazılmadı.
// ============================================================================

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

data class NtlChannel(
    @JsonProperty("channel_id") val channelId: String? = null,
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("logo_url") val logoUrl: String? = null
)

class NewTVLive : MainAPI() {
    override var mainUrl = "https://tv.newtane.com"
    override var name = "NewTV Live"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    companion object {
        private const val API = "https://newtvproxy.newtane.com/api"
        private const val PAGE_SIZE = 100
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val idRegex = Regex("""/live/(\d+)""")

        // Yayın adresi kalıpları (dex'te görülenler)
        private val m3u8Quoted = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""")
        private val srcConst = Regex("""const\s+SRC\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
        private val fileKey = Regex("""(?:streamUrl|file|source)\s*:\s*["'](https?:[^"']+\.m3u8[^"']*)["']""")
        private val atobRegex = Regex("""(?:source\s*:\s*)?window\.atob\(["']([^"']+)["']\)""")

        // Kanal id'leri DaddyLive id'leriyle aynı görünüyor; embed yedekleri
        private val EMBEDS = listOf(
            "https://dembed.top/premiumtv/daddy.php?id=",
            "https://dembed.st/premiumtv/daddy.php?id="
        )
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/json,*/*;q=0.8"
    )

    // ------------------------------------------------------------------ katalog
    private val cacheMutex = Mutex()
    private var cache: List<LiveSearchResponse> = emptyList()
    private var cacheTime = 0L

    private fun cacheFresh() =
        cache.isNotEmpty() && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS

    private suspend fun getChannels(): List<LiveSearchResponse> {
        if (cacheFresh()) return cache
        return cacheMutex.withLock {
            if (cacheFresh()) return@withLock cache
            val fresh = fetchChannels()
            if (fresh.isNotEmpty()) {
                cache = fresh
                cacheTime = System.currentTimeMillis()
            }
            if (fresh.isNotEmpty()) fresh else cache
        }
    }

    private suspend fun fetchChannels(): List<LiveSearchResponse> {
        val list = runCatching {
            app.get("$API/channels", headers = headers("$mainUrl/"), timeout = 20)
                .parsedSafe<Array<NtlChannel>>()
        }.getOrNull() ?: return emptyList()

        return list.mapNotNull { ch ->
            val id = ch.channelId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = ch.channelName?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newLiveSearchResponse(title, "$mainUrl/live/$id", TvType.Live) {
                this.posterUrl = ch.logoUrl?.takeIf { it.isNotBlank() }
            }
        }.distinctBy { it.url }.sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val pageChannels = channels.drop(start).take(PAGE_SIZE)
        return newHomePageResponse(
            listOf(HomePageList("Canlı Kanallar", pageChannels, false)),
            start + pageChannels.size < channels.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getChannels().filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        if (idRegex.find(url) == null) throw ErrorLoadingException("Kanal ID bulunamadı")
        val known = runCatching { getChannels().firstOrNull { it.url == url } }.getOrNull()
        return newLiveStreamLoadResponse(known?.name ?: "Kanal", url, url) {
            this.posterUrl = known?.posterUrl
        }
    }

    // ------------------------------------------------------------------ linkler
    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    private fun extractM3u8(raw: String): List<String> {
        val text = raw.replace("\\/", "/").replace("&amp;", "&")
        val out = linkedSetOf<String>()
        m3u8Quoted.findAll(text).forEach { out.add(it.groupValues[1]) }
        srcConst.findAll(text).forEach { out.add(it.groupValues[1]) }
        fileKey.findAll(text).forEach { out.add(it.groupValues[1]) }
        atobRegex.findAll(text).forEach {
            val decoded = runCatching {
                String(android.util.Base64.decode(it.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull()
            if (decoded != null && decoded.contains(".m3u8")) out.add(decoded.trim())
        }
        return out.toList()
    }

    private suspend fun emit(mediaUrl: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(pageUrl) ?: mainUrl
        callback(
            newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Origin" to origin
                )
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idRegex.find(data)?.groupValues?.getOrNull(1) ?: return false
        val pages = listOf(data) + EMBEDS.map { "$it$id" }

        // 1) Sayfalarda ve iframe'lerinde doğrudan yayın adresi ara
        for (pageUrl in pages) {
            val page = runCatching { app.get(pageUrl, headers = headers("$mainUrl/")) }.getOrNull()
                ?: continue
            if (page.code !in 200..299) continue

            extractM3u8(page.text).firstOrNull()?.let {
                emit(it, pageUrl, callback); return true
            }
            val frames = page.document.select("iframe[src]")
                .mapNotNull { runCatching { URI(pageUrl).resolve(it.attr("src")).toString() }.getOrNull() }
                .distinct()
            for (frame in frames) {
                val inner = runCatching { app.get(frame, headers = headers(pageUrl)).text }.getOrNull()
                    ?: continue
                extractM3u8(inner).firstOrNull()?.let {
                    emit(it, frame, callback); return true
                }
            }
        }

        // 2) Son çare: sayfanın kendi oynatıcısını WebView'da çalıştır ve normal
        //    .m3u8 isteğini gözlemle (doğrulamayı oynatıcı kendisi yapar)
        for (pageUrl in pages) {
            val sniffed = runCatching {
                app.get(
                    pageUrl,
                    referer = "$mainUrl/",
                    headers = headers("$mainUrl/"),
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                        timeout = 10_000L
                    )
                ).url
            }.getOrNull()
            if (!sniffed.isNullOrBlank() && sniffed.contains(".m3u8", ignoreCase = true)) {
                emit(sniffed, pageUrl, callback)
                return true
            }
        }
        return false
    }
}
