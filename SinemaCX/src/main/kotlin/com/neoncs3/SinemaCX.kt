// Site yapısı ve player akışı için mevcut açık kaynak SinemaCX sağlayıcısından yararlanılmıştır.

package com.neoncs3

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty

private const val TMDB_API_KEY = "500330721680edb6d5f7f12ba7cd9023"

private const val SCX_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

data class TmdbVideo(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("iso_639_1") val language: String? = null,
    @JsonProperty("official") val official: Boolean? = null
)

data class TmdbVideos(
    @JsonProperty("results") val results: List<TmdbVideo> = emptyList()
)

data class TmdbCast(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
    @JsonProperty("order") val order: Int? = null
)

data class TmdbCredits(
    @JsonProperty("cast") val cast: List<TmdbCast> = emptyList()
)

data class TmdbDetails(
    @JsonProperty("videos") val videos: TmdbVideos? = null,
    @JsonProperty("credits") val credits: TmdbCredits? = null
)

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

    private suspend fun getTmdbExtras(
        document: org.jsoup.nodes.Document
    ): Pair<List<Actor>, List<String>> {
        val tmdbHref = document.selectFirst("a[href*='themoviedb.org/movie/']")
            ?.attr("href")
            ?: return emptyList<Actor>() to emptyList()

        val match = Regex("""themoviedb\.org/movie/(\d+)""").find(tmdbHref)
            ?: return emptyList<Actor>() to emptyList()

        val id = match.groupValues.getOrNull(1)
            ?: return emptyList<Actor>() to emptyList()

        val details = runCatching {
            app.get(
                "https://api.themoviedb.org/3/movie/" + id +
                    "?api_key=" + TMDB_API_KEY +
                    "&language=tr-TR" +
                    "&append_to_response=videos,credits" +
                    "&include_video_language=tr,en,null",
                headers = mapOf("User-Agent" to SCX_UA),
                referer = "$mainUrl/"
            ).parsedSafe<TmdbDetails>()
        }.getOrNull() ?: return emptyList<Actor>() to emptyList()

        val trailers = details.videos?.results.orEmpty()
            .filter {
                it.site.equals("YouTube", true) &&
                    !it.key.isNullOrBlank() &&
                    (
                        it.type.equals("Trailer", true) ||
                            it.type.equals("Teaser", true)
                    )
            }
            .sortedWith(
                compareBy<TmdbVideo>(
                    { if (it.language == "tr") 0 else 1 },
                    { if (it.type.equals("Trailer", true)) 0 else 1 },
                    { if (it.official == true) 0 else 1 }
                )
            )
            .mapNotNull { video ->
                video.key?.let { key ->
                    "https://www.youtube.com/watch?v=" + key
                }
            }
            .distinct()
            .take(3)

        val actors = details.credits?.cast.orEmpty()
            .sortedBy { it.order ?: Int.MAX_VALUE }
            .take(15)
            .mapNotNull { cast ->
                val actorName = cast.name?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val image = cast.profilePath?.let {
                    "https://image.tmdb.org/t/p/w500" + it
                }

                Actor(actorName, image)
            }

        return actors to trailers
    }

    private suspend fun extractFilmizleLink(
        iframeUrl: String,
        filmReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerResponse = runCatching {
            app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to SCX_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to filmReferer
                ),
                referer = filmReferer,
                allowRedirects = true
            )
        }.getOrNull() ?: return false

        if (!playerResponse.isSuccessful) return false

        val playerHtml = playerResponse.text

        val videoId = Regex("""/video/([A-Za-z0-9_-]+)""")
            .find(iframeUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: iframeUrl.substringAfterLast("/")
                .substringBefore("?")
                .takeIf { it.isNotBlank() }
            ?: return false

        val hash = Regex("""(?i)hash\s*[:=]\s*["']([^"']+)["']""")
            .find(playerHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?: videoId

        Regex("""(?i)playerjsSubtitle\s*=\s*["']\[(.*?)](https?://[^"']+)["']""")
            .findAll(playerHtml)
            .forEach { match ->
                val language = match.groupValues.getOrNull(1).orEmpty().ifBlank { "Türkçe" }
                val subtitleUrl = match.groupValues.getOrNull(2).orEmpty()
                if (subtitleUrl.isNotBlank()) {
                    subtitleCallback(
                        SubtitleFile(
                            lang = language,
                            url = subtitleUrl.replace("\\/", "/")
                        )
                    )
                }
            }

        val apiUrl =
            "https://player.filmizle.in/player/index.php" +
                "?data=" + java.net.URLEncoder.encode(videoId, "UTF-8") +
                "&do=getVideo"

        val legacyResponse = runCatching {
            app.post(
                apiUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/",
                    "User-Agent" to SCX_UA,
                    "Accept" to "*/*"
                ),
                referer = "$mainUrl/"
            ).text
        }.getOrDefault("")

        val apiResponse = if (
            legacyResponse.contains("securedLink", true) ||
            legacyResponse.contains("videoSource", true) ||
            legacyResponse.contains(".m3u8", true)
        ) {
            legacyResponse
        } else {
            runCatching {
                app.post(
                    apiUrl,
                    data = mapOf(
                        "hash" to hash,
                        "r" to filmReferer,
                        "s" to ""
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to iframeUrl,
                        "User-Agent" to SCX_UA,
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    ),
                    referer = iframeUrl
                ).text
            }.getOrDefault("")
        }

        val streamUrl = Regex("""(?i)"securedLink"\s*:\s*"([^"]+)"""")
            .find(apiResponse)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?: Regex("""(?i)"videoSource"\s*:\s*"([^"]+)"""")
                .find(apiResponse)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
            ?: Regex("""https?://[^"'\\s<>]+\.m3u8[^"'\\s<>]*""")
                .find(apiResponse)
                ?.value

        if (streamUrl.isNullOrBlank()) {
            Log.e(SCX_TAG, "FilmizleIn stream bulunamadı: " + iframeUrl)
            return false
        }

        callback(
            newExtractorLink(
                name = "SinemaCX",
                source = "FilmizleIn",
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                quality = Qualities.P1080.value
                referer = iframeUrl
                headers = mapOf(
                    "Referer" to iframeUrl,
                    "User-Agent" to SCX_UA
                )
            }
        )

        return true
    }

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

        val tmdbExtras = getTmdbExtras(document)
        val finalActors = if (actors.isNotEmpty()) actors else tmdbExtras.first
        val finalTrailers = if (!trailer.isNullOrBlank()) listOf(trailer) else tmdbExtras.second

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.score = Score.from10(imdb)

            if (finalActors.isNotEmpty()) {
                addActors(finalActors)
            }

            finalTrailers.forEach { trailerUrl ->
                addTrailer(trailerUrl)
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

    val headers = mapOf(
        "User-Agent" to SCX_UA,
        "Accept" to "text/html,application/xhtml+xml,application/json,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return fixUrlNull(raw.trim().replace("\\/", "/"))
    }

    val filmPages = LinkedHashSet<String>()
    filmPages.add(data)

    fun collectCandidates(document: org.jsoup.nodes.Document) {
        document.select("iframe, a[href], [data-vsrc], [data-src], [data-url]").forEach { element ->
            val label = element.text().trim()
            val raw = sequenceOf(
                element.attr("data-vsrc"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("src"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() }

            val candidate = resolve(raw) ?: return@forEach

            if (
                candidate.contains("/video/", true) ||
                candidate.contains("player.filmizle.in", true) ||
                candidate.contains("filmizle.in", true) ||
                candidate.contains("vidon=", true) ||
                candidate.contains("vr_set=", true) ||
                label.contains("Player", true)
            ) {
                filmPages.add(candidate.substringBefore("?img="))
            }
        }
    }

    runCatching {
        val response = app.get(
            data,
            headers = headers,
            referer = "$mainUrl/",
            allowRedirects = true
        )
        collectCandidates(response.document)
    }

    // İkinci sayfada gerçek player bulunan filmleri de destekle.
    if (filmPages.none { it.contains("/video/", true) || it.contains("player.filmizle.in", true) }) {
        runCatching {
            val response = app.get(
                data.removeSuffix("/") + "/2/",
                headers = headers,
                referer = data,
                allowRedirects = true
            )
            collectCandidates(response.document)
        }
    }

    val iframeUrls = LinkedHashSet<String>()

    for (page in filmPages) {
        val response = runCatching {
            app.get(
                page,
                headers = headers,
                referer = data,
                allowRedirects = true
            )
        }.getOrNull() ?: continue

        response.document.select("iframe, [data-vsrc], [data-src], [data-url]").forEach { element ->
            val raw = sequenceOf(
                element.attr("data-vsrc"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("src")
            ).firstOrNull { it.isNotBlank() }

            val iframe = resolve(raw) ?: return@forEach

            if (!iframe.contains("youtube", true) && !iframe.contains("youtu.be", true)) {
                iframeUrls.add(iframe.substringBefore("?img="))
            }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+/video/[A-Za-z0-9_-]+""")
            .findAll(response.text)
            .mapNotNull { resolve(it.value) }
            .forEach { iframeUrls.add(it.substringBefore("?img=")) }
    }

    var emitted = false

    for (iframe in iframeUrls.distinct()) {
        if (iframe.contains("/video/", true) || iframe.contains("player.filmizle.in", true)) {
            if (extractFilmizleLink(
                    iframeUrl = iframe,
                    filmReferer = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )) {
                emitted = true
                continue
            }
        }

        runCatching {
            if (loadExtractor(iframe, data, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }) {
                emitted = true
            }
        }
    }

    return emitted
}


    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}
