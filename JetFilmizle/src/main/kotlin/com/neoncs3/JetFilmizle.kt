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
import java.net.URLEncoder

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
        val url = "${request.data}$page"

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

        val hasNext = items.isNotEmpty()
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
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val score = selectFirst(
            ".rating-year-imdb .text-warning, .rating, .imdb"
        )?.text()?.trim()

        return newMovieSearchResponse(
            title,
            href,
            if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie,
        ) {
            posterUrl = poster
            score?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val response = runCatching {
            app.get(
                "$mainUrl/arama-json?q=$encoded",
                headers = pageHeaders,
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: return emptyList()

        return try {
            val body = response.substringAfter("<body>", response).substringBefore("</body>", response)
            val json = org.json.JSONObject(body)
            val results = json.optJSONArray("results") ?: return emptyList()

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title").trim()
                    val rawUrl = item.optString("url").trim()
                    if (title.isBlank() || rawUrl.isBlank()) continue

                    val fullUrl = fixUrlNull(
                        if (rawUrl.startsWith("http")) rawUrl else "$mainUrl/$rawUrl"
                    ) ?: continue

                    val poster = fixUrlNull(item.optString("poster").trim())
                    val year = item.optString("year").toIntOrNull()
                    val rating = item.optString("rating")
                    val type = if (item.optString("type").equals("dizi", true)) TvType.TvSeries else TvType.Movie

                    add(newMovieSearchResponse(title, fullUrl, type) {
                        posterUrl = poster
                        this.year = year
                        this.score = Score.from10(rating)
                    })
                }
            }.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(JET_TAG, "search hata: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val pageUrl = fixUrlNull(url) ?: return null

        val document = runCatching {
            app.get(pageUrl, headers = pageHeaders, referer = mainUrl + "/", allowRedirects = true).document
        }.getOrNull() ?: return null

        val title = sequenceOf(
            document.selectFirst(".col-12 .film-title")?.text(),
            document.selectFirst(".film-title")?.text(),
            document.selectFirst("h1")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.substringBefore(" izle")?.trim() ?: return null

        val poster = sequenceOf(
            document.selectFirst(".film-bilgileri-section img")?.attr("data-src"),
            document.selectFirst(".film-bilgileri-section img")?.attr("src"),
            document.selectFirst(".film-poster img")?.attr("data-src"),
            document.selectFirst(".film-poster img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)

        val infoText = document.selectFirst(".film-bilgileri-section, .film-info, .detail-item")?.text().orEmpty()
        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""").find(infoText)?.value?.toIntOrNull()
        val rating = document.selectFirst(".film-ratings-container b, .rating-year-imdb .text-warning, .rating, .imdb")?.text()?.let { value ->
            Regex("""\d+(?:[\.,]\d+)?""").find(value)?.value?.replace(",", ".")
        }
        val description = sequenceOf(
            document.selectFirst(".description-text p:nth-child(2)")?.text(),
            document.selectFirst(".description-text p:nth-child(1)")?.text(),
            document.selectFirst(".description, .plot")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select(".categories-container-details a, .categories-container a, .catss a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select(".oyuncular-section .actors-grid .col, .oyuncu").mapNotNull { actorElement ->
            val actorName = sequenceOf(
                actorElement.selectFirst(".text-decoration-none")?.text(),
                actorElement.selectFirst(".name")?.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return@mapNotNull null
            val actorImage = sequenceOf(
                actorElement.selectFirst("img")?.attr("data-src"),
                actorElement.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let(::fixUrlNull)
            Actor(actorName, actorImage)
        }.distinctBy { it.name }

        if (pageUrl.contains("/dizi/", true)) {
            val episodes = document.select("button.episode-btn[data-player-type], button.episode-btn")
                .mapNotNull { button ->
                    val season = button.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                    val episode = button.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
                    val sourceIndex = button.attr("data-source-index")
                    val playerType = button.attr("data-player-type")
                    if (sourceIndex.isBlank() || playerType.isBlank()) return@mapNotNull null

                    val episodeUrl = pageUrl + "?sezon=" + season + "&bolum=" + episode + "&index=" + sourceIndex + "&type=" + playerType

                    newEpisode(episodeUrl) {
                        name = season.toString() + ".Sezon " + episode + ".Bölüm"
                        this.season = season
                        this.episode = episode
                        posterUrl = poster
                    }
                }
                .distinctBy { it.season.toString() + "-" + it.episode.toString() + "-" + it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            Log.d(JET_TAG, "Dizi bölümleri: " + episodes.size)

            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }

        return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
            posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            addActors(actors)
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

        val filmId = document.selectFirst(
            "input[name='film_id'], input[name='filmId']"
        )?.attr("value")

        if (filmId.isNullOrBlank()) {
            Log.e(JET_TAG, "film_id bulunamadı: $data")
            return false
        }

        val episodeMode = data.contains("?sezon=") && data.contains("&bolum=")

        suspend fun requestPlayer(
            sourceIndex: String,
            playerType: String,
            label: String,
        ) {
            if (sourceIndex.isBlank() || playerType.isBlank()) return

            try {
                val response = app.post(
                    "$mainUrl/jetplayer",
                    data = mapOf(
                        "film_id" to filmId,
                        "source_index" to sourceIndex,
                        "player_type" to playerType,
                    ),
                    headers = mapOf(
                        "User-Agent" to JET_UA,
                        "Origin" to mainUrl,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    referer = data,
                    allowRedirects = true,
                ).document

                val iframeSrc = response.selectFirst("iframe")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != "about:blank" }

                if (iframeSrc.isNullOrBlank()) {
                    Log.d(JET_TAG, "iframe bulunamadı: $label")
                    return
                }

                val playerUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                Log.d(JET_TAG, "player: $label -> $playerUrl")

                loadExtractor(
                    playerUrl,
                    data,
                    subtitleCallback,
                    callback,
                )
            } catch (e: Exception) {
                Log.e(JET_TAG, "player hatası: $label -> ${e.message}")
            }
        }

        if (episodeMode) {
            val sourceIndex = data.substringAfter("index=").substringBefore("&")
            val playerType = data.substringAfter("type=").substringBefore("&").ifBlank { "dublaj" }
            val season = data.substringAfter("sezon=").substringBefore("&")
            val episode = data.substringAfter("bolum=").substringBefore("&")

            requestPlayer(
                sourceIndex = sourceIndex,
                playerType = playerType,
                label = "S\${season}B\${episode}",
            )
            return true
        }

        if (data.contains("/dizi/")) {
            document.select("button.episode-btn[data-player-type]").firstOrNull()?.let { first ->
                requestPlayer(
                    sourceIndex = first.attr("data-source-index"),
                    playerType = first.attr("data-player-type"),
                    label = first.text().trim().ifBlank { "Dizi" },
                )
            }
            return true
        }

        val sourceButtons = document.select("button.player-source-btn")
        if (sourceButtons.isNotEmpty()) {
            val button = sourceButtons.firstOrNull { it.attr("data-source-index").isNotBlank() }

            if (button != null) {
                requestPlayer(
                    sourceIndex = button.attr("data-source-index"),
                    playerType = button.attr("data-player-type").ifBlank { "dublaj" },
                    label = button.text().trim().ifBlank { "Kaynak" },
                )
            }

            return true
        }

        document.select("#movie iframe[src], #movie iframe[data-src], iframe[src], iframe[data-src]").forEach { iframe ->
            val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
            if (raw.isBlank()) return@forEach

            val playerUrl = if (raw.startsWith("//")) "https:$raw" else fixUrlNull(raw) ?: return@forEach
            runCatching {
                loadExtractor(playerUrl, data, subtitleCallback, callback)
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subUrl = fixUrlNull(raw) ?: return@forEach
            runCatching {
                subtitleCallback(SubtitleFile("Türkçe", subUrl))
            }
        }

        return true
    }
}
