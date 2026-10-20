package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CanliProvider : MainAPI() {

    override var mainUrl = "https://klubnichka.live"
    override var name = "XXXCanli"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW, TvType.TvSeries)

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

    // Sunucu cevabı Referer + Sec-Fetch-Dest'e göre değiştiriyor (Vary başlığı),
    // bu yüzden iframe isteklerini tarayıcıdaki iframe gibi gönderiyoruz.
    private fun iframeHeaders(referer: String, site: String = "same-origin") =
        headers(referer) + mapOf(
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to site
        )

    // Tek ana kayıt: tüm kanallar bu dizinin 1. sezonundaki bölümlerdir.
    private data class ChannelEntry(
        val title: String,
        val url: String,
        val poster: String?
    )

    private fun Element.toChannelEntry(): ChannelEntry? {
        val link = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.startsWith("javascript", ignoreCase = true) ||
            href.trimEnd('/') == mainUrl.trimEnd('/')
        ) return null

        val title = selectFirst(".channel-card-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: link.attr("title").trim().takeIf { it.isNotBlank() }
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return ChannelEntry(title, href, poster)
    }

    private fun Document.channelEntries(): List<ChannelEntry> {
        val elements = select("a:has(.channel-card-title)").ifEmpty {
            select(".channel-card, [class*=channel-card]")
        }
        return elements.mapNotNull { it.toChannelEntry() }
            .distinctBy { it.url }
    }

    private fun collectionSearchResult(): SearchResponse =
        newTvSeriesSearchResponse("Canlı TV", mainUrl, TvType.TvSeries) {
            posterUrl = "$mainUrl/favicon.ico"
        }

    // ------------------------------------------------------------------
    // Ana sayfa: yalnızca bir adet Canlı TV kaydı gösterilir.
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(
            request.name,
            listOf(collectionSearchResult()),
            hasNext = false
        )
    }

    // ------------------------------------------------------------------
    // Arama: kanal adı eşleşirse de tek Canlı TV koleksiyonunu açar.
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank() || "Canlı TV".contains(q, ignoreCase = true) ||
            "Canlı Kanallar".contains(q, ignoreCase = true)
        ) return listOf(collectionSearchResult())

        val doc = app.get(mainUrl, headers = headers(mainUrl)).document
        return if (doc.channelEntries().any { it.title.contains(q, ignoreCase = true) }) {
            listOf(collectionSearchResult())
        } else {
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // Tek dizi detayında her kanal ayrı bölüm olarak gösterilir.
    // Bölümün data değeri kanal URL'si olduğundan mevcut loadLinks değişmez.
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        if (url.trimEnd('/') == mainUrl.trimEnd('/')) {
            val doc = app.get(mainUrl, headers = headers(mainUrl)).document
            val channels = doc.channelEntries()
            val episodes = channels.mapIndexed { index, channel ->
                newEpisode(channel.url) {
                    name = channel.title
                    season = 1
                    episode = index + 1
                    posterUrl = channel.poster
                }
            }

            return newTvSeriesLoadResponse(
                "Canlı TV",
                mainUrl,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = "$mainUrl/favicon.ico"
                plot = "Tüm canlı kanallar tek listede. Her kanal 1. sezonun bir bölümü olarak gösterilir."
            }
        }

        // Önceden kaydedilmiş doğrudan kanal adresleri için geriye dönük uyumluluk.
        val doc = app.get(url, headers = headers(mainUrl)).document
        val slug = url.trimEnd('/').substringAfterLast("/")

        val title = doc.selectFirst(".channel-title")?.text()
            ?.replace(Regex("""\s*смотреть онлайн.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".channel-player iframe[title]")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')

        val poster = "$mainUrl/images/$slug.png"
        val description = doc.selectFirst("meta[name=description]")?.attr("content")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            listOf(
                newEpisode(url) {
                    name = "Canlı Yayın"
                    season = 1
                    episode = 1
                    posterUrl = poster
                }
            )
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ------------------------------------------------------------------
    // Linkler: kanal sayfası -> iframe -> (iç iframe) -> m3u8
    // ------------------------------------------------------------------
    private val m3u8Abs =
        Regex("""(?i)https?://[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]+)?""")
    private val m3u8Rel =
        Regex("""(?i)(?<!:)//[^\s"'<>\\]+?\.m3u8(?:\?[^\s"'<>\\]+)?""")

    private val atobRegex = Regex("""atob\(\s*["']([A-Za-z0-9+/=]{16,})["']\s*\)""")

    private fun extractM3u8(raw: String): List<String> {
        var text = raw.replace("\\/", "/").replace("&amp;", "&")
        // atob("...") ile gömülmüş içerikleri de çöz ve aynı metne ekle
        atobRegex.findAll(text).forEach {
            runCatching {
                text += "\n" + String(Base64.decode(it.groupValues[1], Base64.DEFAULT))
                    .replace("\\/", "/")
            }
        }
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
                        name = "$name - ${u.substringAfter("://").substringBefore("/")}",
                        url = u,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to referer,
                            "Origin" to mainUrl
                        )
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
                app.get(iframeUrl, headers = iframeHeaders(data))
            }.getOrNull() ?: continue

            emit(extractM3u8(iframeResp.text), iframeUrl)
            if (emitted > 0) return true

            // 3) İç içe iframe / player sayfası
            for (playerUrl in iframeResp.document.iframeUrls(iframeUrl)) {
                val playerResp = runCatching {
                    app.get(playerUrl, headers = iframeHeaders(iframeUrl))
                }.getOrNull() ?: continue

                emit(extractM3u8(playerResp.text), playerUrl)
                if (emitted > 0) return true
            }

            // 4) Son çare: hazır çıkarıcılar
            runCatching {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        if (emitted > 0) return true

        // 5) Yedek: kanal sayfasının kendisini WebView'da aç; iframe doğal şekilde
        //    yüklenir (doğru Referer/Sec-Fetch başlıklarıyla) ve .m3u8 isteği yakalanır.
        val sniffed = runCatching {
            app.get(
                data,
                referer = mainUrl,
                headers = headers(mainUrl),
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    timeout = 25_000L
                )
            ).url
        }.getOrNull()

        if (sniffed != null && sniffed.contains(".m3u8", ignoreCase = true)) {
            emit(listOf(sniffed), data)
        }
        return emitted > 0
    }
}
