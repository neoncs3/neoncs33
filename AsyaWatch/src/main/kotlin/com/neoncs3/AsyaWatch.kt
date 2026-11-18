package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Calendar

/**
 * AsyaWatch provider.
 *
 * Catalogues and detail metadata are read from the site's public JSON payloads.
 * Playback uses the iframe URLs exposed by the content response and CloudStream's
 * normal extractor registry. It deliberately does not attempt to bypass encrypted
 * player challenges or access controls.
 */
class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/",
        )

    override val mainPage = mainPageOf(
        "" to "Yeni Eklenen Filmler",
        "49" to "Aile Film",
        "44" to "Animasyon Film",
        "59" to "Aksiyon Film",
        "66" to "Bilim Kurgu Film",
        "48" to "Dram Film",
        "61" to "Fantastik Film",
        "68" to "Gerilim Film",
        "51" to "Gizem Film",
        "63" to "Korku Film",
        "45" to "Komedi Film",
        "65" to "Romantik Film",
        "46" to "Suç Film",
        "69" to "Savaş Film",
        "78" to "Western Film",
        "" to "Yeni Eklenen Diziler",
        "15" to "Aile Dizi",
        "17" to "Animasyon Dizi",
        "9" to "Aksiyon Dizi",
        "5" to "Bilim Kurgu Dizi",
        "2" to "Dram Dizi",
        "12" to "Fantastik Dizi",
        "18" to "Gerilim Dizi",
        "3" to "Gizem Dizi",
        "8" to "Korku Dizi",
        "4" to "Komedi Dizi",
        "7" to "Romantik Dizi",
        "1" to "Suç Dizi",
        "26" to "Savaş Dizi",
        "11" to "Western Dizi",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val endpoint = if (request.name.contains("Dizi", ignoreCase = true)) {
            "findSeries"
        } else {
            "findMovies"
        }

        val category = URLEncoder.encode(request.data, "UTF-8")
        val url = "$mainUrl/api/bg/$endpoint" +
            "?releaseYearStart=1900" +
            "&releaseYearEnd=$currentYear" +
            "&imdbPointMin=1&imdbPointMax=10" +
            "&categoryIdsComma=$category" +
            "&countryIdsComma=" +
            "&orderType=date_desc&languageId=-1" +
            "&currentPage=$page&currentPageCount=12" +
            "&queryStr=&categorySlugsComma=&countryCodesComma="

        val body = runCatching {
            app.post(url, headers = requestHeaders, referer = "$mainUrl/").text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val result = decodeApiResponse(body)?.optJSONArray("result")
            ?: return newHomePageResponse(request.name, emptyList())

        val items = (0 until result.length()).mapNotNull { index ->
            result.optJSONObject(index)?.toSearchResponseFromCatalogue()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.length < 2) return emptyList()

        val encoded = URLEncoder.encode(term, "UTF-8")
        val body = runCatching {
            app.post(
                "$mainUrl/api/bg/searchcontent?searchterm=$encoded",
                headers = requestHeaders,
                referer = "$mainUrl/",
            ).text
        }.getOrNull() ?: return emptyList()

        val payload = decodeApiResponse(body) ?: return emptyList()
        if (payload.optBoolean("state", true).not()) return emptyList()

        val result = payload.optJSONArray("result") ?: return emptyList()
        return (0 until result.length()).mapNotNull { index ->
            val item = result.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("object_name").cleanValue()
                ?: return@mapNotNull null
            val href = siteUrl(item.optString("used_slug"))
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (href.contains("/seri-filmler/", ignoreCase = true)) {
                return@mapNotNull null
            }

            val poster = posterUrl(item.optString("object_poster_url"))
            val type = item.optString("used_type")
            val score = item.optDouble("imdb_point").takeIf { it.isFinite() }

            if (type.contains("movie", ignoreCase = true)) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                    score?.let { this.score = Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    score?.let { this.score = Score.from10(it) }
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val response = runCatching {
            app.get(url, headers = requestHeaders, referer = "$mainUrl/")
        }.getOrElse {
            throw ErrorLoadingException("AsyaWatch sayfası açılamadı")
        }

        val document = response.document
        val root = secureDataFrom(document)
        val item = root?.optJSONObject("contentItem")

        if (item == null) {
            return loadFromHtml(url, document)
        }

        val originalTitle = item.optString("original_title").cleanValue()
        val cultureTitle = item.optString("culture_title").cleanValue()
        val title = when {
            originalTitle.isNullOrBlank() -> pageTitle(document, url)
            cultureTitle.isNullOrBlank() || cultureTitle == originalTitle -> originalTitle
            else -> "$originalTitle - $cultureTitle"
        } ?: throw ErrorLoadingException("AsyaWatch başlığı okunamadı")

        val poster = posterUrl(item.optString("poster_url"))
        val plot = item.optString("description").cleanValue()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.cleanValue()
        val year = item.optInt("release_year").takeIf { it > 0 }
        val rating = item.optDouble("imdb_point").takeIf { it.isFinite() }
        val duration = item.optInt("total_minutes").takeIf { it > 0 }
        val tags = item.optString("categories")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val related = root.optJSONObject("RelatedResults")
            ?: root.optJSONObject("relatedResults")
        val actors = parseActors(related)
        val trailer = parseTrailer(related)
        val episodes = parseEpisodes(related, poster)

        val isSeries = url.contains("/dizi/", ignoreCase = true) || episodes.isNotEmpty()
        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                rating?.let { this.score = Score.from10(it) }
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                rating?.let { this.score = Score.from10(it) }
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = requestHeaders, referer = "$mainUrl/")
        }.getOrNull() ?: return false

        val document = response.document
        var found = false
        val root = secureDataFrom(document)
        val related = root?.optJSONObject("RelatedResults")
            ?: root?.optJSONObject("relatedResults")

        if (related != null) {
            val sourceEntries = mutableListOf<JSONObject>()

            related.optJSONObject("getEpisodeSources")
                ?.optJSONArray("result")
                ?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { sourceEntries.add(it) }
                    }
                }

            val movieParts = related.optJSONObject("getMoviePartsById")
                ?.optJSONArray("result")
            if (movieParts != null) {
                for (index in 0 until movieParts.length()) {
                    val partId = movieParts.optJSONObject(index)?.optInt("id") ?: 0
                    if (partId <= 0) continue
                    related.optJSONObject("getMoviePartSourcesById_$partId")
                        ?.optJSONArray("result")
                        ?.let { sources ->
                            for (sourceIndex in 0 until sources.length()) {
                                sources.optJSONObject(sourceIndex)?.let {
                                    sourceEntries.add(it)
                                }
                            }
                        }
                }
            }

            for (entry in sourceEntries) {
                val sourceHtml = entry.optString("source_content").cleanValue() ?: continue
                val sourceDoc = Jsoup.parse(sourceHtml, data)
                val candidates = sourceDoc.select(
                    "iframe[src], iframe[data-src], video[src], video source[src], source[src]"
                ).mapNotNull { element ->
                    sequenceOf(
                        element.attr("src"),
                        element.attr("data-src"),
                    ).firstOrNull { it.isNotBlank() }?.let { siteUrl(it, data) }
                }.distinct()

                val fallbackMedia = MEDIA_URL.findAll(sourceHtml)
                    .map { cleanExtractedUrl(it.value) }
                    .filter { it.isNotBlank() }
                    .toList()

                for (candidate in (candidates + fallbackMedia).distinct()) {
                    if (resolveCandidate(candidate, data, subtitleCallback, callback)) {
                        found = true
                    }
                }
            }
        }

        // Some pages expose a normal iframe/video element without a secureData block.
        val pageCandidates = document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src]"
        ).mapNotNull { element ->
            sequenceOf(element.attr("src"), element.attr("data-src"))
                .firstOrNull { it.isNotBlank() }
                ?.let { siteUrl(it, data) }
        }

        for (candidate in (pageCandidates + MEDIA_URL.findAll(response.text)
                .map { cleanExtractedUrl(it.value) }
                .filter { it.isNotBlank() })
            .distinct()
        ) {
            if (resolveCandidate(candidate, data, subtitleCallback, callback)) {
                found = true
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitle = siteUrl(raw, data)
            if (subtitle.isNotBlank()) {
                runCatching {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = track.attr("label").ifBlank {
                                track.attr("srclang").ifBlank { "Türkçe" }
                            },
                            url = subtitle,
                        ) {
                            headers = requestHeaders + ("Referer" to data)
                        },
                    )
                }
            }
        }

        return found
    }

    private fun JSONObject.toSearchResponseFromCatalogue(): SearchResponse? {
        val title = optString("original_title").cleanValue() ?: return null
        val href = siteUrl(optString("used_slug")).takeIf { it.isNotBlank() } ?: return null
        val poster = posterUrl(optString("poster_url"))
        val score = optDouble("imdb_point").takeIf { it.isFinite() }

        return if (href.contains("/dizi/", ignoreCase = true)) {
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

    private fun decodeApiResponse(body: String): JSONObject? {
        val wrapper = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val encoded = wrapper.optString("response").cleanValue() ?: return null
        return decodeBase64Object(encoded)
    }

    private fun secureDataFrom(document: Document): JSONObject? {
        val script = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val nextData = runCatching { JSONObject(script) }.getOrNull() ?: return null
        val secureData = nextData.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optString("secureData")
            ?.cleanValue()
            ?: return null

        return decodeBase64Object(secureData)
    }

    private fun decodeBase64Object(encoded: String): JSONObject? {
        val decoded = runCatching { base64Decode(encoded.trim().trim('"')) }
            .getOrNull() ?: return null
        val utf8Repaired = String(
            decoded.toByteArray(Charsets.ISO_8859_1),
            Charsets.UTF_8,
        )

        return sequenceOf(decoded, utf8Repaired)
            .mapNotNull { candidate ->
                runCatching { JSONObject(candidate.trim()) }.getOrNull()
            }
            .firstOrNull()
    }

    private fun parseEpisodes(
        related: JSONObject?,
        poster: String?,
    ): List<Episode> {
        val seasons = related
            ?.optJSONObject("getSerieSeasonAndEpisodes")
            ?.optJSONArray("result")
            ?: return emptyList()

        val episodes = mutableListOf<Episode>()
        for (seasonIndex in 0 until seasons.length()) {
            val season = seasons.optJSONObject(seasonIndex) ?: continue
            val seasonNumber = season.optInt("season_no").takeIf { it > 0 }
            val seasonEpisodes = season.optJSONArray("episodes") ?: continue

            for (episodeIndex in 0 until seasonEpisodes.length()) {
                val episodeData = seasonEpisodes.optJSONObject(episodeIndex) ?: continue
                val href = siteUrl(episodeData.optString("used_slug"))
                    .takeIf { it.isNotBlank() } ?: continue
                val episodeNumber = episodeData.optInt("episode_no").takeIf { it > 0 }
                val episodeName = episodeData.optString("episode_text").cleanValue()
                    ?: episodeNumber?.let { "Bölüm $it" }
                    ?: "Bölüm"

                episodes.add(newEpisode(href) {
                    name = episodeName
                    season = seasonNumber
                    episode = episodeNumber
                    posterUrl = poster
                })
            }
        }
        return episodes.distinctBy { it.data }
    }

    private fun parseActors(related: JSONObject?): List<Actor> {
        val cast = related
            ?.optJSONObject("getMovieCastsById")
            ?.optJSONArray("result")
            ?: return emptyList()

        return (0 until cast.length()).mapNotNull { index ->
            val item = cast.optJSONObject(index) ?: return@mapNotNull null
            val actorName = item.optString("name").cleanValue()
                ?: return@mapNotNull null
            val image = posterUrl(item.optString("cast_image"))
            Actor(actorName, image)
        }.distinctBy { it.name }
    }

    private fun parseTrailer(related: JSONObject?): String? {
        val trailers = related
            ?.optJSONObject("getContentTrailers")
            ?.optJSONArray("result")
            ?: return null
        if (trailers.length() == 0) return null

        val trailer = trailers.optJSONObject(0) ?: return null
        return trailer.optString("raw_url").cleanValue()
            ?: trailer.optString("raw").cleanValue()
    }

    private fun loadFromHtml(url: String, document: Document): LoadResponse {
        val title = pageTitle(document, url)
            ?: throw ErrorLoadingException("AsyaWatch başlığı okunamadı")
        val poster = posterUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty(),
        )
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.cleanValue()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanValue()

        val episodes = document.select("a.simr[href]").mapNotNull { anchor ->
            val href = siteUrl(anchor.attr("href"), url).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = anchor.text().cleanValue() ?: href.substringAfterLast("/")
            val season = Regex("(?i)sezon-(\\d+)").find(href)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = Regex("(?i)bolum-(\\d+)").find(href)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                name = label
                this.season = season
                this.episode = episode
                posterUrl = poster
            }
        }.distinctBy { it.data }

        return if (url.contains("/dizi/", ignoreCase = true) || episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
            }
        }
    }

    private fun pageTitle(document: Document, fallbackUrl: String): String? {
        return sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("title")?.text(),
            fallbackUrl.substringAfterLast('/').replace('-', ' '),
        ).mapNotNull { it.cleanValue() }.firstOrNull()
    }

    private fun posterUrl(raw: String?): String? {
        val value = raw.cleanValue()
            ?.replace(
                "images-macellan-online.cdn.ampproject.org/i/s/",
                "",
            )
            ?: return null
        if (BAD_IMAGE.containsMatchIn(value)) return null
        return fixUrlNull(value)
    }

    private fun siteUrl(raw: String?, base: String = mainUrl): String {
        val value = raw.cleanValue()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?: return ""
        return fixUrlNull(value) ?: runCatching {
            java.net.URI(base).resolve(value).toString()
        }.getOrDefault("")
    }

    private fun String?.cleanValue(): String? {
        return this
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true) &&
                    !it.equals("undefined", ignoreCase = true)
            }
    }

    private fun cleanExtractedUrl(raw: String): String {
        return raw
            .replace("\\/", "/")
            .trim()
            .trimEnd(')', ']', '}', ',', ';')
            .let { siteUrl(it) }
    }

    private suspend fun resolveCandidate(
        candidate: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = siteUrl(candidate, referer)
        if (url.isBlank()) return false

        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val directType = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> null
        }

        if (directType != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "AsyaWatch",
                    url = url,
                    type = directType,
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = requestHeaders + ("Referer" to referer)
                },
            )
            return true
        }

        var emitted = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                callback(link)
                emitted = true
            }
        }
        return emitted
    }

    private companion object {
        val MEDIA_URL = Regex(
            """(?i)https?://[^"'<>\\s]+?\\.(?:m3u8|mpd|mp4|webm)(?:\\?[^"'<>\\s]*)?"""
        )
        val BAD_IMAGE = Regex(
            """(?i)^(?:data:|about:|javascript:)|/(?:pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\\.(?:png|gif|jpe?g|svg|webp)(?:[?#]|$)"""
        )
    }
}
