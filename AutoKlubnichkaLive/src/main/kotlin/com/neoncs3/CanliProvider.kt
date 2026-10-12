package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CanliProvider : MainAPI() {

    override var mainUrl = "https://klubnichka.live"
    override var name = "XXXCanli"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Canlı Kanallar"
    )

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun headers(referer: String) = mapOf(
        "User-Agent" to ua,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    // ------------------------------------------------------------------
    // Kart -> SearchResponse
    // ------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.startsWith("javascript") || href == "$mainUrl/" || href == mainUrl) return null

        val title = (selectFirst(".channel-card-title")?.text()
            ?: link.attr("title")
            ?: link.text()).trim()
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Document.cards(): List<SearchResponse> {
        // Önce bilinen sınıf, yoksa genel yedek
        val els = select("a:has(.channel-card-title)").ifEmpty {
            select(".channel-card, [class*=channel-card]")
        }
        return els.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Ana sayfa
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = headers(mainUrl)).document
        return newHomePageResponse(request.name, doc.cards(), hasNext = false)
    }

    // ------------------------------------------------------------------
    // Arama (site araması bilinmiyor; ana sayfa listesini süzer)
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(mainUrl, headers = headers(mainUrl)).document
        val q = query.trim().lowercase()
        return doc.cards().filter { it.name.lowercase().contains(q) }
    }

    // ------------------------------------------------------------------
    // Detay
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers(mainUrl)).document
        val slug = url.trimEnd('/').substringAfterLast("/")

        // Sayfada og:title yok; <h1 class="channel-title"> "X смотреть онлайн" biçiminde
        val title = doc.selectFirst(".channel-title")?.text()
            ?.replace(Regex("""\s*смотреть онлайн.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".channel-player iframe[title]")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')

        // Posterler /images/<slug>.png düzeninde (logo.png ile karışmasın diye slug'dan üretiyoruz)
        val poster = "$mainUrl/images/$slug.png"

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    // ------------------------------------------------------------------
    // Linkler: kanal sayfası -> iframe -> (iç iframe) -> m3u8
    // ------------------------------------------------------------------
    private val m3u8Abs =
        Regex("""(?i)https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]+)?""")
    private val m3u8Rel =
        Regex("""(?i)(?<!:)//[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]+)?""")

    private fun extractM3u8(raw: String): List<String> {
        val text = raw.replace("\\/", "/").replace("&amp;", "&")
        val found = LinkedHashSet<String>()
        m3u8Abs.findAll(text).forEach { found.add(it.value) }
        m3u8Rel.findAll(text).forEach { found.add("https:" + it.value) }
        return found.toList()
    }

    private fun Document.iframeUrls(base: String): List<String> =
        select("iframe[src]").mapNotNull {
            val src = it.attr("src").trim()
            when {
                src.isBlank() || src.startsWith("about:") -> null
                src.startsWith("//") -> "https:$src"
                src.startsWith("http") -> src
                src.startsWith("/") -> base.substringBefore("://") + "://" +
                    base.substringAfter("://").substringBefore("/") + src
                else -> null
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = 0

        suspend fun emit(urls: List<String>, referer: String) {
            urls.distinct().forEachIndexed { i, u ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = if (i == 0) name else "$name Yedek $i",
                        url = u,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to ua, "Referer" to referer)
                    }
                )
                emitted++
            }
        }

        // 1) Kanal sayfasında doğrudan m3u8 var mı?
        val channelResp = runCatching { app.get(data, headers = headers(mainUrl)) }.getOrNull()
            ?: return false
        emit(extractM3u8(channelResp.text), data)
        if (emitted > 0) return true

        // 2) Kanal sayfasındaki iframe'ler
        for (iframeUrl in channelResp.document.iframeUrls(data)) {
            val iframeResp = runCatching {
                app.get(iframeUrl, headers = headers(data))
            }.getOrNull() ?: continue

            emit(extractM3u8(iframeResp.text), iframeUrl)
            if (emitted > 0) return true

            // 3) İç içe iframe / player sayfası
            for (playerUrl in iframeResp.document.iframeUrls(iframeUrl)) {
                val playerResp = runCatching {
                    app.get(playerUrl, headers = headers(iframeUrl))
                }.getOrNull() ?: continue

                emit(extractM3u8(playerResp.text), playerUrl)
                if (emitted > 0) return true
            }

            // 4) Son çare: hazır çıkarıcılar
            runCatching {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return emitted > 0
    }
}
