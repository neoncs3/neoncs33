package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import kotlin.math.roundToInt

class AsyaFilmIzle : MainAPI() {

    override var mainUrl = "https://asyafilmizle.com"
    override var name = "AsyaFilmİzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    override val mainPage = mainPageOf(
        "https://asyafilmizle.com/diziler/" to "Yeni Diziler",
        "https://asyafilmizle.com/filmler/" to "Filmler",
        "https://asyafilmizle.com/tur/kore/" to "Kore Dizileri"
    )

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }
    }

    private fun posterFrom(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return absolute(
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.attr("data-original") }
        )
    }

    private fun scoreFrom(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        val value = Regex("""(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\d)""")
            .findAll(text)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }
            ?: return null
        return Score.from10(value.toString())
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text)
            ?.value
            ?.toIntOrNull()
    }

    private fun qualityFrom(url: String): Int {
        return when {
            Regex("""(?i)(2160|4k)""").containsMatchIn(url) -> Qualities.P2160.value
            Regex("""(?i)1080""").containsMatchIn(url) -> Qualities.P1080.value
            Regex("""(?i)720""").containsMatchIn(url) -> Qualities.P720.value
            Regex("""(?i)480""").containsMatchIn(url) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractEpisodeNumbers(text: String, url: String): Pair<Int, Int>? {
        val source = "$text $url"
        val match = Regex(
            """(?i)(?:sezon[-\s_]*(\d+)[-/\s]*(?:bolum|bölüm)[-\s_]*(\d+)|(\d+)\.?[-\s]*(?:sezon|season)[-\s]*(\d+)\.?[-\s]*(?:bolum|bölüm|episode)[-\s]*(\d+)|(?:s|season)[-\s]*(\d+)[-_]?e[-\s]*(\d+))"""
        ).find(source)

        if (match != null) {
            val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            when {
                values.size >= 2 -> return values[0] to values[1]
            }
        }

        val compact = Regex("""(?i)(?:sezon|season)[-\s]*(\d+).{0,25}?(?:bolum|bölüm|episode)[-\s]*(\d+)""")
            .find(source)
        if (compact != null) {
            return (compact.groupValues[1].toIntOrNull() ?: 1) to
                (compact.groupValues[2].toIntOrNull() ?: 1)
        }

        return null
    }

    private fun parseSearchCard(card: Element): SearchResponse? {
        val link = card.selectFirst("a[href*='/dizi/'], a[href*='/film/']")
            ?: card.selectFirst("a[href]")
            ?: return null

        val url = absolute(link.attr("href")) ?: return null
        if (!url.contains("/dizi/") && !url.contains("/film/")) return null

        val title = link.selectFirst("img")?.attr("alt")
            ?.trim()
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("h1,h2,h3,h4,.title,.name")?.text()?.trim()
            ?: link.text().trim()
        if (title.isBlank()) return null

        val poster = posterFrom(card)
        val score = scoreFrom(card.text())
        val type = if (url.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, url) {
                this.posterUrl = poster
                this.score = score
            }
            else -> newTvSeriesSearchResponse(title, url) {
                this.posterUrl = poster
                this.score = score
            }
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { link ->
            val href = absolute(link.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach
            if (!seen.add(href)) return@forEach

            val title = link.selectFirst("img")?.attr("alt")
                ?.trim()
                ?.removeSuffix(" izle")
                ?.removeSuffix(" İzle")
                ?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("h1,h2,h3,h4,.title,.name")?.text()?.trim()
                ?: link.text().trim()

            if (title.isBlank()) return@forEach

            val card = link.closest("article, .item, .post, .card, li, div")
            val poster = posterFrom(card ?: link)
            val score = scoreFrom((card ?: link).text())
            val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

            val item = if (type == TvType.Movie) {
                newMovieSearchResponse(title, href) {
                    posterUrl = poster
                    this.score = score
                }
            } else {
                newTvSeriesSearchResponse(title, href) {
                    posterUrl = poster
                    this.score = score
                }
            }
            results.add(item)
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> "${request.data}page/$page/"
            else -> "${request.data}/page/$page/"
        }

        val response = app.get(
            pageUrl,
            headers = siteHeaders,
            referer = mainUrl
        )

        if (!response.isSuccessful) return null

        val results = parseListing(response.document)
        return newHomePageResponse(
            request,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    private suspend fun searchPage(query: String, candidate: String): List<SearchResponse> {
        val response = runCatching {
            app.get(candidate, headers = siteHeaders, referer = mainUrl)
        }.getOrNull() ?: return emptyList()

        if (!response.isSuccessful) return emptyList()
        return parseListing(response.document)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null

        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/ara/?q=$encoded",
            "$mainUrl/?q=$encoded"
        )

        val seen = HashSet<String>()
        val merged = ArrayList<SearchResponse>()

        for (url in candidates) {
            searchPage(query, url).forEach { item ->
                if (seen.add(item.url)) merged.add(item)
            }
            if (merged.isNotEmpty()) break
        }

        return newSearchResponseList(merged, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    private fun parseGenres(document: Document): List<String> {
        return document.select("a").mapNotNull { a ->
            val href = a.attr("href")
            val text = a.text().trim()
            if (href.contains("/tur/") && text.isNotBlank()) text else null
        }.distinct().filter {
            !it.equals("Kore Dizileri", true) &&
                !it.equals("Çin Dizileri", true) &&
                !it.equals("Japon Dizileri", true) &&
                !it.equals("Tayland Dizileri", true)
        }
    }

    private fun parseTrailer(document: Document): String? {
        val trailerLink = document.select("a").firstOrNull {
            it.text().contains("Fragman", true) ||
                it.attr("href").contains("youtube", true) ||
                it.attr("href").contains("youtu.be", true)
        }

        val direct = trailerLink?.attr("href").takeIf { !it.isNullOrBlank() }
        if (!direct.isNullOrBlank() && (direct.startsWith("http") || direct.startsWith("//"))) {
            return absolute(direct)
        }

        val dataLink = document.select("[data-trailer], [data-trailer-url], [data-video], [data-url]")
            .mapNotNull {
                listOf("data-trailer", "data-trailer-url", "data-video", "data-url")
                    .firstNotNullOfOrNull { key -> it.attr(key).takeIf(String::isNotBlank) }
            }
            .firstOrNull()

        return absolute(dataLink)
    }

    private fun collectActors(document: Document): List<String> {
        return document.select("a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun collectDirectors(document: Document): List<String> {
        return document.select("a[href*='/yonetmen/'], a[href*='/director/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun parseEpisodes(document: Document, poster: String?): List<Episode> {
        val result = ArrayList<Episode>()
        val seen = HashSet<String>()

        document.select("a[href*='/bolum/']").forEach { link ->
            val href = absolute(link.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach

            val text = "${link.text()} ${link.attr("title")} $href"
            val numbers = extractEpisodeNumbers(text, href) ?: return@forEach
            val season = numbers.first
            val episode = numbers.second

            val runtimeText = link.closest("article, .item, .episode, li, div")
                ?.text()
                ?.takeIf { it != link.text() }
                ?: link.text()

            result.add(
                newEpisode(href) {
                    name = "Bölüm $episode"
                    this.season = season
                    this.episode = episode
                    posterUrl = poster
                    runTime = getDurationFromString(runtimeText)
                }
            )
        }

        return result.sortedWith(
            compareBy<Episode> { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = siteHeaders,
            referer = mainUrl
        )
        if (!response.isSuccessful) return null

        val document = response.document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?: return null

        val pageText = document.text()
        val poster = document.select("img[src], img[data-src], img[data-lazy-src]")
            .mapNotNull {
                absolute(
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-lazy-src") }
                )
            }
            .firstOrNull { it.contains("image.tmdb.org", true) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let(::absolute)

        val year = yearFrom(document.selectFirst("h1")?.parent()?.parent()?.text() ?: pageText)
        val score = scoreFrom(pageText)
        val plot = document.selectFirst(
            "meta[property='og:description'], .description, .plot, .summary"
        )?.let {
            it.attr("content").takeIf(String::isNotBlank) ?: it.text()
        }?.trim()

        val genres = parseGenres(document)
        val trailer = parseTrailer(document)
        val actors = collectActors(document)
        val directors = collectDirectors(document)

        if (url.contains("/dizi/")) {
            val episodes = parseEpisodes(document, poster)

            val response = newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score
                tags = genres
                if (trailer != null) addTrailer(trailer)
                addActors(actors)

                if (directors.isNotEmpty()) {
                    this.plot = buildString {
                        if (!plot.isNullOrBlank()) append(plot)
                        if (isNotEmpty()) append("\n\n")
                        append("Yönetmen: ")
                        append(directors.joinToString(", "))
                    }
                }
            }

            return response
        }

        val duration = getDurationFromString(pageText)

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            this.duration = duration
            tags = genres
            if (trailer != null) addTrailer(trailer)
            addActors(actors)

            if (directors.isNotEmpty()) {
                this.plot = buildString {
                    if (!plot.isNullOrBlank()) append(plot)
                    if (isNotEmpty()) append("\n\n")
                    append("Yönetmen: ")
                    append(directors.joinToString(", "))
                }
            }
        }
    }

    private fun mediaUrls(text: String): List<String> {
        val patterns = listOf(
            Regex("""https?://[^"'\\s<>]+?\.m3u8(?:\?[^"'\\s<>]+)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\s<>]+?\.mp4(?:\?[^"'\\s<>]+)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\s<>]+?\.m3u(?:\?[^"'\\s<>]+)?""", RegexOption.IGNORE_CASE)
        )

        val result = LinkedHashSet<String>()
        for (pattern in patterns) {
            pattern.findAll(text).forEach {
                result.add(it.value.replace("\\/", "/"))
            }
        }
        return result.toList()
    }

    private fun decodeBase64Candidates(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val tokenRegex = Regex("""(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{40,}={0,2})(?![A-Za-z0-9+/=])""")

        tokenRegex.findAll(text).forEach { match ->
            val token = match.groupValues[1]
            runCatching {
                val decoded = base64Decode(token)
                mediaUrls(decoded).forEach(result::add)
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    result.add(decoded.trim())
                }
            }
        }

        return result.toList()
    }

    private suspend fun addDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = "AsyaFilmİzle",
                name = if (url.contains(".m3u8", true)) "Katre HLS" else "Katre MP4",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = qualityFrom(url)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to URI(referer).let { "${it.scheme}://${it.host}" }
                )
            }
        )
    }

    private suspend fun extractSubtitleFromUrl(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        runCatching {
            val uri = URI(iframeUrl)
            val query = uri.rawQuery.orEmpty()

            val encoded = query.split("&")
                .mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].equals("sub", true)) parts[1] else null
                }
                .firstOrNull()

            if (!encoded.isNullOrBlank()) {
                val decoded = runCatching { base64Decode(encoded) }.getOrElse { encoded }
                if (decoded.startsWith("http")) {
                    subtitleCallback(
                        newSubtitleFile(
                            "Türkçe",
                            decoded
                        )
                    )
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageResponse = runCatching {
            app.get(
                data,
                headers = siteHeaders,
                referer = mainUrl
            )
        }.getOrNull()

        if (pageResponse == null || !pageResponse.isSuccessful) return false

        val document = pageResponse.document
        val iframe = document.selectFirst("iframe[src], iframe[data-src]")
            ?.let {
                absolute(
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                )
            }

        var found = false

        if (iframe != null) {
            extractSubtitleFromUrl(iframe, subtitleCallback)

            val iframeResponse = runCatching {
                app.get(
                    iframe,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data,
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                    ),
                    referer = data,
                    allowRedirects = true
                )
            }.getOrNull()

            if (iframeResponse != null && iframeResponse.isSuccessful) {
                val body = iframeResponse.text
                val directUrls = mediaUrls(body) + decodeBase64Candidates(body)

                for (media in directUrls.distinct()) {
                    addDirectMedia(media, iframe, callback)
                    found = true
                }

                val subtitleUrls = iframeResponse.document
                    .select("track[src], source[src], video[src]")
                    .mapNotNull { absolute(it.attr("src")) }
                    .filter { it.contains(".vtt", true) || it.contains(".srt", true) }
                    .distinct()

                for (sub in subtitleUrls) {
                    subtitleCallback(newSubtitleFile("Türkçe", sub))
                }
            }

            // Try a native CloudStream extractor too. This covers providers whose
            // embed is not readable as plain HTML but is supported by a known extractor.
            runCatching {
                if (loadExtractor(
                        iframe,
                        data,
                        subtitleCallback,
                        callback
                    )
                ) {
                    found = true
                }
            }
        }

        // Some pages expose a direct source without using an iframe.
        val directPageUrls = mediaUrls(pageResponse.text) + decodeBase64Candidates(pageResponse.text)
        for (media in directPageUrls.distinct()) {
            addDirectMedia(media, data, callback)
            found = true
        }

        return found
    }
}
