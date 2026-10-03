package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder

private const val JET_TAG = "JetFilmizle"
private const val JET_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val pageHeaders = mapOf(
        "User-Agent" to JET_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler/sayfa-" to "En Yeni Filmler",
        "$mainUrl/diziler/sayfa-" to "En Yeni Diziler",
        "$mainUrl/filmler/en-cok-izlenenler/sayfa-" to "En Çok İzlenen Filmler",
        "$mainUrl/diziler/siralama-en-cok-izlenen/sayfa-" to "En Çok İzlenen Diziler",
        "$mainUrl/saglayici/netflix?sayfa=" to "Netflix",
        "$mainUrl/yerli-filmler/sayfa-" to "Yerli Filmler",
        "$mainUrl/tur/aile/film/sayfa-" to "Aile",
        "$mainUrl/tur/aksiyon/film/sayfa-" to "Aksiyon",
        "$mainUrl/tur/animasyon/film/sayfa-" to "Animasyon",
        "$mainUrl/tur/bilim-kurgu/film/sayfa-" to "Bilim Kurgu",
        "$mainUrl/tur/dram/film/sayfa-" to "Dram",
        "$mainUrl/tur/fantastik/film/sayfa-" to "Fantastik",
        "$mainUrl/tur/gerilim/film/sayfa-" to "Gerilim",
        "$mainUrl/tur/gizem/film/sayfa-" to "Gizem",
        "$mainUrl/tur/komedi/film/sayfa-" to "Komedi",
        "$mainUrl/tur/korku/film/sayfa-" to "Korku",
        "$mainUrl/tur/macera/film/sayfa-" to "Macera",
        "$mainUrl/tur/romantik/film/sayfa-" to "Romantik",
        "$mainUrl/tur/suc/film/sayfa-" to "Suç",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val base = request.data
        val url = if (page <= 1) {
            base
        } else {
            buildPagedUrl(base, page)
        }

        Log.d(JET_TAG, "getMainPage: $url")

        val document = runCatching {
            app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return newHomePageResponse(
            request.name,
            emptyList(),
            false,
        )

        val items = document.select(
            ".row-cols-2 .col .film-card, .film-card"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = detectNextPage(document, page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (base.endsWith("/")) {
            return base.trimEnd('/') + "/page/$page/"
        }
        return "$base/page/$page/"
    }

    private fun detectNextPage(document: org.jsoup.nodes.Document, page: Int): Boolean {
        if (document.select("a.next, a[rel=next], .pagination a").any {
                it.text().contains("Son", true) || it.text().contains("»")
            }) {
            return true
        }
        return page < 50 && document.select("article.movie").isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(
            ".film-title, .card-title, h3 a, h3, h2 a, h2"
        )?.text()?.trim()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val href = fixUrlNull(
            selectFirst(".card-body a[href], a[href]")?.attr("href")
        ) ?: return null

        val poster = sequenceOf(
            selectFirst(".film-poster img")?.attr("data-src"),
            selectFirst(".film-poster img")?.attr("data-lazy-src"),
            selectFirst(".film-poster img")?.attr("src"),
            selectFirst("img")?.attr("data-src"),
            selectFirst("img")?.attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrlNull)

        val score = selectFirst(
            ".rating-year-imdb .text-warning, .rating, .imdb"
        )?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = try {
            val doc = app.post(
                "$mainUrl/filmara.php",
                headers = pageHeaders,
                referer = "$mainUrl/",
                data = mapOf("s" to q),
            ).document

            buildList {
                for (element in doc.select(
                    "article.movie, article[class*=movie], .movie-item, .film-item, .movie, .film"
                )) {
                    element.toSearchResult()?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (results.isNotEmpty()) return results

        val encoded = URLEncoder.encode(q, "UTF-8")
        return try {
            val doc = app.get(
                "$mainUrl/?s=$encoded",
                headers = pageHeaders,
                referer = "$mainUrl/",
            ).document

            buildList {
                for (element in doc.select(
                    "article.movie, article[class*=movie], .movie-item, .film-item, .movie, .film"
                )) {
                    element.toSearchResult()?.let { add(it) }
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
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
            document.selectFirst("section.movie-exp div.movie-exp-title")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("h2")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = sequenceOf(
            document.selectFirst("section.movie-exp img")?.attr("data-src"),
            document.selectFirst("section.movie-exp img")?.attr("src"),
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("article img")?.attr("data-src"),
            document.selectFirst("article img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val text = document.text()

        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(
                document.selectFirst(
                    ".yap, .movie-info, .film-info, .movie-exp"
                )?.text().orEmpty().ifBlank { text }
            )?.value?.toIntOrNull()

        val description = sequenceOf(
            document.selectFirst("section.movie-exp p.aciklama")?.text(),
            document.selectFirst(".aciklama")?.text(),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val rating = sequenceOf(
            document.selectFirst("section.movie-exp div.imdb_puan span")?.text(),
            document.selectFirst(".imdb_puan")?.text(),
            document.selectFirst(".puan_1")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.let {
            Regex("""\d+(?:[\.,]\d+)?""").find(it)?.value?.replace(',', '.')
        }

        val tags = document.select(
            "section.movie-exp div.catss a, .catss a, a[href*='/dizi/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        val actors = document.select(
            "section.movie-exp div.oyuncu, .oyuncu, .actor, .actors .name"
        ).mapNotNull { actor ->
            val actorName = sequenceOf(
                actor.selectFirst("div.name")?.text(),
                actor.selectFirst("a")?.text(),
                actor.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return@mapNotNull null

            val actorImage = sequenceOf(
                actor.selectFirst("img")?.attr("data-src"),
                actor.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

            Actor(actorName, actorImage)
        }.distinctBy { it.name }

        val trailer = document.select(
            "iframe[src], iframe[data-src], a[href], a[data-src], img[data-src]"
        ).mapNotNull { element ->
            val raw = sequenceOf(
                element.attr("data-src"),
                element.attr("src"),
                element.attr("href"),
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

            fixUrlNull(raw)?.takeIf {
                it.contains("youtube.com", true) || it.contains("youtu.be", true)
            }
        }.firstOrNull()

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.year = year
            this.plot = description
            this.score = Score.from10(rating)
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(JET_TAG, "loadLinks: $data")

        val document = runCatching {
            app.get(
                data,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return false

        val iframeUrls = linkedSetOf<String>()

        fun addUrl(raw: String?) {
            if (raw.isNullOrBlank()) return
            fixUrlNull(
                raw.trim()
                    .replace("\\/", "/")
                    .replace("&amp;", "&")
                    .replace("&#038;", "&")
            )?.let { iframeUrls += it }
        }

        // Ana oynatıcı
        document.selectFirst("div#movie iframe")?.let {
            addUrl(
                it.attr("data-src").ifBlank {
                    it.attr("data-litespeed-src").ifBlank {
                        it.attr("data").ifBlank { it.attr("src") }
                    }
                }
            )
        }

        // Kaynak/part düğmeleri
        document.select(
            "div.film_part a, .film_part a, .sources a, .source a"
        ).forEach { sourceLink ->
            val sourceName = sourceLink.text().trim()
            if (sourceName.contains("fragman", true)) return@forEach

            val href = fixUrlNull(sourceLink.attr("href")) ?: return@forEach
            val sourceDoc = runCatching {
                app.get(
                    href,
                    headers = pageHeaders,
                    referer = data,
                    allowRedirects = true,
                ).document
            }.getOrNull() ?: return@forEach

            val sourceIframe = sourceDoc.selectFirst("div#movie iframe")?.let {
                sequenceOf(
                    it.attr("data-src"),
                    it.attr("data-litespeed-src"),
                    it.attr("data"),
                    it.attr("src"),
                ).firstOrNull { value -> value.isNotBlank() }
            }

            if (!sourceIframe.isNullOrBlank()) {
                addUrl(sourceIframe)
            } else {
                sourceDoc.select("div#movie p a, #movie p a").forEach {
                    addUrl(it.attr("href"))
                }
            }
        }

        // Doğrudan sayfada bırakılmış embed/player bağlantıları
        document.select(
            "iframe[src], iframe[data-src], video[src], source[src]"
        ).forEach {
            val raw = sequenceOf(
                it.attr("data-src"),
                it.attr("src"),
            ).firstOrNull { value -> value.isNotBlank() }
            if (
                raw?.contains("youtube", true) != true &&
                raw?.contains("youtu.be", true) != true
            ) {
                addUrl(raw)
            }
        }

        if (iframeUrls.isEmpty()) {
            Log.d(JET_TAG, "Player bulunamadı")
            return false
        }

        var found = false

        for (iframe in iframeUrls) {
            Log.d(JET_TAG, "Player deneniyor: $iframe")

            try {
                // JetTV benzeri oynatıcı
                if (iframe.contains("jetv.xyz", true)) {
                    val playerDoc = app.get(
                        iframe,
                        headers = pageHeaders,
                        referer = data,
                        allowRedirects = true,
                    ).document

                    val script = playerDoc.select("script").firstOrNull {
                        it.data().contains("\"sources\"", true)
                    }?.data().orEmpty()

                    val sourceBlock = script
                        .substringAfter("\"sources\"", "")
                        .substringAfter("[", "")
                        .substringBefore("]", "")

                    Regex(
                        """\{[^{}]*["']file["']\s*:\s*["']([^"']+)["'][^{}]*["']label["']\s*:\s*["']([^"']+)["'][^{}]*}""",
                        RegexOption.IGNORE_CASE
                    ).findAll(sourceBlock).forEach { match ->
                        val url = match.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")

                        val label = match.groupValues[2]
                        callback(
                            newExtractorLink(
                                source = "JetTV",
                                name = "JetTV - $label",
                                url = url,
                                type = if (url.contains(".mp4", true))
                                    ExtractorLinkType.VIDEO
                                else
                                    ExtractorLinkType.M3U8,
                            ) {
                                quality = getQualityFromName(label)
                                referer = iframe
                                headers = mapOf(
                                    "User-Agent" to JET_UA,
                                    "Referer" to iframe,
                                )
                            }
                        )
                        found = true
                    }

                    if (!found) {
                        runCatching {
                            loadExtractor(
                                iframe,
                                data,
                                subtitleCallback,
                                callback,
                            )
                        }
                    }
                    continue
                }

                // D2RS API tabanlı oynatıcı
                if (iframe.contains("d2rs.com", true)) {
                    val playerHtml = app.get(
                        iframe,
                        headers = pageHeaders,
                        referer = data,
                        allowRedirects = true,
                    ).text

                    val q = Regex(
                        """form\.append\(["']q["'],\s*["']([^"']+)["']""",
                        RegexOption.IGNORE_CASE
                    ).find(playerHtml)?.groupValues?.getOrNull(1)

                    if (!q.isNullOrBlank()) {
                        val api = runCatching {
                            app.post(
                                "https://d2rs.com/zeus/api.php",
                                headers = pageHeaders,
                                referer = iframe,
                                data = mapOf("q" to q),
                            ).text
                        }.getOrNull().orEmpty()

                        Regex(
                            """["']file["']\s*:\s*["']([^"']+)["'].*?["']label["']\s*:\s*["']([^"']+)["'].*?["']type["']\s*:\s*["']([^"']+)["']""",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                        ).findAll(api).forEach { match ->
                            val file = match.groupValues[1]
                            val label = match.groupValues[2]
                            val sourceType = match.groupValues[3]

                            callback(
                                newExtractorLink(
                                    source = "D2RS",
                                    name = "D2RS - $label",
                                    url = if (file.startsWith("http", true)) {
                                        file
                                    } else {
                                        "https://d2rs.com/zeus/$file"
                                    },
                                    type = if (sourceType.contains("mp4", true))
                                        ExtractorLinkType.VIDEO
                                    else
                                        ExtractorLinkType.M3U8,
                                ) {
                                    quality = getQualityFromName(label)
                                    referer = iframe
                                    headers = mapOf(
                                        "User-Agent" to JET_UA,
                                        "Referer" to iframe,
                                    )
                                }
                            )
                            found = true
                        }
                    }
                    continue
                }

                // JS ile paketlenmiş videoları açmayı dene
                val playerHtml = runCatching {
                    app.get(
                        iframe,
                        headers = pageHeaders,
                        referer = data,
                        allowRedirects = true,
                    ).text
                }.getOrNull().orEmpty()

                Regex(
                    """https?://[^"'<>\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(playerHtml)
                    .map { it.value.replace("\\/", "/") }
                    .distinct()
                    .forEach { media ->
                        val type = when {
                            media.contains(".mpd", true) -> ExtractorLinkType.DASH
                            media.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                            else -> ExtractorLinkType.VIDEO
                        }

                        callback(
                            newExtractorLink(
                                source = "JetFilmizle",
                                name = "JetFilmizle",
                                url = media,
                                type = type,
                            ) {
                                quality = Qualities.Unknown.value
                                referer = iframe
                                headers = mapOf(
                                    "User-Agent" to JET_UA,
                                    "Referer" to iframe,
                                )
                            }
                        )
                        found = true
                    }

                val unpackScript = playerHtml.lines().firstOrNull {
                    it.contains("eval(function(p,a,c,k,e,d)", true) ||
                        it.contains("eval(function(p,a,c,k,e,r)", true)
                }

                if (!found && !unpackScript.isNullOrBlank()) {
                    val unpacked = runCatching {
                        JsUnpacker(unpackScript).unpack()
                    }.getOrNull().orEmpty()

                    Regex(
                        """https?://[^"'<>\s]+\.(?:m3u8|mp4|mpd)(?:\?[^"'<>\s]*)?""",
                        RegexOption.IGNORE_CASE
                    ).findAll(unpacked)
                        .map { it.value.replace("\\/", "/") }
                        .distinct()
                        .forEach { media ->
                            val type = when {
                                media.contains(".mpd", true) -> ExtractorLinkType.DASH
                                media.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }

                            callback(
                                newExtractorLink(
                                    source = "JetFilmizle",
                                    name = "JetFilmizle",
                                    url = media,
                                    type = type,
                                ) {
                                    quality = Qualities.Unknown.value
                                    referer = iframe
                                    headers = mapOf(
                                        "User-Agent" to JET_UA,
                                        "Referer" to iframe,
                                    )
                                }
                            )
                            found = true
                        }
                }

                // Son aşamada CloudStream'in yerleşik extractor'ı
                if (!found) {
                    runCatching {
                        loadExtractor(
                            iframe,
                            data,
                            subtitleCallback,
                            callback,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(JET_TAG, "Kaynak hatası: $iframe", e)
            }
        }

        // Subtitle
        document.select("track[src], track[kind='subtitles']").forEach { track ->
            val sub = fixUrlNull(track.attr("src")) ?: return@forEach
            subtitleCallback(SubtitleFile("Türkçe", sub))
        }

        return found
    }
}
