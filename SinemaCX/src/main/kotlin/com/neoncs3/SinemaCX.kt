package com.neoncs3

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

private const val SCX_TAG = "SinemaCX"
private const val SCX_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class SinemaCX : MainAPI() {
    override var mainUrl = "https://sinemacc.com"
    override var name = "SinemaCX"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val pageHeaders = mapOf(
        "User-Agent" to SCX_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

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
        "$mainUrl/tur/romantik-filmler/" to "Romantik Filmler",
        "$mainUrl/tur/savas-filmleri/" to "Savaş Filmleri",
        "$mainUrl/tur/suc-filmleri/" to "Suç Filmleri",
        "$mainUrl/tur/western-filmleri/" to "Western Filmleri",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document = runCatching {
            app.get(
                buildPageUrl(request.data, page),
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = document.select(
            "div.son div.frag-k, div.icerik div.frag-k, article, .film-card"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    private fun buildPageUrl(base: String, page: Int): String {
        val clean = base.trimEnd('/')
        return when {
            clean.endsWith("/page") -> "$clean/$page/"
            clean.endsWith("/page-") -> "$clean$page/"
            clean.contains("sayfa=") -> "$clean$page"
            clean == mainUrl -> "$clean/page/$page/"
            else -> "$clean/page/$page/"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = sequenceOf(
            selectFirst("div.yanac span")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst(".card-title")?.text(),
            selectFirst("h3 a")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h2 a")?.text(),
            selectFirst("h2")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val href = fixUrlNull(
            sequenceOf(
                selectFirst("div.yanac a")?.attr("href"),
                selectFirst(".film-title a")?.attr("href"),
                selectFirst(".card-title a")?.attr("href"),
                selectFirst("a[href*='/film/']")?.attr("href"),
                selectFirst("a[href]")?.attr("href"),
            ).firstOrNull { !it.isNullOrBlank() }
        ) ?: return null

        if (!href.contains("/film/")) return null

        val poster = sequenceOf(
            selectFirst("a.resim img")?.attr("data-src"),
            selectFirst("a.resim img")?.attr("data-lazy-src"),
            selectFirst("a.resim img")?.attr("src"),
            selectFirst(".film-poster img")?.attr("data-src"),
            selectFirst(".film-poster img")?.attr("src"),
            selectFirst("img")?.attr("data-src"),
            selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val scoreText = sequenceOf(
            selectFirst(".imdb")?.text(),
            selectFirst(".rating")?.text(),
            selectFirst(".film-rating")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            scoreText?.let {
                val value = Regex("""\d+(?:[\\.,]\d+)?""").find(it)?.value
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
                headers = pageHeaders,
                referer = "$mainUrl/",
            ).document
        }.getOrNull() ?: return emptyList()

        return document.select(
            "div.icerik div.frag-k, div.son div.frag-k, article, .film-card"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: return null

        val document = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst("div.f-bilgi h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst(".film-title")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = sequenceOf(
            document.selectFirst("link[rel='image_src']")?.attr("href"),
            document.selectFirst(".film-poster img")?.attr("data-src"),
            document.selectFirst(".film-poster img")?.attr("src"),
            document.selectFirst(".f-bilgi img")?.attr("data-src"),
            document.selectFirst(".f-bilgi img")?.attr("src"),
            document.selectFirst("meta[property='og:image']")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val infoText = document.selectFirst("div.f-bilgi")?.text().orEmpty()
        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(infoText)?.value?.toIntOrNull()

        val ratingText = sequenceOf(
            document.selectFirst(".f-bilgi .imdb")?.text(),
            document.selectFirst(".film-ratings-container b")?.text(),
            document.selectFirst("a[href*='imdb.com']")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }

        val rating = ratingText?.let {
            Regex("""\d+(?:[\\.,]\d+)?""").find(it)?.value?.replace(",", ".")
        }

        val description = sequenceOf(
            document.selectFirst("div.f-bilgi div.ackl")?.text(),
            document.selectFirst(".description-text")?.text(),
            document.selectFirst(".description")?.text(),
            document.selectFirst("meta[name='description']")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select(
            "div.f-bilgi div.tur a, .categories-container-details a, .categories-container a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = sequenceOf(
            Regex("""Süre:\s*</span>\s*(\d+)\s*Dakika""")
                .find(document.html())?.groupValues?.getOrNull(1),
            Regex("""(\d+)\s*Dakika""")
                .find(infoText)?.groupValues?.getOrNull(1),
            Regex("""(\d+)\s*Dk\.?""")
                .find(infoText)?.groupValues?.getOrNull(1),
        ).firstNotNullOfOrNull { it?.toIntOrNull() }

        val actors = document.select(
            "li.oync li.oyuncu-k, .oyuncular-section .actors-grid .col, .oyuncu"
        ).mapNotNull { element ->
            val actorName = sequenceOf(
                element.selectFirst("span.isim")?.text(),
                element.selectFirst(".name")?.text(),
                element.selectFirst("a")?.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
                ?: return@mapNotNull null

            val actorImage = sequenceOf(
                element.selectFirst("img")?.attr("data-src"),
                element.selectFirst("img")?.attr("data-lazy-src"),
                element.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

            Actor(actorName, actorImage)
        }.distinctBy { it.name }

        val trailer = document.select(
            "iframe[src*='youtube'], iframe[data-vsrc*='youtube'], a[href*='youtube.com/watch'], a[href*='youtu.be/']"
        ).mapNotNull { element ->
            sequenceOf(
                element.attr("src"),
                element.attr("data-vsrc"),
                element.attr("href"),
            ).firstOrNull { it.isNotBlank() }
        }.firstOrNull { it.isNotBlank() }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.score = Score.from10(rating)
            addActors(actors)

            if (!trailer.isNullOrBlank()) {
                val trailerUrl = normalizeUrl(trailer)
                if (trailerUrl.contains("youtube.com", true) || trailerUrl.contains("youtu.be", true)) {
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(SCX_TAG, "loadLinks data=" + data)

        val pageDocument = runCatching {
            app.get(
                data,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return false

        val iframeCandidates = pageDocument.select("iframe").mapNotNull { iframe ->
            val raw = sequenceOf(
                iframe.attr("data-vsrc"),
                iframe.attr("src"),
                iframe.attr("data-src"),
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

            val normalized = normalizeUrl(raw).substringBefore("?img=")
            if (isTrailerUrl(normalized)) null else normalized
        }.distinct()

        val actualIframes = if (iframeCandidates.isNotEmpty()) {
            iframeCandidates
        } else {
            listOf(
                data.removeSuffix("/") + "/2/",
                if (data.endsWith("/")) data + "2/" else "$data/2/",
            ).distinct().flatMap { fallback ->
                runCatching {
                    app.get(
                        fallback,
                        headers = pageHeaders,
                        referer = "$mainUrl/",
                    ).document.select("iframe").mapNotNull { iframe ->
                        val raw = sequenceOf(
                            iframe.attr("data-vsrc"),
                            iframe.attr("src"),
                            iframe.attr("data-src"),
                        ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                        val normalized = normalizeUrl(raw).substringBefore("?img=")
                        if (isTrailerUrl(normalized)) null else normalized
                    }
                }.getOrDefault(emptyList())
            }.distinct()
        }

        if (actualIframes.isEmpty()) {
            Log.d(SCX_TAG, "Gerçek player iframe bulunamadı")
            return false
        }

        var emitted = false

        for (iframe in actualIframes) {
            if (isTrailerUrl(iframe)) continue

            Log.d(SCX_TAG, "player=" + iframe)

            runCatching {
                extractSubtitles(iframe, subtitleCallback)
            }.onFailure {
                Log.d(SCX_TAG, "altyazı okunamadı: " + it.message)
            }

            if (iframe.contains("player.filmizle.in", true)) {
                val direct = resolveFilmizlePlayer(iframe)
                if (!direct.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = direct,
                            type = if (direct.contains(".m3u8", true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            },
                        ) {
                            referer = iframe
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "Referer" to iframe,
                                "User-Agent" to SCX_UA,
                            )
                        }
                    )
                    emitted = true
                    continue
                }
            }

            var iframeEmitted = false

            runCatching {
                loadExtractor(
                    iframe,
                    "$mainUrl/",
                    subtitleCallback,
                ) { link ->
                    iframeEmitted = true
                    emitted = true
                    callback(link)
                }
            }.onFailure {
                Log.e(SCX_TAG, "loadExtractor hata [" + iframe + "]: " + it.message)
            }

            if (iframeEmitted) {
                Log.d(SCX_TAG, "player çalıştı: " + iframe)
            }
        }

        return emitted
    }

    private suspend fun extractSubtitles(
        iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val source = app.get(
            iframe,
            headers = mapOf("User-Agent" to SCX_UA),
            referer = "$mainUrl/",
        ).text

        val assignment = Regex(
            """playerjsSubtitle\s*=\s*["'](.+?)["']""",
            RegexOption.IGNORE_CASE,
        ).find(source)?.groupValues?.getOrNull(1) ?: return

        Regex(
            """\[(.*?)](https?://[^\s"',]+)""",
            RegexOption.IGNORE_CASE,
        ).findAll(assignment).forEach { match ->
            val lang = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val url = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (url.isBlank()) return@forEach

            subtitleCallback(
                SubtitleFile(
                    lang = if (lang.isBlank()) "Türkçe" else lang,
                    url = fixUrl(url),
                )
            )
        }
    }

    private suspend fun resolveFilmizlePlayer(iframe: String): String? {
        val host = Regex("""https?://([^/]+)""")
            .find(iframe)?.groupValues?.getOrNull(1)
            ?: return null

        val token = iframe.substringAfterLast("/")
            .substringBefore("?")
            .takeIf { it.isNotBlank() }
            ?: return null

        return runCatching {
            app.post(
                "https://" + host + "/player/index.php?data=" + token + "&do=getVideo",
                headers = mapOf(
                    "User-Agent" to SCX_UA,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                referer = "$mainUrl/",
                allowRedirects = true,
            ).parsedSafe<Panel>()?.securedLink
        }.getOrNull()
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:" + trimmed
            trimmed.startsWith("/") -> mainUrl + trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> fixUrlNull(trimmed) ?: trimmed
        }
    }

    private fun isTrailerUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("fragman") ||
            value.contains("trailer")
    }

    private data class Panel(
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
    )
}
