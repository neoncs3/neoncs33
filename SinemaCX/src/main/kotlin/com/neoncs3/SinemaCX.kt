// Site yapısı ve player akışı için mevcut açık kaynak SinemaCX sağlayıcısından yararlanılmıştır.

package com.neoncs3

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty

private const val SCX_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch = true
    private val SCX_TAG = "SinemaCX"
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
	/*
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye
	*/

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Son Eklenen Filmler",
        "$mainUrl/tur/aile-filmleri/" to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri/" to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmleri/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel/" to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/biyografi/" to "Biyografi Filmleri",
        "$mainUrl/tur/dram-filmleri/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-filmler/" to "Fantastik Filmler",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim Filmleri",
        "$mainUrl/tur/gizem-filmleri/" to "Gizem Filmleri",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmleri/" to "Korku Filmleri",
        "$mainUrl/tur/macera-filmleri/" to "Macera Filmleri",
        "$mainUrl/tur/muzikal-filmleri/" to "Müzikal Filmleri",
        "$mainUrl/tur/romantik-filmleri/" to "Romantik Filmleri",
        "$mainUrl/tur/savas-filmleri/" to "Savaş Filmleri",
        "$mainUrl/tur/spor-filmleri/" to "Spor Filmleri",
        "$mainUrl/tur/suc-filmleri/" to "Suç Filmleri",
        "$mainUrl/tur/tarihi-filmler/" to "Tarih Filmleri",
        "$mainUrl/tur/western-filmleri/" to "Western Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data == "$mainUrl/page/" && page <= 1 -> "$mainUrl/"
            page == 1 -> request.data
            else -> request.data.trimEnd('/') + "/page/" + page + "/"
        }

        Log.d(SCX_TAG, "Ana sayfa: " + url)

        val document = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to SCX_UA),
                referer = "$mainUrl/",
                allowRedirects = true
            ).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = document.select("a[href*='/film/']")
            .mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun Element.posterFromAnchor(): String? {
        fun imageUrl(img: Element): String? {
            val values = listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("data-wpfc-original-src"),
                img.attr("data-poster"),
                img.attr("data-cover"),
                img.attr("src")
            )

            return values.firstOrNull { value ->
                !value.isNullOrBlank() && !value.startsWith("data:image", true)
            }?.let(::fixUrlNull)
        }

        selectFirst("img")?.let { imageUrl(it) }?.let { return it }

        closest(".film_kutusu, .frag-k, .film-k, article, li, div")?.selectFirst("img")?.let {
            imageUrl(it)
        }?.let { return it }

        return null
    }

    private fun Element.toSearchResultFromAnchor(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/film/", true)) return null

        val img = selectFirst("img")

        val title = attr("title").ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: text().trim().ifBlank { null }
            ?: closest(".film_kutusu, .frag-k, .film-k, article, li, div")
                ?.selectFirst(".film_adi, .film-title, .card-title, .baslik, h2, h3")
                ?.text()
                ?.trim()
                ?.ifBlank { null }
            ?: return null

        val poster = posterFromAnchor()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val cleanTitle = title
            .replace(Regex("""\s*\((19\d{2}|20\d{2})\)"""), "")
            .replace(Regex("""(?i)\s*(Türkçe Dublaj|Türkçe Altyazı|Film Posteri|İzle)\s*$"""), "")
            .trim()

        val card = closest(".film_kutusu, .frag-k, .film-k, article, li, div")
        val scoreText = sequenceOf(
            selectFirst(".imdb")?.text(),
            selectFirst(".rating")?.text(),
            card?.selectFirst(".imdb")?.text(),
            card?.selectFirst(".rating")?.text()
        ).firstOrNull { !it.isNullOrBlank() }

        return newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
            posterUrl = poster
            this.year = year

            scoreText?.let {
                val value = Regex("""\d+(?:[.,]\d+)?""")
                    .find(it)
                    ?.value
                    ?.replace(",", ".")
                this.score = Score.from10(value)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val document = runCatching {
            app.get(
                "$mainUrl/?s=$encoded",
                headers = mapOf("User-Agent" to SCX_UA),
                referer = "$mainUrl/",
                allowRedirects = true
            ).document
        }.getOrNull() ?: return emptyList()

        return document.select("a[href*='/film/']")
            .mapNotNull { it.toSearchResultFromAnchor() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to SCX_UA,
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                referer = "$mainUrl/",
                allowRedirects = true
            ).document
        }.getOrNull() ?: return null

        val rawTitle = sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("title")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val title = rawTitle
            .replace(Regex("""(?i)\s*[-|]\s*(Sinema\s*CC|Sinema\.gg)\s*$"""), "")
            .replace(Regex("""(?i)\s+(Full HD|HD)\s+İzle$"""), "")
            .replace(Regex("""(?i)\s+İzle$"""), "")
            .trim()

        if (title.isBlank()) return null

        val poster = sequenceOf(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("link[rel='image_src']")?.attr("href"),
            document.selectFirst("img[src*='/uploads/']")?.attr("src"),
            document.selectFirst("img[data-src*='/uploads/']")?.attr("data-src"),
            document.selectFirst("img[data-original*='/uploads/']")?.attr("data-original"),
            document.selectFirst("div.f-bilgi img")?.attr("data-src"),
            document.selectFirst("div.f-bilgi img")?.attr("data-lazy-src"),
            document.selectFirst("div.f-bilgi img")?.attr("data-original"),
            document.selectFirst("div.f-bilgi img")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val pageText = document.text()

        val year = Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(document.selectFirst("div.f-bilgi")?.text().orEmpty().ifBlank { pageText })
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val description = sequenceOf(
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("div.f-bilgi div.ackl")?.text(),
            document.selectFirst(".film_ozeti, .f-ozet, .konu, .description, .plot")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select(
            "div.f-bilgi div.tur a, a[href*='/tur/'], a[href*='/kategori/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = Regex("""(?i)(\d{2,3})\s*Dakika""")
            .find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val actorNodes = document.select(
            "[class*='oyuncu'], .oyuncular li, .oyuncular div, .cast li, .cast div"
        )

        val actors = actorNodes.mapNotNull { node ->
            val name = sequenceOf(
                node.selectFirst("span.isim")?.text(),
                node.selectFirst(".isim")?.text(),
                node.selectFirst(".oyuncu-isim")?.text(),
                node.selectFirst(".actor-name")?.text(),
                node.selectFirst("a[href*='/oyuncu/']")?.text(),
                node.selectFirst("a")?.text(),
                node.ownText()
            ).firstOrNull { !it.isNullOrBlank() }
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()

            if (name.isNullOrBlank()) {
                null
            } else {
                val image = sequenceOf(
                    node.selectFirst("img")?.attr("data-src"),
                    node.selectFirst("img")?.attr("data-lazy-src"),
                    node.selectFirst("img")?.attr("data-original"),
                    node.selectFirst("img")?.attr("src")
                ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

                Actor(name, image)
            }
        }
            .filter {
                it.name.length in 2..80 &&
                    !it.name.equals("Oyuncuları", true) &&
                    !it.name.equals("Oyuncular", true) &&
                    !it.name.contains("Daha Fazla", true)
            }
            .distinctBy { it.name }

        val imdbText = sequenceOf(
            document.selectFirst("a[href*='imdb.com']")?.text(),
            document.selectFirst("span.imdb, span.puan, div.f-puan, .film_puani")?.text()
        ).firstOrNull { !it.isNullOrBlank() }

        val imdb = imdbText
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value?.replace(",", ".") }

        val trailerCandidates = document.select(
            "iframe, a, [data-src], [data-vsrc], [data-url]"
        ).mapNotNull { element ->
            sequenceOf(
                element.attr("src"),
                element.attr("data-vsrc"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() }
        }.toMutableList()

        Regex("""(?i)(?:https?:)?//(?:www\.)?(?:youtube\.com/(?:watch\?v=|embed/)|youtu\.be/)[^"'\s<>]+""")
            .findAll(document.html().replace("\\/", "/"))
            .forEach { trailerCandidates.add(it.value) }

        val trailer = trailerCandidates
            .mapNotNull { fixUrlNull(it.replace("\\/", "/")) }
            .firstOrNull { candidate ->
                candidate.contains("youtube.com/watch", true) ||
                    candidate.contains("youtu.be/", true) ||
                    candidate.contains("youtube-nocookie.com/embed/", true)
            }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.score = Score.from10(imdb)

            if (actors.isNotEmpty()) {
                addActors(actors)
            }

            if (!trailer.isNullOrBlank()) {
                addTrailer(trailer)
            }
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d(SCX_TAG, "loadLinks data=" + data)

    val pageHeaders = mapOf(
        "User-Agent" to SCX_UA,
        "Accept" to "text/html,application/xhtml+xml,application/json,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return fixUrlNull(raw.trim().replace("\\/", "/"))
    }

    val pages = LinkedHashSet<String>()
    pages.add(data)

    // Film detayından gerçek Player bağlantısını ve iframe'leri topla.
    runCatching {
        val detail = app.get(
            data,
            headers = pageHeaders,
            referer = "$mainUrl/",
            allowRedirects = true
        )

        detail.document.select(
            "a[href], button[data-href], [data-url], [data-src], iframe"
        ).forEach { element ->
            val label = element.text().trim()

            sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-vsrc"),
                element.attr("data-src"),
                element.attr("src")
            ).mapNotNull(::resolve).forEach { candidate ->
                if (
                    label.contains("Player", true) ||
                    candidate.contains("vidon=", true) ||
                    candidate.contains("player.filmizle.in", true) ||
                    candidate.contains("filmizle.in", true)
                ) {
                    pages.add(candidate)
                }
            }
        }
    }

    val iframeUrls = LinkedHashSet<String>()

    for (pageUrl in pages) {
        val response = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders,
                referer = data,
                allowRedirects = true
            )
        }.getOrNull() ?: continue

        response.document.select("iframe, [data-vsrc], [data-src], [data-url]").forEach { iframe ->
            sequenceOf(
                iframe.attr("data-vsrc"),
                iframe.attr("data-src"),
                iframe.attr("data-url"),
                iframe.attr("src")
            ).mapNotNull(::resolve).forEach { candidate ->
                if (
                    !candidate.contains("youtube", true) &&
                    !candidate.contains("youtu.be", true) &&
                    !candidate.contains("fragman", true) &&
                    !candidate.contains("trailer", true)
                ) {
                    iframeUrls.add(candidate.substringBefore("?img="))
                }
            }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\\s]+(?:player\.filmizle\.in|filmizle\.in)[^"'<>\\s]*""")
            .findAll(response.text)
            .mapNotNull { resolve(it.value) }
            .forEach { candidate ->
                if (!candidate.contains("youtube", true)) {
                    iframeUrls.add(candidate.substringBefore("?img="))
                }
            }
    }

    if (iframeUrls.isEmpty()) return false

    var emitted = false

    for (iframe in iframeUrls.distinct()) {
        Log.d(SCX_TAG, "player iframe=" + iframe)

        if (iframe.contains("player.filmizle.in", true) ||
            iframe.contains("filmizle.in", true)
        ) {
            val videoId = iframe
                .substringAfterLast("/")
                .substringBefore("?")
                .takeIf { it.isNotBlank() }

            if (!videoId.isNullOrBlank()) {
                val apiUrl =
                    "https://player.filmizle.in/player/index.php" +
                        "?data=" + java.net.URLEncoder.encode(videoId, "UTF-8") +
                        "&do=getVideo"

                val filmReferer = data.ifBlank { mainUrl + "/" }

                val panel = runCatching {
                    app.post(
                        apiUrl,
                        headers = mapOf(
                            "User-Agent" to SCX_UA,
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to "https://player.filmizle.in",
                            "Referer" to iframe
                        ),
                        referer = iframe,
                        data = mapOf(
                            "hash" to videoId,
                            "r" to filmReferer
                        )
                    ).parsedSafe<Panel>()
                }.getOrNull()

                val stream = panel?.securedLink ?: panel?.videoSource

                if (!stream.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            name,
                            "SinemaCX",
                            stream,
                            if (
                                stream.contains(".m3u8", true) ||
                                panel?.hls == true
                            ) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            quality = Qualities.P1080.value
                            referer = "https://player.filmizle.in/"
                            headers = mapOf(
                                "User-Agent" to SCX_UA,
                                "Referer" to "https://player.filmizle.in/"
                            )
                        }
                    )
                    emitted = true
                }
            }
        }

        runCatching {
            if (loadExtractor(
                    iframe,
                    data,
                    subtitleCallback
                ) { link ->
                    emitted = true
                    callback(link)
                }) {
                emitted = true
            }
        }.onFailure { error ->
            Log.e(SCX_TAG, "Extractor hata: " + error.message)
        }
    }

    return emitted
}

    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
