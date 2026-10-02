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
        "$mainUrl/animeler?page=" to "Animeler",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%221%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Kore Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%222%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Çin Dizileri",
        "$mainUrl/diziler?filter=%7B%22country%22%3A%223%22%2C%22sorting%22%3A%22newest%22%7D&page=" to "Japon Dizileri",
        "$mainUrl/tur/romantik?page=" to "Romantik",
        "$mainUrl/tur/dram?page=" to "Dram",
        "$mainUrl/tur/fantastik?page=" to "Fantastik",
        "$mainUrl/tur/komedi?page=" to "Komedi",
        "$mainUrl/tur/gerilim?page=" to "Gerilim",
        "$mainUrl/tur/korku?page=" to "Korku",
        "$mainUrl/tur/aksiyon?page=" to "Aksiyon",
        "$mainUrl/tur/gizem?page=" to "Gizem",
        "$mainUrl/tur/bilim-kurgu?page=" to "BilimKurgu",
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

        val plot = document.select("div.detail-attr").firstOrNull { block ->
            block.selectFirst(".attr")?.text()?.trim()?.equals("Genel Bakış", true) == true
        }?.selectFirst(".text-content, .text")?.text()?.trim()
            ?: document.selectFirst(
                "div.app-detail-overview, div.desc, div.description, div.plot, p.desc"
            )?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

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
            val img = element.selectFirst("img, .media")
            val actorName = sequenceOf(
                element.selectFirst("div.list-title, div.name, span.name")?.text()?.trim(),
                img?.attr("alt")?.trim(),
                element.text().trim(),
            ).firstOrNull { !it.isNullOrBlank() } ?: return@mapNotNull null

            val imageUrl = img?.attr("data-src")
                ?.ifBlank { img.attr("src") }
                ?.ifBlank { null }
                ?: Regex("""url\((?:&quot;|["']?)([^)"']+)(?:&quot;|["']?)\)""")
                    .find(element.attr("style"))
                    ?.groupValues?.getOrNull(1)

            Actor(actorName, fixUrlNull(imageUrl))
        }.distinctBy { it.name }

        val trailer = document.select("iframe[src], iframe[data-src], a[href], button[data-remote]")
            .mapNotNull { element ->
                val raw = element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .ifBlank { element.attr("data-remote") }
                    .let { value ->
                        Regex("""(?:trailer=)(https?%3A%2F%2F[^&]+)""", RegexOption.IGNORE_CASE)
                            .find(value)?.groupValues?.getOrNull(1)
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            ?: value
                    }

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

    override suspend fun loadLinks(\n        data: String,\n        isCasting: Boolean,\n        subtitleCallback: (SubtitleFile) -> Unit,\n        callback: (ExtractorLink) -> Unit,\n    ): Boolean {\n        Log.d(WDT_TAG, "loadLinks: $data")\n\n        val document = runCatching {\n            app.get(\n                data,\n                headers = pageHeaders,\n                referer = "$mainUrl/",\n                allowRedirects = true,\n            ).document\n        }.getOrNull() ?: return false\n\n        // Aynı loadLinks çağrısında bütün alternatifleri tüketme.\n        // İlk gerçekten medya linki üreten kaynak bulunduğunda dur.\n        var found = false\n\n        // Sayfada doğrudan bırakılmış gerçek player iframe/video adresleri.\n        // Navigation/footer bağlantılarını özellikle taramıyoruz.\n        val directPlayerCandidates = linkedSetOf<String>()\n\n        document.select(\n            "iframe[src], iframe[data-src], video[src], video[data-src], source[src], source[data-src]"\n        ).forEach { element ->\n            val raw = sequenceOf(\n                element.attr("data-src"),\n                element.attr("src"),\n                element.attr("data-url")\n            ).firstOrNull { it.isNotBlank() }.orEmpty()\n\n            if (\n                raw.contains("vidmoly", true) ||\n                raw.contains("filemoon", true) ||\n                raw.contains("ok.ru", true) ||\n                raw.contains("odnoklassniki", true) ||\n                raw.contains("vk.com", true) ||\n                raw.contains("vkvideo.ru", true) ||\n                raw.contains("abstream", true) ||\n                raw.contains("upn", true) ||\n                raw.contains("abyss", true) ||\n                raw.contains("moly", true) ||\n                raw.contains("moon", true) ||\n                raw.contains("p2p", true)\n            ) {\n                fixUrlNull(raw)?.let { directPlayerCandidates += it }\n            }\n        }\n\n        for (playerUrl in directPlayerCandidates) {\n            Log.d(WDT_TAG, "Doğrudan player adayı deneniyor: $playerUrl")\n            if (\n                resolveIframe(\n                    playerUrl,\n                    data,\n                    "Doğrudan Kaynak",\n                    subtitleCallback,\n                    callback,\n                )\n            ) {\n                found = true\n                break\n            }\n        }\n\n        // Asıl WDT kaynak akışı: data-embed -> AJAX -> video.php -> iframe/player.\n        if (!found) {\n            val buttons = document\n                .select("button[data-embed], a[data-embed], .dropdown-source[data-embed], [data-embed]")\n                .filter { it.attr("data-embed").trim().isNotBlank() }\n                .distinctBy { it.attr("data-embed").trim() }\n\n            for (button in buttons) {\n                val embedId = button.attr("data-embed").trim()\n                val sourceName = button.selectFirst("span.name")?.text()?.trim()\n                    ?: button.text().trim().takeIf { it.isNotBlank() }\n                    ?: "Alternatif"\n\n                Log.d(WDT_TAG, "Kaynak deneniyor: " + sourceName + " | embedId=" + embedId)\n\n                val ajaxResponse = runCatching {\n                    app.post(\n                        "$mainUrl/ajax/embed",\n                        headers = mapOf(\n                            "User-Agent" to USER_AGENT,\n                            "X-Requested-With" to "XMLHttpRequest",\n                            "Referer" to data,\n                            "Accept" to "*/*",\n                        ),\n                        referer = data,\n                        data = mapOf("id" to embedId),\n                    )\n                }.getOrNull() ?: continue\n\n                Log.d(WDT_TAG, "AJAX HTTP=" + ajaxResponse.code + " | source=" + sourceName)\n                if (!ajaxResponse.isSuccessful) continue\n\n                val ajaxText = ajaxResponse.text\n\n                val videoPhpUrl = Regex(\n                    """src\s*=\s*["']([^"']+)["']""",\n                    RegexOption.IGNORE_CASE\n                ).find(ajaxText)?.groupValues?.getOrNull(1)\n                    ?.replace("\\/", "/")\n                    ?.replace("&amp;", "&")\n                    ?.let(::fixUrlNull)\n                    ?: Regex(\n                        """(?:https?:)?//[^\s"'<>]*video\.php\?[^\s"'<>]+""",\n                        RegexOption.IGNORE_CASE\n                    ).find(ajaxText)?.value\n                        ?.replace("\\/", "/")\n                        ?.let(::fixUrlNull)\n\n                Log.d(WDT_TAG, "video.php=" + videoPhpUrl + " | source=" + sourceName)\n\n                if (videoPhpUrl.isNullOrBlank()) {\n                    val fallbackIframe = Regex(\n                        """(?:src|iframe)[^"']*["'](https?://[^"']+)["']""",\n                        RegexOption.IGNORE_CASE\n                    ).find(ajaxText)?.groupValues?.getOrNull(1)\n\n                    if (!fallbackIframe.isNullOrBlank()) {\n                        found = resolveIframe(\n                            fallbackIframe,\n                            data,\n                            sourceName,\n                            subtitleCallback,\n                            callback,\n                        )\n                        if (found) break\n                    }\n                    continue\n                }\n\n                val videoResponse = runCatching {\n                    app.get(\n                        videoPhpUrl,\n                        headers = mapOf(\n                            "User-Agent" to USER_AGENT,\n                            "Referer" to data,\n                        ),\n                        referer = data,\n                        allowRedirects = true,\n                    )\n                }.getOrNull() ?: continue\n\n                if (!videoResponse.isSuccessful) continue\n\n                val videoHtml = videoResponse.text\n\n                val iframeUrl = Regex(\n                    """<iframe[^>]+(?:id=["']main-iframe["'][^>]+)?src=["']([^"']+)["']""",\n                    RegexOption.IGNORE_CASE\n                ).find(videoHtml)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)\n                    ?: Regex(\n                        """src=["'](https?://[^"']+)["']""",\n                        RegexOption.IGNORE_CASE\n                    ).find(videoHtml)?.groupValues?.getOrNull(1)?.let(::fixUrlNull)\n\n                Log.d(WDT_TAG, "iframe=" + iframeUrl + " | source=" + sourceName)\n\n                if (!iframeUrl.isNullOrBlank()) {\n                    found = resolveIframe(\n                        iframeUrl,\n                        videoPhpUrl,\n                        sourceName,\n                        subtitleCallback,\n                        callback,\n                    )\n                }\n\n                // video.php içinde medya adresi bulunuyorsa doğrudan kullan.\n                if (!found) {\n                    Regex(\n                        """["'](https?://[^"']+\.(?:m3u8|mp4|mpd)[^"']*)["']""",\n                        RegexOption.IGNORE_CASE\n                    ).findAll(videoHtml).forEach { match ->\n                        val stream = match.groupValues[1]\n                        val type = when {\n                            stream.contains(".mpd", true) -> ExtractorLinkType.DASH\n                            stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8\n                            else -> ExtractorLinkType.VIDEO\n                        }\n                        callback(\n                            newExtractorLink(\n                                source = name,\n                                name = "$name - $sourceName",\n                                url = stream,\n                                type = type,\n                            ) {\n                                quality = Qualities.Unknown.value\n                                referer = videoPhpUrl\n                                headers = mapOf(\n                                    "User-Agent" to USER_AGENT,\n                                    "Referer" to videoPhpUrl,\n                                )\n                            }\n                        )\n                        found = true\n                    }\n                }\n\n                // Bu kaynak çalıştıysa diğer kaynaklara dokunma.\n                if (found) break\n\n                Regex(\n                    """["'](?:subtitle|subtitles|captions?)["']\s*[:=]\s*["']([^"']+(?:\.vtt|\.srt)[^"']*)["']""",\n                    RegexOption.IGNORE_CASE\n                ).findAll(videoHtml).forEach { match ->\n                    fixUrlNull(match.groupValues[1])?.let { subtitle ->\n                        runCatching {\n                            subtitleCallback(SubtitleFile("Türkçe", subtitle))\n                        }\n                    }\n                }\n            }\n        }\n\n        // Kaynak düğmesi bulunamazsa iframe fallback.\n        if (!found) {\n            for (iframe in document.select("iframe[src], iframe[data-src]")) {\n                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }\n                val iframeUrl = fixUrlNull(raw) ?: continue\n\n                if (resolveIframe(\n                        iframeUrl,\n                        data,\n                        "Alternatif",\n                        subtitleCallback,\n                        callback,\n                    )\n                ) {\n                    found = true\n                    break\n                }\n            }\n        }\n\n        Log.d(WDT_TAG, "loadLinks sonucu found=" + found)\n        return found\n    }\n\n    private suspend fun resolveIframe(
        iframeUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ): Boolean {
        if (depth > 2) return false

        val normalized = fixUrlNull(
            iframeUrl
                .trim()
                .replace("\\/","/")
                .replace("&amp;","&")
        ) ?: return false

        var emitted = false

        fun emitLink(link: ExtractorLink) {
            emitted = true
            callback(link)
        }

        // Siteye özel oynatıcı
        if (normalized.contains("dtpasn.asia", true) ||
            normalized.contains("dtpasn.com", true)
        ) {
            runCatching {
                WebDramaTurkeyExtractor().getUrl(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link -> emitLink(link) }
            }
        }

        // Özel VK ve Abstream çözücüleri
        if (!emitted &&
            (normalized.contains("vkvideo.ru", true) || normalized.contains("vk.com", true))
        ) {
            runCatching {
                WebDramaTurkeyVkExtractor().getUrl(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link -> emitLink(link) }
            }
        }

        if (!emitted && normalized.contains("abstream.to", true)) {
            runCatching {
                WebDramaTurkeyAbstreamExtractor().getUrl(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link -> emitLink(link) }
            }
        }

        // Bazı WebDramaTurkey player'ları gerçek medya URL'sini
        // "const qualities = [...]" içinde tutuyor ve kalite URL'sini
        // bir redirect ile gerçek m3u8/mp4 adresine çeviriyor.
        if (!emitted) {
            runCatching {
                emitted = resolveQualityPlayer(
                    normalized,
                    referer,
                    sourceName,
                    subtitleCallback,
                    callback
                ) || emitted
            }.onFailure {
                Log.d(WDT_TAG, "qualities çözümleme hatası: " + it.message)
            }
        }

        // CloudStream'in yerleşik extractor'ları. Sadece gerçekten link üretirse başarılı say.
        if (!emitted) {
            runCatching {
                Log.d(WDT_TAG, "Yerleşik extractor deneniyor: " + normalized)
                loadExtractor(
                    normalized,
                    referer,
                    subtitleCallback
                ) { link ->
                    Log.d(WDT_TAG, "Yerleşik extractor link üretti: " + link.url)
                    emitLink(link)
                }
            }.onFailure {
                Log.d(WDT_TAG, "Yerleşik extractor hata: " + it.message)
            }
        }

        if (emitted) return true

        // Oynatıcı sayfasını doğrudan incele.
        val response = runCatching {
            app.get(
                normalized,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
                referer = referer,
                allowRedirects = true,
            )
        }.getOrNull() ?: return false

        if (!response.isSuccessful) return false

        val html = response.text

        // HTML/JS içindeki doğrudan medya adresleri
        val directCandidates = linkedSetOf<String>()

        Regex(
            """https?://[^"'<>\\s]+(?:\\/[^"'<>\\s]*)*\\.(?:m3u8|mp4|mpd)(?:\\?[^"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/","/").replace("\u0026","&") }
            .forEach { directCandidates += it }

        Regex(
            """["'](?:file|src|url|source|videoSource|securedLink)["']?\\s*[:=]\\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.groupValues[1].replace("\\/","/").replace("\u0026","&") }
            .filter {
                it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains(".mpd", true)
            }
            .forEach { raw ->
                fixUrlNull(raw)?.let { directCandidates += it }
            }

        response.document.select("video[src], source[src], source[data-src]").forEach { el ->
            val raw = el.attr("data-src").ifBlank { el.attr("src") }
            fixUrlNull(raw)?.let { candidate ->
                if (
                    candidate.contains(".m3u8", true) ||
                    candidate.contains(".mp4", true) ||
                    candidate.contains(".mpd", true)
                ) {
                    directCandidates += candidate
                }
            }
        }

        directCandidates.forEach { stream ->
            val type = when {
                stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            runCatching {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - $sourceName",
                        url = stream,
                        type = type,
                    ) {
                        quality = Qualities.Unknown.value
                        this.referer = normalized
                    }
                )
                emitted = true
            }
        }

        // Alt oynatıcı iframe'leri: ilk iframe çalışmazsa diğerlerini de dene.
        if (!emitted) {
            val nestedFrames = response.document
                .select("iframe[src], iframe[data-src]")
                .mapNotNull { frame ->
                    val raw = frame.attr("data-src").ifBlank { frame.attr("src") }
                    fixUrlNull(raw)
                }
                .filter { it.isNotBlank() }
                .distinct()
                .filterNot { it == normalized }

            for (nested in nestedFrames) {
                if (
                    resolveIframe(
                        nested,
                        normalized,
                        sourceName,
                        subtitleCallback,
                        callback,
                        depth + 1,
                    )
                ) {
                    emitted = true
                    break
                }
            }
        }

        // Altyazıları doğrudan sayfadan bul.
        Regex(
            """https?://[^"'<>\\s]+(?:\\/[^"'<>\\s]*)*\\.(?:vtt|srt)(?:\\?[^"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/","/").replace("\u0026","&") }
            .distinct()
            .forEach { sub ->
                runCatching {
                    subtitleCallback(SubtitleFile("Türkçe", sub))
                }
            }

        return emitted
    }

    private suspend fun resolveQualityPlayer(
        playerUrl: String,
        referer: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = app.get(
            playerUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
            referer = referer,
            allowRedirects = true,
        )

        if (!response.isSuccessful) return false

        val html = response.text
        var emitted = false

        // Bazı player'larda gerçek medya adresleri:
        // const qualities = [{ "name": "720p", "url": "/redirect/..." }, ...];
        val qualityUrls = linkedSetOf<String>()

        val qualitiesBlock = Regex(
            """(?:const|let|var)\\s+qualities\\s*=\\s*(\\[.*?\\])\\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1)

        val qualitySource = qualitiesBlock ?: html

        Regex(
            """["'](?:url|file|src)["']\\s*:\\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(qualitySource).forEach { match ->
            val raw = match.groupValues[1]
                .replace("\\/","/")
                .replace("\\u0026","&")
            absolutizePlayerUrl(raw, playerUrl)?.let { qualityUrls += it }
        }

        // En yüksek kalite genellikle dizinin sonunda geliyor.
        for (qualityUrl in qualityUrls.toList().asReversed()) {
            Log.d(WDT_TAG, "Quality URL: " + qualityUrl)

            val redirectResponse = runCatching {
                app.get(
                    qualityUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerUrl,
                    ),
                    referer = playerUrl,
                    allowRedirects = false,
                )
            }.getOrNull()

            val location = redirectResponse?.headers?.get("Location")
                ?.replace("\\/","/")
                ?.replace("\\u0026","&")

            val finalUrl = when {
                !location.isNullOrBlank() ->
                    absolutizePlayerUrl(location, playerUrl)
                qualityUrl.contains(".m3u8", true) ||
                    qualityUrl.contains(".mp4", true) ||
                    qualityUrl.contains(".mpd", true) ->
                    qualityUrl
                else -> null
            }

            if (finalUrl.isNullOrBlank()) continue

            val type = when {
                finalUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                finalUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $sourceName",
                    url = finalUrl,
                    type = type,
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = playerUrl
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playerUrl,
                    )
                }
            )

            Log.d(WDT_TAG, "Gerçek medya URL bulundu: " + finalUrl)
            emitted = true
            break
        }

        // Player sayfasında doğrudan m3u8/mp4/mpd bulunuyorsa ayrıca dene.
        if (!emitted) {
            Regex(
                """https?://[^"'<>\\s]+\\.(?:m3u8|mp4|mpd)(?:\\?[^"'<()>\\s]*)?""",
                RegexOption.IGNORE_CASE
            ).findAll(html)
                .map { it.value.replace("\\/","/").replace("\u0026","&") }
                .distinct()
                .forEach { stream ->
                    val type = when {
                        stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                        stream.contains(".m3u8", true) -> ExtractorLinkType.M3U8
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
                            this.referer = playerUrl
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to playerUrl,
                            )
                        }
                    )
                    emitted = true
                }
        }

        // Altyazı
        Regex(
            """https?://[^"'<>\s]+\\.(?:vtt|srt)(?:\\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html)
            .map { it.value.replace("\\/","/").replace("\u0026","&") }
            .distinct()
            .forEach { subtitle ->
                runCatching { subtitleCallback(SubtitleFile("Türkçe", subtitle)) }
            }

        return emitted
    }

    private fun absolutizePlayerUrl(raw: String, base: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value
        }

        if (value.startsWith("//")) return "https:$value"

        return runCatching {
            java.net.URI(base).resolve(value).toString()
        }.getOrNull()
    }

    private fun isContentUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("$mainUrl/dizi/") ||
            value.contains("$mainUrl/film/") ||
            value.contains("$mainUrl/anime/") ||
            value.contains("$mainUrl/program/")
    }
}
