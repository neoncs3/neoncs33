package com.asyawatch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * AsyaWatch sağlayıcısı.
 * Kullanıcının verdiği AsyaWatchProvider kodu temel alınarak düzenlenmiştir.
 */
class AsyaWatchProvider : MainAPI() {
    override var name = "AsyaWatch"
    override var mainUrl = "https://asyawatch.com"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/json,*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to (mainUrl + "/"),
        )

    override val mainPage = mainPageOf(
        mainUrl + "/dizi-izle" to "Yeni Diziler",
        mainUrl + "/film-izle" to "Son Filmler",
    )

    private val cardSelector =
        "a.pcard[href], a[href*='/dizi/'], a[href*='/film/']"

    private val badImg = Regex(
        """(?i)^(?:data:|about:|javascript:)|/(?:pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(?:png|gif|jpe?g|svg|webp)(?:[?#]|$)"""
    )

    private val mediaRegex = Regex(
        """(?i)(?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^"'<>\s]*)?"""
    )

    private val playerUrlRegex = Regex(
        """(?i)["'](?:src|master|file|source|url)["']\s*:\s*["']((?:https?:)?//[^"']+)["']"""
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (page > 1) {
            request.data.trimEnd('/') + "?page=" + page
        } else {
            request.data
        }

        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        val url = mainUrl + "/ara?q=" + URLEncoder.encode(term, "UTF-8")
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrNull() ?: return emptyList()

        return document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }
    }

    private fun Element.toHtmlCard(): SearchResponse? {
        val href = absoluteUrl(attr("href"))?.takeIf { it.isNotBlank() } ?: return null
        val title = sequenceOf(
            selectFirst("a.tcn, b, h2, h3")?.text(),
            attr("title"),
            selectFirst("img")?.attr("alt"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull() ?: return null

        val image = selectFirst("img")
        val rawPoster = image?.let {
            sequenceOf(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src"),
            ).firstOrNull { value -> value.isNotBlank() }
        }?.takeIf { !badImg.containsMatchIn(it) }

        val poster = rawPoster?.let { absoluteUrl(it) }
        return if (href.contains("/dizi/", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            throw ErrorLoadingException("AsyaWatch içerik sayfası açılamadı")
        }

        val ld = readJsonLd(document)
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Başlık okunamadı")

        val imageValue = when (val image = ld?.opt("image")) {
            is JSONArray -> image.optString(0)
            is JSONObject -> image.optString("url")
            is String -> image
            else -> null
        }

        val rawPoster = imageValue?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        val poster = rawPoster?.let { absoluteUrl(it) }

        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val rating = ld?.optJSONObject("aggregateRating")
            ?.optString("ratingValue")
            ?.takeIf { it.isNotBlank() }

        val actors = readActors(ld)
        val recommendations = document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .filterNot { it.url == url }
            .distinctBy { it.url }

        val episodes = if (url.contains("/dizi/", ignoreCase = true)) {
            document.select("a.simr[href]").mapNotNull { anchor ->
                val href = absoluteUrl(anchor.attr("href"))
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = anchor.text().ifBlank {
                    href.substringAfterLast("/")
                }

                newEpisode(href) {
                    name = label
                    season = Regex("""(?i)sezon-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episode = Regex("""(?i)bolum-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    posterUrl = poster
                }
            }.distinctBy { it.data }
        } else {
            emptyList()
        }

        return if (url.contains("/dizi/", ignoreCase = true)) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private fun readJsonLd(document: org.jsoup.nodes.Document): JSONObject? {
        val candidates = mutableListOf<JSONObject>()

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            val parsed: Any = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: return@forEach

            when (parsed) {
                is JSONObject -> {
                    val graph = parsed.optJSONArray("@graph")
                    if (graph != null) {
                        for (index in 0 until graph.length()) {
                            graph.optJSONObject(index)?.let(candidates::add)
                        }
                    } else {
                        candidates.add(parsed)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until parsed.length()) {
                        parsed.optJSONObject(index)?.let(candidates::add)
                    }
                }
            }
        }

        return candidates.firstOrNull { item ->
            when (val type = item.opt("@type")) {
                is String -> type in setOf("Movie", "TVSeries", "CreativeWork", "Article")
                is JSONArray -> (0 until type.length()).any {
                    type.optString(it) in setOf("Movie", "TVSeries", "CreativeWork", "Article")
                }
                else -> false
            }
        }
    }

    private fun readActors(ld: JSONObject?): List<ActorData> {
        val raw = ld?.opt("actor") ?: return emptyList()
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) }
            else -> listOf(raw)
        }

        return values.mapNotNull { item ->
            val actorName = when (item) {
                is JSONObject -> item.optString("name")
                is String -> item
                else -> ""
            }.trim()

            actorName.takeIf { it.isNotBlank() }?.let { ActorData(Actor(it)) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = headers, referer = mainUrl + "/")
        }.getOrNull() ?: return false

        val document = response.document
        val pageText = buildString {
            append(response.text)
            append('\n')
            append(document.html())
            document.select("script").forEach {
                append('\n')
                append(it.data())
            }
        }.replace("\\/", "/")

        var found = false

        val iframeCandidates = document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src]"
        ).mapNotNull { element ->
            sequenceOf(
                element.attr("src"),
                element.attr("data-src"),
            ).firstOrNull { it.isNotBlank() }?.let { absoluteUrl(it, data) }
        }.filterNotNull().distinct()

        for (candidate in iframeCandidates) {
            if (candidate.isMediaUrl()) {
                if (emitMedia(candidate, data, callback)) found = true
            } else {
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }
        }

        val videoId = sequenceOf(
            Regex("""(?i)window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""")
                .find(pageText)?.groupValues?.getOrNull(1),
            Regex("""(?i)[?&]v=([\w-]+)""")
                .find(pageText)?.groupValues?.getOrNull(1),
            Regex("""(?i)(?:videoId|video_id|data-video-id)\s*[:=]\s*['"]?([\w-]+)""")
                .find(pageText)?.groupValues?.getOrNull(1),
        ).firstOrNull { !it.isNullOrBlank() }

        if (!videoId.isNullOrBlank()) {
            val sourceUrl = mainUrl + "/source2.php?v=" +
                URLEncoder.encode(videoId, "UTF-8")
            val sourceResponse = runCatching {
                app.get(
                    sourceUrl,
                    headers = headers + mapOf(
                        "Referer" to data,
                        "Accept" to "application/json,text/plain,*/*",
                    ),
                    referer = data,
                )
            }.getOrNull()

            if (sourceResponse?.isSuccessful == true) {
                val sourceText = sourceResponse.text.replace("\\/", "/")
                val streams = buildSet {
                    mediaRegex.findAll(sourceText).forEach { add(cleanUrl(it.value)) }
                    playerUrlRegex.findAll(sourceText).forEach {
                        add(cleanUrl(it.groupValues[1]))
                    }

                    runCatching { JSONObject(sourceText) }.getOrNull()?.let { json ->
                        listOf("src", "master", "file", "url").forEach { key ->
                            json.optString(key).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }

                for (stream in streams) {
                    if (stream.isMediaUrl() && emitMedia(stream, data, callback)) {
                        found = true
                    }
                }

                val sourceIframe = playerUrlRegex.find(sourceText)
                    ?.groupValues?.getOrNull(1)
                if (!sourceIframe.isNullOrBlank() && !sourceIframe.isMediaUrl()) {
                    val iframeUrl = absoluteUrl(sourceIframe, data)
                    if (iframeUrl != null) {
                        runCatching {
                            loadExtractor(iframeUrl, data, subtitleCallback) { link ->
                                callback(link)
                                found = true
                            }
                        }
                    }
                }
            }
        }

        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitle = absoluteUrl(raw, data) ?: return@forEach
            runCatching {
                subtitleCallback(
                    newSubtitleFile(
                        lang = track.attr("label").ifBlank {
                            track.attr("srclang").ifBlank { "Türkçe" }
                        },
                        url = subtitle,
                    ) {
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to data,
                        )
                    }
                )
            }
        }

        return found
    }

    private suspend fun emitMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = cleanUrl(url)
        val path = clean.substringBefore('?').lowercase()
        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(name, name, clean, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                )
            }
        )
        return true
    }

    private fun String.isMediaUrl(): Boolean {
        val path = lowercase().substringBefore('#').substringBefore('?')
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm")
    }

    private fun cleanUrl(raw: String): String {
        val value = raw.trim()
            .replace("\\/", "/")
            .trim('"', '\'', ' ', ')', ']', '}', ',')
        return if (value.startsWith("//")) "https:" + value else value
    }

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true) &&
                    !it.equals("undefined", ignoreCase = true)
            } ?: return null
        return runCatching { URI(base).resolve(value).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
