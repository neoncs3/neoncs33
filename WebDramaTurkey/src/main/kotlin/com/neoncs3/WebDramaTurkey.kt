package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val WDT_TAG = "WebDramaTurkey"

class WebDramaTurkey : MainAPI() {

    override var mainUrl = "https://webdramaturkey2.com"
    override var name = "WebDramaTurkey"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.Anime,
        TvType.Others
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Bölümler",
        "$mainUrl/diziler?page=" to "Diziler",
        "$mainUrl/filmler?page=" to "Filmler",
        "$mainUrl/programlar?page=" to "Programlar",
        "$mainUrl/animeler?page=" to "Animeler",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%221%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Kore Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%222%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Çin Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%223%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Japon Dizileri",
        "$mainUrl/tur/romantik?page=" to "Romantik",
        "$mainUrl/tur/dram?page=" to "Dram",
        "$mainUrl/tur/fantastik?page=" to "Fantastik",
        "$mainUrl/tur/komedi?page=" to "Komedi",
        "$mainUrl/tur/gerilim?page=" to "Gerilim",
        "$mainUrl/tur/aksiyon?page=" to "Aksiyon",
        "$mainUrl/tur/gizem?page=" to "Gizem",
        "$mainUrl/tur/tarihi?page=" to "Tarihi",
    )

    private val pageHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = if (data == "$mainUrl/") {
            data
        } else {
            "$data${page.coerceAtLeast(1)}"
        }

        Log.d(WDT_TAG, "getMainPage: $url")

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

        val items = if (data == "$mainUrl/") {
            document.select("div.col.sonyuklemeler")
                .mapNotNull { it.toLatestEpisodeResult() }
        } else {
            document.select("div.col")
                .mapNotNull { it.toMainPageResult() }
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = data != "$mainUrl/" && items.isNotEmpty() && page < 100,
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst(
            "a.list-title, a[href*='/dizi/'], a[href*='/film/'], " +
                "a[href*='/anime/'], a[href*='/program/']"
        ) ?: return null

        val title = selectFirst("a.list-title")?.text()?.trim()
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("h2, h3, h4")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(link.attr("href").replace("&amp;", "&")) ?: return null
        if (!isContentUrl(href)) return null

        return makeSearchResponse(title, href, posterFromCard(this))
    }

    private fun Element.toLatestEpisodeResult(): SearchResponse? {
        val titleBase = selectFirst("div.list-title")?.text()?.trim()
            ?: selectFirst("h2, h3, h4")?.text()?.trim()
            ?: return null

        val episodeText = selectFirst("div.list-category")?.text()?.trim().orEmpty()
        val link = selectFirst("a[href]") ?: return null
        val originalHref = link.attr("href").trim()
        if (originalHref.isBlank()) return null

        val cleanHref = originalHref.replace(
            Regex("""/\d+-sezon/\d+-bolum/?$"""),
            "/",
        )
        val href = fixUrlNull(cleanHref) ?: return null
        if (!isContentUrl(href)) return null

        val title = if (episodeText.isNotBlank()) "$titleBase - $episodeText" else titleBase
        return makeSearchResponse(title, href, episodePoster(this))
    }

    private fun posterFromCard(element: Element): String? {
        val cover = element.selectFirst("div.media.media-cover")
        val raw = sequenceOf(
            cover?.attr("data-src"),
            cover?.attr("data-background"),
            cover?.attr("data-image"),
            cover?.selectFirst("img")?.attr("data-src"),
            cover?.selectFirst("img")?.attr("src"),
            element.selectFirst("img")?.attr("data-src"),
            element.selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        if (raw.isNotBlank()) return fixUrlNull(raw)

        val style = cover?.attr("style").orEmpty()
        return Regex(
            """url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)"""
        ).find(style)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)
    }

    private fun episodePoster(element: Element): String? {
        val ep = element.selectFirst("div.media.media-episode")
        val raw = sequenceOf(
            ep?.attr("data-src"),
            ep?.attr("data-background"),
            ep?.selectFirst("img")?.attr("data-src"),
            ep?.selectFirst("img")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }

        if (!raw.isNullOrBlank()) return fixUrlNull(raw)

        val style = ep?.attr("style").orEmpty()
        return Regex(
            """url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)"""
        ).find(style)?.groupValues?.getOrNull(1)
            ?.replace("&quot;", "")
            ?.let(::fixUrlNull)
    }

    private fun makeSearchResponse(
        title: String,
        href: String,
        poster: String?,
    ): SearchResponse {
        val type = when {
            href.contains("/film/", true) -> TvType.Movie
            href.contains("/anime/", true) -> TvType.Anime
            href.contains("/program/", true) -> TvType.Others
            else -> TvType.AsianDrama
        }

        return newMovieSearchResponse(title, href, type) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val url = "$mainUrl/arama/${URLEncoder.encode(q, "UTF-8")}"
        val document = runCatching {
            app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return emptyList()

        return document
            .select("div.tab-pane:not(#actors) div.col, div.col")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst(
            "a[href*='/dizi/'], a[href*='/film/'], " +
                "a[href*='/anime/'], a[href*='/program/']"
        ) ?: return null

        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!isContentUrl(href)) return null

        val title = selectFirst("a.list-title")?.text()?.trim()
            ?: selectFirst("h2, h3, h4, span.title, .title")?.text()?.trim()
            ?: link.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        return makeSearchResponse(title, href, posterFromCard(this))
    }

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

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("İzle").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(
            listOf(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("div.app-detail-poster img")?.attr("data-src"),
                document.selectFirst("div.app-detail-poster img")?.attr("src"),
                document.selectFirst("div.media-cover img")?.attr("data-src"),
                document.selectFirst("div.media-cover img")?.attr("src"),
                document.selectFirst("div.cover img")?.attr("data-src"),
                document.selectFirst("div.cover img")?.attr("src"),
                document.selectFirst("div.poster img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }
        )

        val plot = document.selectFirst(
            "div.app-detail-overview, div.desc, div.description, div.plot, p.desc"
        )?.text()?.trim()

        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val yearText = document.selectFirst(
            "div.app-detail-info, div.info, div.metadata, .detail-info"
        )?.text().orEmpty()

        val year = Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(yearText)?.value?.toIntOrNull()
            ?: Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
                .find(document.text())?.value?.toIntOrNull()

        val statusText = document.selectFirst(
            "span[class*='status'], div[class*='status'], span[class*='durum']"
        )?.text()?.trim()

        val duration = document.selectFirst(
            "span[class*='time'], div[class*='duration'], span[class*='sure']"
        )?.text()?.trim()?.let { getDurationFromString(it) }

        val actors = document.select(
            "a[href*='/oyuncu/'], div.actor, div.oyuncu, .actors a[href]"
        ).mapNotNull { element ->
            val img = element.selectFirst("img")
            val actorName = sequenceOf(
                element.selectFirst("div.name, span.name")?.text()?.trim(),
                img?.attr("alt")?.trim(),
                element.text().trim(),
            ).firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null

            Actor(
                actorName,
                fixUrlNull(img?.attr("data-src")?.ifBlank { img.attr("src") })
            )
        }.distinctBy { it.name }

        val trailer = document.select("iframe[src], iframe[data-src], a[href]")
            .mapNotNull { element ->
                val raw = element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }

                fixUrlNull(raw)?.takeIf {
                    it.contains("youtube.com", true) || it.contains("youtu.be", true)
                }
            }.firstOrNull()

        val isMovie = pageUrl.contains("/film/", true)
        val isAnime = pageUrl.contains("/anime/", true)
        val isProgram = pageUrl.contains("/program/", true)

        if (isMovie) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.contentRating = statusText
                addActors(actors)
                addTrailer(trailer)
            }
        }

        val episodeElements = document.select(
            "a[href*='-sezon/'][href*='-bolum'], a[href*='/sezon/'][href*='/bolum']"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null

            val match = Regex("""/(\d+)-sezon/(\d+)-bolum""").find(href)
                ?: Regex("""/(\d+)/sezon/(\d+)/bolum""").find(href)

            val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

            val name = element.text().trim().ifBlank {
                if (season != null && episode != null) {
                    "$season. Sezon $episode. Bölüm"
                } else {
                    "Bölüm"
                }
            }

            newEpisode(href) {
                this.name = name
                this.season = season ?: 1
                this.episode = episode
            }
        }.distinctBy { it.data }

        if (isAnime) {
            return newAnimeLoadResponse(title, pageUrl, TvType.Anime) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.contentRating = statusText
                addEpisodes(DubStatus.Subbed, episodes)
                addActors(actors)
                addTrailer(trailer)
            }
        }

        if (isProgram) {
            return newTvSeriesLoadResponse(title, pageUrl, TvType.Others, episodes) {
                posterUrl = poster
                posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
                this.tags = tags
                this.contentRating = statusText
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.AsianDrama, episodes) {
            posterUrl = poster
            posterHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")
            this.plot = plot
            this.year = year
            this.tags = tags
            this.contentRating = statusText
            this.duration = duration
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
        Log.d(WDT_TAG, "loadLinks: $data")

        val document = runCatching {
            app.get(
                data,
                headers = pageHeaders,
                referer = "$mainUrl/",
                allowRedirects = true,
            ).document
        }.getOrNull() ?: return false

        var found = false

        val buttons = document
            .select("button[data-embed], a[data-embed], [data-embed]")
            .distinctBy { it.attr("data-embed") }

        for (button in buttons) {
            val embedId = button.attr("data-embed").trim()
            if (embedId.isBlank()) continue

            val sourceName = button.selectFirst("span.name")?.text()?.trim()
                ?: button.text().trim().takeIf { it.isNotBlank() }
                ?: "Alternatif"

            val ajaxResponse = runCatching {
                app.post(
                    "$mainUrl/ajax/embed",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data,
                        "Accept" to "*/*",
                    ),
                    referer = data,
                    data = mapOf("id" to embedId),
                )
            }.getOrNull() ?: continue

            if (!ajaxResponse.isSuccessful) continue
            val ajaxText = ajaxResponse.text

            val videoPhpUrl = Regex(
                """(?:src|url)\s*=\s*["'](https?://[^"']*video\.php\?[^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(ajaxText)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """(?:src|url)\s*=\s*["']([^"']*video\.php\?[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(ajaxText)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)

            if (videoPhpUrl.isNullOrBlank()) {
                val fallbackIframe = Regex(
                    """(?:src|iframe)[^"']*["'](https?://[^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(ajaxText)?.groupValues?.getOrNull(1)

                if (!fallbackIframe.isNullOrBlank()) {
                    found = resolveIframe(
                        fallbackIframe,
                        data,
                        sourceName,
                        subtitleCallback,
                        callback,
                    ) || found
                }
                continue
            }

            val videoResponse = runCatching {
                app.get(
                    videoPhpUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data,
                    ),
                    referer = data,
                    allowRedirects = true,
                )
            }.getOrNull() ?: continue

            if (!videoResponse.isSuccessful) continue

            val videoHtml = videoResponse.text

            val iframeUrl = Regex(
                """<iframe[^>]+src=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(videoHtml)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)

            if (!iframeUrl.isNullOrBlank()) {
                found = resolveIframe(
                    iframeUrl,
                    videoPhpUrl,
                    sourceName,
                    subtitleCallback,
                    callback,
                ) || found
            }

            Regex(
                """["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(videoHtml).forEach { match ->
                val stream = match.groupValues[1]
                val type = when {
                    stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $sourceName",
                        url = stream,
                        type = type,
                    ) {
                        quality = Qualities.Unknown.value
                        referer = videoPhpUrl
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to videoPhpUrl,
                        )
                    }
                )
                found = true
            }

            Regex(
                """["'](?:subtitle|subtitles|captions?)["']\s*[:=]\s*["']([^"']+(?:\.vtt|\.srt)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(videoHtml).forEach { match ->
                fixUrlNull(match.groupValues[1])?.let { subtitle ->
                    runCatching {
                        subtitleCallback(SubtitleFile("Türkçe", subtitle))
                    }
                }
            }
        }

        if (!found) {
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                val iframeUrl = fixUrlNull(raw) ?: return@forEach

                found = resolveIframe(
                    iframeUrl,
                    data,
                    "Alternatif",
                    subtitleCallback,
                    callback,
                ) || found
            }
        }

        return found
    }

    private suspend fun resolveIframe(
        iframeUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (iframeUrl.contains("dtpasn.asia", true) ||
            iframeUrl.contains("dtpasn.com", true)
        ) {
            WebDramaTurkeyExtractor().getUrl(
                iframeUrl,
                referer,
                subtitleCallback,
                callback,
            )
            return true
        }

        val loaded = runCatching {
            loadExtractor(
                iframeUrl,
                referer,
                subtitleCallback,
                callback,
            )
        }.getOrDefault(false)

        if (loaded) return true

        return runCatching {
            val iframeResponse = app.get(
                iframeUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                ),
                referer = referer,
                allowRedirects = true,
            )

            if (!iframeResponse.isSuccessful) return@runCatching false
            val html = iframeResponse.text

            Regex(
                """["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val stream = match.groupValues[1]
                val type = when {
                    stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                    stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $sourceName",
                        url = stream,
                        type = type,
                    ) {
                        quality = Qualities.Unknown.value
                        referer = iframeUrl
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to iframeUrl,
                        )
                    }
                )
            }

            Regex(
                """["'](?:subtitle|subtitles|captions?)["']\s*[:=]\s*["']([^"']+(?:\.vtt|\.srt)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                fixUrlNull(match.groupValues[1])?.let { subtitle ->
                    runCatching {
                        subtitleCallback(SubtitleFile("Türkçe", subtitle))
                    }
                }
            }

            html.contains(".m3u8", true) ||
                html.contains(".mp4", true) ||
                html.contains(".mpd", true)
        }.getOrDefault(false)
    }

    private fun isContentUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("$mainUrl/dizi/") ||
            value.contains("$mainUrl/film/") ||
            value.contains("$mainUrl/anime/") ||
            value.contains("$mainUrl/program/")
    }
}
