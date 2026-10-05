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
        val document = app.get(url).document

        val title       = document.selectFirst("div.f-bilgi h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("link[rel='image_src']")?.attr("href"))
        val year        = document.selectFirst("div.f-bilgi ul.detay a[href*='yapim']")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.f-bilgi div.ackl")?.text()?.trim()
        val tags        = document.select("div.f-bilgi div.tur a").map { it.text() }
        val duration    = Regex("""Süre: </span>(\d+) Dakika</li>""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.select("li.oync li.oyuncu-k").map {
            Actor(it.selectFirst("span.isim")!!.text(), it.selectFirst("img")!!.attr("data-src"))
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)

            val imdb = document.selectFirst("a[href*='imdb.com']")?.text()
                ?.let { Regex("""\d+(?:[\.,]\d+)?""").find(it)?.value?.replace(",", ".") }
            this.score = Score.from10(imdb)

            val trailer = document.select(
                "iframe[src*='youtube'], iframe[data-vsrc*='youtube'], a[href*='youtube.com/watch'], a[href*='youtu.be/']"
            ).mapNotNull { element ->
                sequenceOf(
                    element.attr("src"),
                    element.attr("data-vsrc"),
                    element.attr("href")
                ).firstOrNull { it.isNotBlank() }
            }.firstOrNull { it.isNotBlank() }

            if (!trailer.isNullOrBlank()) {
                addTrailer(fixUrl(trailer))
            }

        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d("SCX", "data = " + data)

    val document = runCatching {
        app.get(data, headers = mapOf("User-Agent" to SCX_UA), referer = mainUrl + "/").document
    }.getOrNull() ?: return false

    val iframeUrls = document.select("iframe").mapNotNull { iframe ->
        val raw = iframe.attr("data-vsrc").ifBlank {
            iframe.attr("src").ifBlank { iframe.attr("data-src") }
        }.trim()
        if (raw.isBlank()) return@mapNotNull null
        val url = fixUrlNull(raw)?.substringBefore("?img=") ?: return@mapNotNull null
        if (url.contains("youtube", true) || url.contains("youtu.be", true) ||
            url.contains("fragman", true) || url.contains("trailer", true)) null else url
    }.distinct()

    val candidates = if (iframeUrls.isNotEmpty()) iframeUrls else {
        val fallback = if (data.endsWith("/")) data + "2/" else data + "/2/"
        runCatching {
            app.get(fallback, headers = mapOf("User-Agent" to SCX_UA), referer = mainUrl + "/")
                .document.select("iframe").mapNotNull { iframe ->
                    val raw = iframe.attr("data-vsrc").ifBlank {
                        iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    }.trim()
                    if (raw.isBlank()) return@mapNotNull null
                    fixUrlNull(raw)?.substringBefore("?img=")
                }.filterNot { it.contains("youtube", true) || it.contains("youtu.be", true) }
        }.getOrDefault(emptyList()).distinct()
    }

    if (candidates.isEmpty()) return false

    var emitted = false
    for (iframe in candidates) {
        Log.d("SCX", "iframe = " + iframe)

        runCatching {
            val iframeSource = app.get(
                iframe,
                headers = mapOf("User-Agent" to SCX_UA),
                referer = mainUrl + "/"
            ).text
            val subtitleMatch = Regex("""playerjsSubtitle\s*=\s*["'](.+?)["']""", RegexOption.IGNORE_CASE)
                .find(iframeSource)
            subtitleMatch?.groupValues?.getOrNull(1)?.let { section ->
                Regex("""\[(.*?)](https?://[^\s"',]+)""").findAll(section).forEach { match ->
                    val lang = match.groupValues.getOrNull(1).orEmpty().ifBlank { "Türkçe" }
                    val subUrl = match.groupValues.getOrNull(2).orEmpty()
                    if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(lang = lang, url = fixUrl(subUrl)))
                }
            }
        }

        if (iframe.contains("player.filmizle.in", true)) {
            val host = Regex("""https?://([^/]+)""").find(iframe)?.groupValues?.getOrNull(1)
            val token = iframe.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
            if (!host.isNullOrBlank() && !token.isNullOrBlank()) {
                val direct = runCatching {
                    app.post(
                        "https://" + host + "/player/index.php?data=" + token + "&do=getVideo",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to SCX_UA),
                        referer = mainUrl + "/"
                    ).parsedSafe<Panel>()?.securedLink
                }.getOrNull()
                if (!direct.isNullOrBlank()) {
                    callback(newExtractorLink(name, name, direct, if (direct.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value
                        referer = iframe
                        headers = mapOf("Referer" to iframe, "User-Agent" to SCX_UA)
                    })
                    emitted = true
                    continue
                }
            }
        }

        runCatching {
            loadExtractor(iframe, mainUrl + "/", subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }.onFailure {
            Log.e("SCX", "extractor hata: " + it.message)
        }
    }

    return emitted
}
    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
