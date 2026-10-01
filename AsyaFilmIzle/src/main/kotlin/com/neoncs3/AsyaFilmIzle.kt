package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

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

    private val chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

    private fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val value = url.trim()
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> mainUrl + value
            else -> "$mainUrl/$value"
        }
    }

    private fun posterFrom(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return absolute(
            img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("src") }
        )
    }

    private fun scoreFrom(text: String?): Score? {
        if (text.isNullOrBlank()) return null
        val value = Regex(
            """(?<!\d)(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)(?!\d)"""
        ).findAll(text)
            .mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }
            .firstOrNull { it in 0.0..10.0 }
            ?: return null
        return Score.from10(value.toString())
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text)?.value?.toIntOrNull()
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

        val direct = Regex(
            """(?ix)
            sezon[-\s_]*(\d+)[-/\s_]*(?:bolum|bölüm)[-\s_]*(\d+)
            |
            (\d+)\.?[-\s]*(?:sezon|season)[-\s]*(\d+)\.?[-\s]*(?:bolum|bölüm|episode)[-\s]*(\d+)
            |
            (?:s|season)[-\s]*(\d+)[-_]?e[-\s]*(\d+)
            """
        ).find(source)

        if (direct != null) {
            val g = direct.groupValues
            val pairs = listOf(
                g.getOrNull(1)?.toIntOrNull() to g.getOrNull(2)?.toIntOrNull(),
                g.getOrNull(3)?.toIntOrNull() to g.getOrNull(4)?.toIntOrNull(),
                g.getOrNull(6)?.toIntOrNull() to g.getOrNull(7)?.toIntOrNull()
            )
            pairs.firstOrNull { it.first != null && it.second != null }?.let {
                return it.first!! to it.second!!
            }
        }

        Regex("""(?i)(?:sezon|season)[-\s]*(\d+).{0,25}?(?:bolum|bölüm|episode)[-\s]*(\d+)""")
            .find(source)?.let {
                val season = it.groupValues[1].toIntOrNull() ?: 1
                val episode = it.groupValues[2].toIntOrNull() ?: 1
                return season to episode
            }

        return null
    }

    private fun parseSearchCard(card: Element): SearchResponse? {
        val link = card.selectFirst("a[href*='/dizi/'], a[href*='/film/']")
            ?: card.selectFirst("a[href]")
            ?: return null

        val url = absolute(link.attr("href")) ?: return null
        if (!url.contains("/dizi/", true) && !url.contains("/film/", true)) return null

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
        return if (url.contains("/film/", true)) {
            newMovieSearchResponse(title, url) {
                posterUrl = poster
                this.score = score
            }
        } else {
            newTvSeriesSearchResponse(title, url) {
                posterUrl = poster
                this.score = score
            }
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { link ->
            val href = absolute(link.attr("href")) ?: return@forEach
            if (!href.contains("/dizi/", true) && !href.contains("/film/", true)) return@forEach
            if (!seen.add(href)) return@forEach

            val title = link.selectFirst("img")?.attr("alt")
                ?.trim()
                ?.removeSuffix(" izle")
                ?.removeSuffix(" İzle")
                ?.takeIf { it.isNotBlank() }
                ?: link.selectFirst(".title,.name,h1,h2,h3,h4")?.text()?.trim()
                ?: link.text().trim()

            if (title.isBlank()) return@forEach

            val card = link.closest("article, .item, .post, .card, .film, .dizi, li, div") ?: link
            val poster = posterFrom(card)
            val score = scoreFrom(card.text())

            results += if (href.contains("/film/", true)) {
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
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> "${request.data}page/$page/"
            else -> "${request.data}/page/$page/"
        }

        val response = runCatching {
            app.get(pageUrl, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null
        val results = parseListing(response.document)
        return newHomePageResponse(request, results, hasNext = results.isNotEmpty())
    }

    private suspend fun searchPage(candidate: String): List<SearchResponse> {
        val response = runCatching {
            app.get(candidate, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return emptyList()

        if (!response.isSuccessful) return emptyList()
        return parseListing(response.document)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (page > 1) return null

        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val candidates = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/arama/?q=$encoded",
            "$mainUrl/ara/?q=$encoded",
            "$mainUrl/?q=$encoded"
        )

        val seen = HashSet<String>()
        val merged = ArrayList<SearchResponse>()

        for (url in candidates) {
            searchPage(url).forEach { item ->
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
        return document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot {
                it.equals("Kore Dizileri", true) ||
                    it.equals("Çin Dizileri", true) ||
                    it.equals("Japon Dizileri", true) ||
                    it.equals("Tayland Dizileri", true)
            }
    }

    private fun parseTrailer(document: Document): String? {
        val direct = document.select("a").firstOrNull {
            it.text().contains("Fragman", true) ||
                it.attr("href").contains("youtube.com", true) ||
                it.attr("href").contains("youtu.be", true)
        }?.attr("href")?.trim()

        if (!direct.isNullOrBlank() && (direct.startsWith("http", true) || direct.startsWith("//"))) {
            return absolute(direct)
        }

        val dataLink = document.select("[data-trailer], [data-trailer-url], [data-video], [data-url]")
            .flatMap { element ->
                listOf("data-trailer", "data-trailer-url", "data-video", "data-url")
                    .mapNotNull { key -> element.attr(key).takeIf(String::isNotBlank) }
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

            val runtimeText = link.closest("article, .item, .episode, li, div")?.text()
                ?.takeIf { it != link.text() }
                ?: link.text()

            result += newEpisode(href) {
                name = "Bölüm $episode"
                this.season = season
                this.episode = episode
                posterUrl = poster
                runTime = getDurationFromString(runtimeText)
            }
        }

        return result.sortedWith(
            compareBy<Episode> { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = runCatching {
            app.get(url, headers = siteHeaders, referer = mainUrl, allowRedirects = true)
        }.getOrNull() ?: return null

        if (!response.isSuccessful) return null

        val document = response.document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.removeSuffix(" izle")
            ?.removeSuffix(" İzle")
            ?: return null

        val pageText = document.text()
        val poster = document.select("img[src], img[data-src], img[data-lazy-src], img[data-original]")
            .mapNotNull {
                absolute(
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-lazy-src") }
                        .ifBlank { it.attr("data-original") }
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

        if (url.contains("/dizi/", true)) {
            val episodes = parseEpisodes(document, poster)
            return newTvSeriesLoadResponse(
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
        }

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
            this.duration = getDurationFromString(pageText)
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

    private fun normalizeUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&")
            .trim()
    }

    private fun looksLikeMedia(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".m3u", true)
    }

    private fun mediaUrls(text: String): List<String> {
        val input = normalizeUrl(text)
        val result = LinkedHashSet<String>()

        val patterns = listOf(
            Regex("""https?://[^\"'\\s<>]+?\.m3u8(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?\.mp4(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\\s<>]+?\.m3u(?:\?[^\"'\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""[\"'](https?://[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(input).forEach { match ->
                val url = normalizeUrl(match.groupValues.last())
                if (looksLikeMedia(url)) result.add(url)
            }
        }

        return result.toList()
    }

    private fun extractQuotedUrls(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""(?is)(?:file|src|source|url|hls|stream|playlist|video|streamUrl|playUrl)[\\s:=]+[\"']([^\"']+)[\"']"""),
            Regex("""(?is)[\"'](?:file|src|source|url|hls|stream|playlist|video|streamUrl|playUrl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?is)(?:fetch|\\$\\.get|\\$\\.ajax|axios\\.get|XMLHttpRequest\\.open)\\s*\\(\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?is)(?:m3u8|mp4|m3u)[^\"'<>\\s]{0,600}""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.findAll(text).forEach { match ->
                val candidate = normalizeUrl(match.groupValues.last())
                if (candidate.startsWith("http://") || candidate.startsWith("https://") ||
                    candidate.startsWith("/") || candidate.startsWith("//")) {
                    result.add(candidate)
                }
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
                val decoded = normalizeUrl(base64Decode(token))
                mediaUrls(decoded).forEach(result::add)
                extractQuotedUrls(decoded).forEach(result::add)
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    result.add(decoded)
                }
            }
        }

        return result.toList()
    }

    private fun decodeUrlCandidates(text: String): List<String> {
        val result = LinkedHashSet<String>()
        val encodedRegex = Regex("""(?:https?%3A%2F%2F|https?://)[^\"'\\s<>]{20,1000}""", RegexOption.IGNORE_CASE)

        encodedRegex.findAll(text).forEach { match ->
            runCatching {
                val decoded = normalizeUrl(URLDecoder.decode(match.value, "UTF-8"))
                if (looksLikeMedia(decoded)) result.add(decoded)
            }
        }

        return result.toList()
    }

    private fun absoluteFor(base: String, value: String): String? {
        val cleaned = normalizeUrl(value)
        if (cleaned.isBlank()) return null
        if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) return cleaned
        if (cleaned.startsWith("//")) return "https:$cleaned"

        return runCatching { URI(base).resolve(cleaned).toString() }.getOrNull()
    }

    private fun iframeHeaders(referer: String): Map<String, String> {
        val origin = runCatching {
            val uri = URI(referer)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)

        return mapOf(
            "User-Agent" to chromeUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
            "Origin" to origin,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
    }

    private fun playerHeaders(referer: String): Map<String, String> {
        val origin = runCatching {
            val uri = URI(referer)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)

        return mapOf(
            "User-Agent" to chromeUserAgent,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    private suspend fun addDirectMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaUrl = normalizeUrl(url)
        if (!looksLikeMedia(mediaUrl)) return

        callback(
            newExtractorLink(
                source = "AsyaFilmİzle",
                name = when {
                    mediaUrl.contains(".m3u8", true) -> "Katre HLS"
                    mediaUrl.contains(".m3u", true) -> "Katre M3U"
                    else -> "Katre MP4"
                },
                url = mediaUrl,
                type = if (mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".m3u", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                quality = qualityFrom(mediaUrl)
                headers = playerHeaders(referer)
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
                ?: return@runCatching

            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }
                .getOrElse { encoded }
                .let { value ->
                    runCatching { base64Decode(value) }.getOrElse { value }
                }

            if (decoded.startsWith("http", true)) {
                subtitleCallback(newSubtitleFile("Türkçe", decoded))
            }
        }
    }

    private fun findIframeUrls(document: Document, baseUrl: String): List<String> {
        val result = LinkedHashSet<String>()

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]").forEach { iframe ->
            val raw = iframe.attr("src")
                .ifBlank { iframe.attr("data-src") }
                .ifBlank { iframe.attr("data-lazy-src") }

            absoluteFor(baseUrl, raw)?.let { result.add(it) }
        }

        return result.toList()
    }

    private suspend fun extractFromResponseText(
        baseUrl: String,
        body: String,
        iframeReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val candidates = LinkedHashSet<String>()
        candidates.addAll(mediaUrls(body))
        candidates.addAll(decodeBase64Candidates(body))
        candidates.addAll(decodeUrlCandidates(body))
        candidates.addAll(extractQuotedUrls(body))

        for (raw in candidates) {
            val url = absoluteFor(baseUrl, raw) ?: continue
            if (!looksLikeMedia(url)) continue
            addDirectMedia(url, iframeReferer, callback)
            found = true
        }

        val document = runCatching { org.jsoup.Jsoup.parse(body, baseUrl) }.getOrNull()
        if (document != null) {
            document.select("track[src], source[src], video[src]")
                .mapNotNull { absoluteFor(baseUrl, it.attr("src")) }
                .filter { it.contains(".vtt", true) || it.contains(".srt", true) }
                .distinct()
                .forEach { sub ->
                    try {
                        subtitleCallback(newSubtitleFile("Türkçe", sub))
                    } catch (_: Throwable) { }
                }
        }

        return found
    }

    private suspend fun processIframe(
        iframe: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
        visited: MutableSet<String> = LinkedHashSet()
    ): Boolean {
        if (depth > 2) return false
        if (!visited.add(iframe)) return false

        var found = false
        extractSubtitleFromUrl(iframe, subtitleCallback)

        // First let installed CloudStream extractors try the player URL.
        runCatching {
            if (loadExtractor(iframe, pageUrl, subtitleCallback, callback)) {
                found = true
            }
        }

        fun responseHeaders(): Map<String, String> = iframeHeaders(pageUrl)

        val iframeResponse = runCatching {
            app.get(
                iframe,
                headers = responseHeaders(),
                referer = pageUrl,
                allowRedirects = true
            )
        }.getOrNull()

        // Some anti-hotlink setups reject the first header profile. Retry without
        // the browser fetch metadata while keeping the real page Referer.
        val finalResponse = if (iframeResponse == null || !iframeResponse.isSuccessful) {
            val fallbackHeaders = listOf(
                mapOf(
                    "User-Agent" to chromeUserAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to pageUrl,
                    "Origin" to runCatching {
                        val uri = URI(pageUrl)
                        "${uri.scheme}://${uri.host}"
                    }.getOrDefault(mainUrl)
                ),
                mapOf(
                    "User-Agent" to chromeUserAgent,
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to pageUrl
                )
            )

            fallbackHeaders.firstNotNullOfOrNull { headers ->
                runCatching {
                    app.get(
                        iframe,
                        headers = headers,
                        referer = pageUrl,
                        allowRedirects = true
                    ).takeIf { it.isSuccessful }
                }.getOrNull()
            }
        } else {
            iframeResponse
        }

        if (finalResponse == null) return found

        if (finalResponse.isSuccessful) {
            val body = finalResponse.text

            if (extractFromResponseText(
                    iframe,
                    body,
                    iframe,
                    subtitleCallback,
                    callback
                )
            ) {
                found = true
            }

            val document = finalResponse.document

            // Follow nested player iframes, including KSD/Katre style embeds.
            findIframeUrls(document, iframe)
                .filterNot { it == iframe }
                .take(8)
                .forEach { nested ->
                    if (processIframe(
                            nested,
                            iframe,
                            subtitleCallback,
                            callback,
                            depth + 1,
                            visited
                        )
                    ) {
                        found = true
                    }
                }

            // Inspect external JS files. A number of Katre versions put the media
            // endpoint in player JavaScript rather than directly in the HTML.
            val scriptUrls = document.select("script[src]")
                .mapNotNull { absoluteFor(iframe, it.attr("src")) }
                .distinct()
                .take(12)

            for (scriptUrl in scriptUrls) {
                val jsResponse = runCatching {
                    app.get(
                        scriptUrl,
                        headers = iframeHeaders(iframe),
                        referer = iframe,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!jsResponse.isSuccessful) continue

                if (extractFromResponseText(
                        scriptUrl,
                        jsResponse.text,
                        iframe,
                        subtitleCallback,
                        callback
                    )
                ) {
                    found = true
                }
            }

            // Follow obvious player API endpoints discovered in the HTML/JS.
            val apiCandidates = extractQuotedUrls(body)
                .mapNotNull { absoluteFor(iframe, it) }
                .filter {
                    !looksLikeMedia(it) &&
                        !it.contains(".js", true) &&
                        (
                            it.contains(".php", true) ||
                                it.contains("/api/", true) ||
                                it.contains("ajax", true) ||
                                it.contains("source", true) ||
                                it.contains("video", true)
                            )
                }
                .distinct()
                .take(8)

            for (api in apiCandidates) {
                val apiResponse = runCatching {
                    app.get(
                        api,
                        headers = iframeHeaders(iframe),
                        referer = iframe,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!apiResponse.isSuccessful) continue

                if (extractFromResponseText(
                        api,
                        apiResponse.text,
                        iframe,
                        subtitleCallback,
                        callback
                    )
                ) {
                    found = true
                }
            }
        }

        return found
    }

    private fun isPossiblePlayer(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("embed") ||
            lower.contains("player") ||
            lower.contains("rplayer") ||
            lower.contains("dzembed") ||
            lower.contains("ksdpictures.site") ||
            lower.contains("katre") ||
            lower.contains("yabancidizim.com")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val iframes = LinkedHashSet<String>()

        // CloudStream may already pass the player URL directly.
        if (isPossiblePlayer(data)) {
            iframes.add(data)
        } else {
            val pageResponse = runCatching {
                app.get(
                    data,
                    headers = siteHeaders,
                    referer = mainUrl,
                    allowRedirects = true
                )
            }.getOrNull()

            if (pageResponse != null && pageResponse.isSuccessful) {
                val document = pageResponse.document

                findIframeUrls(document, data)
                    .filter(::isPossiblePlayer)
                    .forEach(iframes::add)

                // Also allow non-standard iframe attributes and player URLs hidden
                // in page scripts/data attributes.
                document.select("[data-src], [data-lazy-src], [data-embed], [data-player], [data-video], [data-url]")
                    .forEach { element ->
                        val values = listOf(
                            element.attr("data-src"),
                            element.attr("data-lazy-src"),
                            element.attr("data-embed"),
                            element.attr("data-player"),
                            element.attr("data-video"),
                            element.attr("data-url")
                        )

                        values.mapNotNull { absoluteFor(data, it) }
                            .filter(::isPossiblePlayer)
                            .forEach(iframes::add)
                    }

                if (extractFromResponseText(
                        data,
                        pageResponse.text,
                        data,
                        subtitleCallback,
                        callback
                    )
                ) {
                    found = true
                }
            }
        }

        // Known current AsyaFilmIzle player form. This is deliberately added as a
        // fallback only when the page exposes a KSD embed so a future player change
        // does not disable all native/external extractors.
        if (data.contains("ksdpictures.site", true) || data.contains("dzembed.php", true)) {
            iframes.add(data)
        }

        for (iframe in iframes) {
            if (processIframe(
                    iframe = iframe,
                    pageUrl = data,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            ) {
                found = true
            }
        }

        return found
    }
}
