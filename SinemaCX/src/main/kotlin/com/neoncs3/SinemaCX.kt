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
        "$mainUrl/tur/aksiyon-filmleri/" to "Aksiyon Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/dram-filmleri/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-filmler/" to "Fantastik Filmleri",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim Filmleri",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmleri/" to "Korku Filmleri",
        "$mainUrl/tur/romantik-filmleri/" to "Romantik Filmleri",
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

        fun parseResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
            val results = document
                .select("a[href*='/film/'], a.baslik, a.resim")
                .mapNotNull { it.toSearchResultFromAnchor() }
                .distinctBy { it.url }

            val words = q.lowercase()
                .split(Regex("""\s+"""))
                .filter { it.length >= 2 }

            if (words.isEmpty()) return results

            // Site arama sorgusunu yok sayıp ana sayfayı döndürürse,
            // ana sayfadaki rastgele filmleri yanlış sonuç olarak göstermeyelim.
            val matching = results.filter { result ->
                val haystack = (result.name + " " + result.url).lowercase()
                words.count { word -> haystack.contains(word) } > 0
            }

            return if (matching.isNotEmpty()) matching else emptyList()
        }

        val timestamp = System.currentTimeMillis()
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")

        // Önce sitenin klasik WordPress aramasını, cache'i kırarak dene.
        val urls = listOf(
            "$mainUrl/?s=$q&scx_search=$timestamp",
            "$mainUrl/?s=$encoded&scx_search=$timestamp"
        ).distinct()

        for (url in urls) {
            val document = runCatching {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to SCX_UA,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Cache-Control" to "no-cache"
                    ),
                    referer = "$mainUrl/",
                    allowRedirects = true
                ).document
            }.getOrNull() ?: continue

            val results = parseResults(document)
            if (results.isNotEmpty()) {
                Log.d(SCX_TAG, "Arama sonucu: " + q + " -> " + results.size)
                return results
            }
        }

        return emptyList()
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
        val cleanIframe = iframeUrl.trim().substringBefore("?img=")

        val playerResponse = runCatching {
            app.get(
                cleanIframe,
                headers = mapOf(
                    "User-Agent" to SCX_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                referer = "$mainUrl/",
                allowRedirects = true
            )
        }.getOrNull() ?: return false

        if (!playerResponse.isSuccessful) return false

        val playerHtml = playerResponse.text

        Regex("""playerjsSubtitle\s*=\s*["']([^"']+)["']""")
            .find(playerHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { section ->
                Regex("""\[(.*?)](https?://[^\s",]+)""")
                    .findAll(section.replace("\\/", "/"))
                    .forEach { match ->
                        val language = match.groupValues.getOrNull(1).orEmpty()
                        val subtitleUrl = match.groupValues.getOrNull(2).orEmpty()
                        if (language.isNotBlank() && subtitleUrl.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = language,
                                    url = fixUrl(subtitleUrl)
                                )
                            )
                        }
                    }
            }

        val videoId = Regex("""/video/([a-zA-Z0-9_-]+)""")
            .find(cleanIframe)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val hash = Regex("""(?i)hash\s*[:=]\s*["']([^"']+)["']""")
            .find(playerHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?: videoId

        val apiBase = when {
            cleanIframe.contains("player.filmizle.in", true) ->
                "https://player.filmizle.in"
            cleanIframe.contains("panel.sinema.cx", true) ->
                "https://panel.sinema.cx"
            else -> return false
        }

        val apiUrl = "$apiBase/player/index.php?data=$videoId&do=getVideo"

        fun extractStream(body: String): String? {
            return Regex(""""securedLink"\s*:\s*"([^"]+)"""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?: Regex(""""videoSource"\s*:\s*"([^"]+)"""")
                    .find(body)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace("\\/", "/")
                ?: Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""")
                    .find(body)
                    ?.value
        }

        // Önce gerçek film URL'si ile isteği yap.
        var apiResponse = runCatching {
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
                    "Referer" to cleanIframe,
                    "User-Agent" to SCX_UA,
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = cleanIframe
            ).text
        }.getOrDefault("")

        var streamUrl = extractStream(apiResponse)

        // Bazı eski kaynaklar r değerinde sadece ana siteyi kabul ediyor.
        if (streamUrl.isNullOrBlank()) {
            apiResponse = runCatching {
                app.post(
                    apiUrl,
                    data = mapOf(
                        "hash" to hash,
                        "r" to "$mainUrl/",
                        "s" to ""
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to cleanIframe,
                        "User-Agent" to SCX_UA,
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    ),
                    referer = cleanIframe
                ).text
            }.getOrDefault("")

            streamUrl = extractStream(apiResponse)
        }

        if (streamUrl.isNullOrBlank()) {
            Log.e(SCX_TAG, "Filmizle kaynak vermedi: " + cleanIframe)
            return false
        }

        val streamRef = if (apiBase.contains("filmizle.in", true)) {
            "https://player.filmizle.in/"
        } else {
            "$apiBase/"
        }

        callback(
            newExtractorLink(
                source = "FilmizleIn",
                name = "SinemaCX | 1080p",
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                quality = Qualities.P1080.value
                referer = streamRef
                headers = mapOf(
                    "Referer" to streamRef,
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
            document.selectFirst("div.f-bilgi img")?.attr("data-src"),
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
        )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = Regex("""(?i)(\d{2,3})\s*Dakika""")
            .find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val actors = document.select(
            "li.oyuncu-k, .oyuncular li, .oyuncular div, .cast li, .cast div, [class*='oyuncu-k']"
        )
            .mapNotNull { node ->
                val actorName = sequenceOf(
                    node.selectFirst("span.isim")?.text(),
                    node.selectFirst(".isim")?.text(),
                    node.selectFirst(".oyuncu-isim")?.text(),
                    node.selectFirst(".actor-name")?.text(),
                    node.selectFirst("a[href*='/oyuncu/']")?.text(),
                    node.selectFirst("a")?.text()
                )
                    .firstOrNull { !it.isNullOrBlank() }
                    ?.replace(Regex("""\s+"""), " ")
                    ?.trim()
                    ?: return@mapNotNull null

                val image = sequenceOf(
                    node.selectFirst("img")?.attr("data-src"),
                    node.selectFirst("img")?.attr("data-lazy-src"),
                    node.selectFirst("img")?.attr("data-original"),
                    node.selectFirst("img")?.attr("src")
                )
                    .firstOrNull { !it.isNullOrBlank() }
                    ?.let(::fixUrlNull)

                Actor(actorName, image)
            }
            .filter {
                it.name.length in 2..80 &&
                    !it.name.equals("Oyuncular", true) &&
                    !it.name.equals("Oyuncuları", true)
            }
            .distinctBy { it.name }

        val imdbText = sequenceOf(
            document.selectFirst("a[href*='imdb.com']")?.text(),
            document.selectFirst("span.imdb, span.puan, div.f-puan, .film_puani")?.text()
        ).firstOrNull { !it.isNullOrBlank() }

        val imdb = imdbText
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value?.replace(",", ".") }

        val trailer = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:youtube\.com/(?:watch\?v=|embed/)|youtu\.be/)[^"'\s<>]+"""
        )
            .find(document.html().replace("\\/", "/"))
            ?.value
            ?.let(::fixUrlNull)

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

            finalTrailers.forEach { addTrailer(it) }
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d(SCX_TAG, "loadLinks data=" + data)

    fun decodeIframeUrl(raw: String): String? {
        val value = raw.trim().replace("\\/", "/")

        if (value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("//")
        ) {
            return fixUrlNull(value)
        }

        return runCatching {
            val decoded = String(
                android.util.Base64.decode(value, android.util.Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()

            if (decoded.startsWith("http://", true) ||
                decoded.startsWith("https://", true) ||
                decoded.startsWith("//")
            ) {
                fixUrlNull(decoded)
            } else {
                null
            }
        }.getOrNull()
    }

    fun collectIframes(document: org.jsoup.nodes.Document): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()

        document.select("iframe, [data-vsrc], [data-src], [data-url]").forEach { element ->
            sequenceOf(
                element.attr("data-vsrc"),
                element.attr("data-src"),
                element.attr("src"),
                element.attr("data-url")
            )
                .filter { it.isNotBlank() }
                .forEach { raw ->
                    decodeIframeUrl(raw)?.let { iframe ->
                        val clean = iframe.substringBefore("?img=")
                        if (
                            !clean.contains("youtube", true) &&
                            !clean.contains("youtu.be", true) &&
                            !clean.contains("vr_set=", true) &&
                            !clean.contains("/fragman", true) &&
                            !clean.contains("trailer", true)
                        ) {
                            result.add(clean)
                        }
                    }
                }
        }

        Regex("""(?i)(?:https?:)?//[^"'<>\s]+/video/[A-Za-z0-9_-]+""")
            .findAll(document.html())
            .forEach { match ->
                decodeIframeUrl(match.value)?.let { iframe ->
                    val clean = iframe.substringBefore("?img=")
                    if (
                        !clean.contains("youtube", true) &&
                        !clean.contains("youtu.be", true) &&
                        !clean.contains("/fragman", true)
                    ) {
                        result.add(clean)
                    }
                }
            }

        return result
    }

    suspend fun resolveDocument(
        document: org.jsoup.nodes.Document,
        filmUrl: String
    ): Boolean {
        val iframes = collectIframes(document)
        Log.d(SCX_TAG, "Player adayları=" + iframes.size)

        // Önce Filmizle/Panel kaynaklarının tamamını dene.
        for (iframe in iframes) {
            if (
                iframe.contains("player.filmizle.in", true) ||
                iframe.contains("panel.sinema.cx", true)
            ) {
                if (extractFilmizleLink(
                        iframeUrl = iframe,
                        filmReferer = filmUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
                    return true
                }
            }
        }

        // Doğrudan m3u8/mp4 varsa kullan.
        for (iframe in iframes) {
            if (iframe.contains(".m3u8", true) || iframe.contains(".mp4", true)) {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "SinemaCX | Doğrudan Kaynak",
                        url = iframe,
                        type = if (iframe.contains(".m3u8", true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        quality = Qualities.P1080.value
                        referer = filmUrl
                        headers = mapOf(
                            "Referer" to filmUrl,
                            "User-Agent" to SCX_UA
                        )
                    }
                )
                return true
            }
        }

        // Diğer CloudStream extractor'ları.
        for (iframe in iframes) {
            if (
                iframe.contains("player.filmizle.in", true) ||
                iframe.contains("panel.sinema.cx", true) ||
                iframe.contains(".m3u8", true) ||
                iframe.contains(".mp4", true)
            ) {
                continue
            }

            val found = runCatching {
                loadExtractor(iframe, filmUrl, subtitleCallback) { link ->
                    callback(link)
                }
            }.getOrDefault(false)

            if (found) return true
        }

        return false
    }

    val firstResponse = runCatching {
        app.get(
            data,
            headers = mapOf(
                "User-Agent" to SCX_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            ),
            referer = "$mainUrl/",
            allowRedirects = true
        )
    }.getOrNull() ?: return false

    if (!firstResponse.isSuccessful) return false

    // Önce normal film sayfası.
    if (resolveDocument(firstResponse.document, data)) {
        return true
    }

    // İlk sayfada player bozuk/eksikse, başarısızlıktan SONRA /2/ sayfasını dene.
    val part2Url = firstResponse.document
        .selectFirst("a[href*='/2/'], .part-sayfala a")
        ?.attr("href")
        ?.let(::fixUrlNull)
        ?: if (!data.endsWith("/2/")) data.removeSuffix("/") + "/2/" else null

    if (!part2Url.isNullOrBlank()) {
        val secondResponse = runCatching {
            app.get(
                part2Url,
                headers = mapOf(
                    "User-Agent" to SCX_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                ),
                referer = data,
                allowRedirects = true
            )
        }.getOrNull()

        if (secondResponse?.isSuccessful == true) {
            if (resolveDocument(secondResponse.document, part2Url)) {
                return true
            }
        }
    }

    Log.e(SCX_TAG, "Hiçbir video kaynağı çözülemedi: " + data)
    return false
}



    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}
