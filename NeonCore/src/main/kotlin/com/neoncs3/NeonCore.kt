package com.neoncs3

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URI
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Shared runtime helpers for every NeonCS provider.
 *
 * The class is intentionally kept dependency-light so the source is compiled
 * into each provider package instead of requiring a second runtime plugin.
 */
open class NeonMainAPI : MainAPI() {
    protected open val neonCacheTtlMs: Long = 90_000L
    protected open val neonRequestTimeoutMs: Int = 15_000

    protected val neonHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
        )

    protected fun neonCleanText(value: String?): String? {
        return value
            ?.replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
                it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
            }
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.replace("\\\"", "\"")
            ?.replace("\\'", "'")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    protected fun neonNormalizeUrl(value: String?, baseUrl: String = mainUrl): String {
        val raw = neonCleanText(value).orEmpty()
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) return raw
        if (raw.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl).scheme }.getOrNull() ?: "https"
            return "$scheme:$raw"
        }
        // A player script sometimes stores absolute URLs without their scheme
        // (for example: www.tvdiziler.tv/vid/assets). URI.resolve() treats those
        // strings as relative paths and builds /vid/ply/www.tvdiziler.tv/... .
        if (Regex("""(?i)^www\.[a-z0-9.-]+(?::\d+)?(?:/|[?#]|$)""").containsMatchIn(raw)) {
            return "https://$raw"
        }
        return runCatching {
            URI(baseUrl).resolve(raw).toString()
        }.getOrElse { raw }
    }

    protected fun neonExtractYear(text: String?): Int? {
        val value = text.orEmpty()
        return Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
            .find(value)
            ?.value
            ?.toIntOrNull()
    }

    protected fun neonExtractRating(text: String?): Double? {
        val value = text.orEmpty()
        val labelled = Regex(
            """(?i)(?:IMDb|IMDB|rating|puan)\s*[:/\\-]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(value)

        val candidate = labelled?.groupValues?.getOrNull(1)
            ?: Regex("""(?<!\d)([0-9](?:[.,][0-9])?)(?!\d)""")
                .find(value)
                ?.groupValues
                ?.getOrNull(1)

        return candidate
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }
    }

    protected fun neonPoster(document: Document, baseUrl: String = mainUrl): String? {
        val candidates = listOf(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("meta[name='twitter:image']")?.attr("content"),
            document.selectFirst("main img")?.attr("data-src"),
            document.selectFirst("main img")?.attr("data-lazy-src"),
            document.selectFirst("main img")?.attr("data-original"),
            document.selectFirst("main img")?.attr("src"),
            document.selectFirst("article img")?.attr("src"),
            document.selectFirst("img")?.attr("src"),
        )
        return candidates.firstNotNullOfOrNull {
            neonNormalizeUrl(it, baseUrl).takeIf { url -> url.isNotBlank() }
        }
    }

    protected fun neonPageTitle(document: Document, fallback: String? = null): String? {
        return listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='twitter:title']")?.attr("content"),
            document.selectFirst("title")?.text(),
            fallback,
        ).firstNotNullOfOrNull { neonCleanText(it) }
    }

    protected fun neonPlot(document: Document): String? {
        return listOf(
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
            document.selectFirst(".summary")?.text(),
        ).firstNotNullOfOrNull { neonCleanText(it) }
    }

    protected fun neonMediaQuality(url: String): Int {
        val value = url.lowercase()
        Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240|144)(?:p|k)?(?!\d)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        return when {
            "2160" in value || "4k" in value -> 2160
            "1440" in value || "2k" in value -> 1440
            "1080" in value || "fhd" in value -> 1080
            "720" in value || "hd" in value -> 720
            "480" in value -> 480
            "360" in value -> 360
            "240" in value -> 240
            else -> 0
        }
    }

    protected fun neonMediaType(url: String): ExtractorLinkType {
        val value = url.lowercase().substringBefore('#').substringBefore('?')
        return when {
            value.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            value.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun neonIsYouTubePlaybackPage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri.path.orEmpty().lowercase()
        return when (host) {
            "youtu.be" -> true
            "youtube.com", "m.youtube.com", "youtube-nocookie.com" ->
                path == "/watch" || path == "/live" ||
                    path.startsWith("/embed/") || path.startsWith("/shorts/")
            else -> false
        }
    }

    protected fun neonIsMediaUrl(url: String): Boolean {
        val value = url.lowercase().substringBefore('#')
        val uri = runCatching { URI(value) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase().removePrefix("www.")
        val path = uri?.path.orEmpty().lowercase()
        // Google Video uses signed /videoplayback endpoints with no media extension.
        // Treating these as HTML pages made the resolver fetch the stream URL as a page,
        // which commonly returns 403 and then emits no ExtractorLink for ExoPlayer.
        val googleVideoPlayback = host.endsWith(".googlevideo.com") &&
            (path == "/videoplayback" || path.endsWith("/videoplayback"))
        return googleVideoPlayback ||
            value.contains(".m3u8") ||
            value.contains(".mpd") ||
            value.contains(".mp4") ||
            value.contains(".m4v") ||
            value.contains(".webm") ||
            value.contains(".mov")
    }

    protected fun neonExtractMediaUrls(raw: String?): List<String> {
        val normalized = neonCleanText(raw)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("&#x2F;", "/")
            .orEmpty()
        if (normalized.isBlank()) return emptyList()

        val directPattern = Regex(
            """(?i)(?:https?:)?//[^"'<>\s]+?(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov)(?:\?[^"'<>\s]*)?"""
        )

        val attributePattern = Regex(
            """(?is)(?:src|file|source|stream|url|videoSource|securedLink|hls|playlist)\s*[:=]\s*["']([^"'<>\s]+)["']"""
        )

        return buildList {
            directPattern.findAll(normalized).forEach { add(it.value) }
            attributePattern.findAll(normalized).forEach { add(it.groupValues[1]) }
        }
            .mapNotNull {
                neonNormalizeUrl(it)
                    .trimEnd('"', '\'', ')', ']', '}', ',', ';')
                    .takeIf(::neonIsMediaUrl)
            }
            .distinct()
    }

    protected fun neonExtractPlayerCandidates(document: Document, baseUrl: String = mainUrl): List<String> {
        val result = LinkedHashSet<String>()

        neonExtractIframeUrls(document, baseUrl).forEach { result.add(it) }

        document.select(
            "video[src], video source[src], iframe[data-url], video[data-url], source[data-url], " +
                "[data-video], [data-file], [data-source], [data-video-url], [data-stream], [data-hls], " +
                "[data-player][data-url], [data-embed][data-url], [data-iframe][data-url]"
        ).forEach { node ->
            sequenceOf(
                node.attr("src"),
                node.attr("data-video"),
                node.attr("data-file"),
                node.attr("data-source"),
                node.attr("data-video-url"),
                node.attr("data-stream"),
                node.attr("data-hls"),
                node.attr("data-url"),
            ).filter { it.isNotBlank() }.forEach { raw ->
                val url = neonNormalizeUrl(raw, baseUrl)
                if (url.isNotBlank()) result.add(url)
            }
        }

        neonExtractMediaUrls(document.html()).forEach { result.add(it) }
        return result.toList()
    }

    protected fun neonExtractIframeUrls(document: Document, baseUrl: String = mainUrl): List<String> {
        return document.select("iframe[src], iframe[data-src], iframe[data-url], iframe[data-vsrc], iframe[data-video-src], iframe[data-player]")
            .mapNotNull {
                val value = it.attr("src")
                    .ifBlank { it.attr("data-vsrc") }
                    .ifBlank { it.attr("data-video-src") }
                    .ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-url") }
                    .ifBlank { it.attr("data-player") }

                neonNormalizeUrl(value, baseUrl).takeIf { url -> url.isNotBlank() }
            }
            .distinct()
    }

    protected fun neonSubtitleUrls(document: Document, baseUrl: String = mainUrl): List<Pair<String, String>> {
        val result = LinkedHashMap<String, String>()

        document.select("track[src], track[data-src], track[data-subtitle]").forEach { node ->
            val value = node.attr("src")
                .ifBlank { node.attr("data-src") }
                .ifBlank { node.attr("data-subtitle") }

            val url = neonNormalizeUrl(value, baseUrl)
            if (url.isNotBlank()) {
                val label = node.attr("label")
                    .ifBlank { node.attr("srclang") }
                    .ifBlank { "Türkçe" }
                result[url] = label
            }
        }

        document.select("a[href]").forEach { node ->
            val url = neonNormalizeUrl(node.attr("href"), baseUrl)
            val lower = url.lowercase()
            if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass") || lower.contains(".ttml")) {
                result.putIfAbsent(url, node.text().trim().ifBlank { "Türkçe" })
            }
        }

        return result.map { it.key to it.value }
    }

    protected suspend fun neonEmitSubtitle(
        url: String,
        label: String = "Türkçe",
        referer: String = mainUrl,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val normalized = neonNormalizeUrl(url, referer)
        if (normalized.isBlank()) return

        subtitleCallback(
            newSubtitleFile(
                lang = label.ifBlank { "Türkçe" },
                url = normalized,
            ) {
                headers = neonHeaders + ("Referer" to referer)
            },
        )
    }

    protected suspend fun neonEmitMedia(
        url: String,
        sourceName: String = name,
        referer: String = mainUrl,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = neonNormalizeUrl(url, referer)
        if (!neonIsMediaUrl(normalized)) return false

        val quality = neonMediaQuality(normalized)
        val type = neonMediaType(normalized)
        val label = buildString {
            append(sourceName)
            when (quality) {
                2160 -> append(" • 4K")
                1440 -> append(" • 1440p")
                1080 -> append(" • 1080p")
                720 -> append(" • 720p")
                576 -> append(" • 576p")
                540 -> append(" • 540p")
                480 -> append(" • 480p")
                360 -> append(" • 360p")
                240 -> append(" • 240p")
            }
        }

        val uri = runCatching { URI(normalized) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase().removePrefix("www.")
        val isSignedYouTubeMedia = (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            uri?.rawQuery.orEmpty().split("&").any { it.equals("source=youtube", ignoreCase = true) }
        val mediaReferer = if (isSignedYouTubeMedia) "https://www.youtube.com/" else referer

        val link = newExtractorLink(
            source = sourceName,
            name = label,
            url = normalized,
            type = type,
        ) {
            this.referer = mediaReferer
            this.quality = quality
            // Use media-friendly request headers. Google Video signed streams expect a
            // YouTube player referer, not the TVDiziler episode/iframe URL.
            this.headers = neonHeaders + mapOf(
                "Accept" to "*/*",
                "Referer" to mediaReferer,
            )
        }

        callback(link)
        return true
    }

    private fun neonExtractPlayerUrlsFromText(raw: String?, baseUrl: String): List<String> {
        val text = raw.orEmpty()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        if (text.isBlank()) return emptyList()

        val pattern = Regex(
            """(?is)["']?(?:src|file|file_url|source|source_url|url|embed|embed_url|iframe|iframe_url|video_url|video_src|videourl|play_url|player_url|playerurl|hls|hls_url|playlist|stream|stream_url|media_url|manifest)["']?\s*[:=]\s*["']([^"'<>\s]+)["']"""
        )
        return pattern.findAll(text)
            .mapNotNull { match ->
                val value = neonNormalizeUrl(match.groupValues[1], baseUrl)
                    .trimEnd('"', '\'', ')', ']', '}', ',', ';')
                val uri = runCatching { URI(value) }.getOrNull() ?: return@mapNotNull null
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    null
                } else value
            }
            .distinct()
            .take(80)
            .toList()
    }

    protected suspend fun neonResolveLinks(
        data: String,
        sourceName: String = name,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        maxDepth: Int = 2,
        referer: String = mainUrl,
    ): Boolean {
        val root = neonNormalizeUrl(data).ifBlank { data }
        if (root.isBlank()) return false

        val visited = HashSet<String>()
        val mediaSeen = HashSet<String>()
        var found = false

        suspend fun visit(url: String, referer: String, depth: Int) {
            val normalized = neonNormalizeUrl(url, referer).ifBlank { url }
            if (!visited.add(normalized) || depth > maxDepth) return

            if (neonIsMediaUrl(normalized)) {
                if (!mediaSeen.add(normalized)) return
                if (neonEmitMedia(normalized, sourceName, referer, callback)) {
                    found = true
                }
                return
            }

            // YouTube iframe pages must go through CloudStream's native extractor.
            // Crawling YouTube's player JavaScript as generic HTML produced many false
            // media candidates (including expired googlevideo URLs) and no playable link.
            if (neonIsYouTubePlaybackPage(normalized)) {
                val resolved = runCatching {
                    loadExtractor(normalized, referer, subtitleCallback, callback)
                }.onFailure {
                    Log.w("NeonResolver", "YouTube extractor failed: ${it.javaClass.simpleName}")
                }.getOrDefault(false)
                if (resolved) found = true
                return
            }

            val cacheKey = "html:$normalized"
            val cached = NeonMemoryCache.get(cacheKey)
            val fetched = if (cached != null) {
                normalized to cached
            } else {
                neonGetHtmlWithFallback(normalized, referer)
                    ?.also { (requestUrl, html) ->
                        NeonMemoryCache.put(cacheKey, html, neonCacheTtlMs)
                    }
            }

            val html = fetched?.second
            val documentBase = fetched?.first ?: normalized
            if (html.isNullOrBlank()) return

            val document = Jsoup.parse(html, documentBase)

            neonSubtitleUrls(document, normalized).forEach { (url, label) ->
                neonEmitSubtitle(url, label, normalized, subtitleCallback)
            }

            // If the player frame itself is a YouTube video, resolve that page first
            // through CloudStream's native YouTube extractor. Scraped googlevideo URLs
            // can be tied to the IP/client that fetched them and then fail with HTTP 403.
            val youtubeFrames = neonExtractIframeUrls(document, documentBase)
                .filter(::neonIsYouTubePlaybackPage)
                .distinct()
                .take(4)
            if (depth < maxDepth && youtubeFrames.isNotEmpty()) {
                val foundBeforeYouTube = found
                youtubeFrames.forEach { frame -> visit(frame, normalized, depth + 1) }
                if (!foundBeforeYouTube && found) return
            }

            neonExtractMediaUrls(html).forEach { media ->
                if (!mediaSeen.add(media)) return@forEach
                if (neonEmitMedia(media, sourceName, normalized, callback)) {
                    found = true
                }
            }

            // Embedded players frequently keep the stream URL in data-* attributes or
            // JavaScript configuration rather than a literal .m3u8 URL in the HTML.
            val playerCandidates = LinkedHashSet<String>()
            neonExtractPlayerCandidates(document, documentBase).forEach { candidate ->
                if (candidate != normalized && !neonIsMediaUrl(candidate)) {
                    playerCandidates.add(candidate)
                }
            }
            document.select("script:not([src])").forEach { script ->
                val scriptText = script.data().ifBlank { script.html() }
                neonExtractMediaUrls(scriptText).forEach { playerCandidates.add(it) }
                neonExtractPlayerUrlsFromText(scriptText, documentBase).forEach { playerCandidates.add(it) }
            }

            // Probe a small, relevant subset of external player bundles; never crawl every
            // site script. This handles embeds whose source is assembled client-side.
            val playerScripts = document.select("script[src]")
                .map { neonNormalizeUrl(it.attr("src"), documentBase) }
                .filter { scriptUrl ->
                    val hint = scriptUrl.lowercase()
                    hint.endsWith(".js") || ".js?" in hint
                }
                .filter { scriptUrl ->
                    Regex("""(?i)(player|video|stream|embed|media|watch|episode|source|app|bundle|main|chunk)""")
                        .containsMatchIn(scriptUrl)
                }
                .distinct()
                .take(3)

            for (scriptUrl in playerScripts) {
                val scriptBody = runCatching {
                    app.get(
                        scriptUrl,
                        headers = neonHeaders + ("Referer" to normalized),
                        referer = normalized,
                        timeout = 4_000L,
                        allowRedirects = true,
                    ).takeIf { it.isSuccessful }?.text
                }.getOrNull().orEmpty()
                if (scriptBody.isBlank()) continue
                neonExtractMediaUrls(scriptBody).forEach { playerCandidates.add(it) }
                neonExtractPlayerUrlsFromText(scriptBody, scriptUrl).forEach { playerCandidates.add(it) }
            }

            if (depth < maxDepth) {
                playerCandidates.forEach { candidate ->
                    val candidateUrl = neonNormalizeUrl(candidate, documentBase)
                    if (candidateUrl.isNotBlank() && candidateUrl != normalized) {
                        if (neonIsMediaUrl(candidateUrl)) {
                            if (mediaSeen.add(candidateUrl) &&
                                neonEmitMedia(candidateUrl, sourceName, normalized, callback)
                            ) {
                                found = true
                            }
                        } else {
                            visit(candidateUrl, normalized, depth + 1)
                        }
                    }
                }
            }
        }

        visit(root, referer, 0)
        return found
    }

    protected fun neonExtractGenres(document: Document): List<String> {
        return document.select(
            "a[href*='/tur/'], a[href*='/genre/'], a[href*='/kategori/'], " +
                "[class*='genre'] a, [class*='tur'] a"
        )
            .map { neonCleanText(it.text()).orEmpty() }
            .filter { it.length in 2..40 }
            .distinct()
    }

    protected fun neonExtractDuration(document: Document): Int? {
        return Regex(
            """(?i)(?:^|\s)(\d{1,3})\s*(?:dakika|dk|min(?:ute)?s?)\b"""
        )
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..600 }
    }

    protected fun neonExtractActors(document: Document, baseUrl: String = mainUrl): List<Actor> {
        return document.select(
            "a[href*='/oyuncu/'], a[href*='/actor/'], .actor, .actors li, .oyuncu-k, " +
                ".oyuncular li, .cast li, .cast .item, [class*='oyuncu']"
        )
            .mapNotNull { node ->
                val name = listOf(
                    node.attr("title"),
                    node.selectFirst("span.name, .name, .actor-name, .oyuncu-isim, .isim")?.text(),
                    node.selectFirst("img")?.attr("alt"),
                    node.text(),
                )
                    .mapNotNull { neonCleanText(it) }
                    .firstOrNull { it.length in 2..80 }
                    ?: return@mapNotNull null

                if (name.equals("Oyuncular", true) || name.equals("Oyuncuları", true)) {
                    return@mapNotNull null
                }

                val imageRaw = sequenceOf(
                    node.selectFirst("img")?.attr("data-src"),
                    node.selectFirst("img")?.attr("data-lazy-src"),
                    node.selectFirst("img")?.attr("data-original"),
                    node.selectFirst("img")?.attr("src"),
                ).firstOrNull { !it.isNullOrBlank() }

                Actor(
                    name = name,
                    image = neonNormalizeUrl(imageRaw, baseUrl)
                        .takeIf { it.isNotBlank() }
                )
            }
            .distinctBy { it.name }
            .take(40)
    }

    protected fun neonExtractTrailer(document: Document, baseUrl: String = mainUrl): String? {
        val html = document.html().replace("\\\\/", "/")
        val youtube = Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:youtube\.com/(?:watch\?v=|embed/)|youtu\.be/)[^"'<>\s]+"""
        )
            .find(html)
            ?.value
            ?.let { neonNormalizeUrl(it, baseUrl) }

        if (!youtube.isNullOrBlank()) return youtube

        return document.select("a[href], iframe[src], [data-trailer], [data-video]")
            .mapNotNull { node ->
                sequenceOf(
                    node.attr("data-trailer"),
                    node.attr("src"),
                    node.attr("href"),
                    node.attr("data-video"),
                )
                    .firstOrNull { it.isNotBlank() }
                    ?.let { neonNormalizeUrl(it, baseUrl) }
            }
            .firstOrNull { it.contains("youtube.com", true) || it.contains("youtu.be", true) }
    }

    protected suspend fun neonEnrichResponse(
        response: LoadResponse,
        document: Document,
        baseUrl: String = mainUrl,
    ): LoadResponse {
        if (response.posterUrl.isNullOrBlank()) {
            neonPoster(document, baseUrl)?.let { response.posterUrl = it }
        }

        if (response.year == null) {
            response.year = neonExtractYear(document.text())
        }

        if (response.plot.isNullOrBlank()) {
            response.plot = neonPlot(document)
        }

        if (response.score == null) {
            neonExtractRating(document.text())?.let { response.score = Score.from10(it.toString()) }
        }

        if (response.tags.isNullOrEmpty()) {
            neonExtractGenres(document).takeIf { it.isNotEmpty() }?.let { response.tags = it }
        }

        if (response.duration == null) {
            response.duration = neonExtractDuration(document)
        }

        if (response.actors.isNullOrEmpty()) {
            val actors = neonExtractActors(document, baseUrl)
            if (actors.isNotEmpty()) {
                response.addActors(actors)
            }
        }

        if (response.trailers.isEmpty()) {
            neonExtractTrailer(document, baseUrl)?.let { response.addTrailer(it) }
        }

        if (response.backgroundPosterUrl.isNullOrBlank() && response.posterUrl != null) {
            response.backgroundPosterUrl = response.posterUrl
        }

        return response
    }

    protected suspend fun neonResolveLinkCandidates(
        candidates: Iterable<String>,
        sourceName: String = name,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        maxDepth: Int = 2,
        referer: String = mainUrl,
    ): Boolean {
        var found = false
        val seen = LinkedHashSet<String>()

        for (candidate in candidates) {
            val normalized = neonNormalizeUrl(candidate).ifBlank { candidate.trim() }
            if (normalized.isBlank() || !seen.add(normalized)) continue

            if (neonResolveLinks(
                    data = normalized,
                    sourceName = sourceName,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    maxDepth = maxDepth,
                    referer = referer,
                )
            ) {
                found = true
            }
        }

        return found
    }

    private companion object {
        const val NEON_DOMAIN_MANIFEST_URL =
            "https://raw.githubusercontent.com/neoncs3/neoncs33/master/NeonCore/domains.json"
        const val NEON_DOMAIN_MANIFEST_CACHE_KEY = "neon:domain-manifest"
        const val NEON_DOMAIN_MANIFEST_TTL_MS = 21_600_000L
    }

    private suspend fun neonRemoteDomain(): String? {
        val providerName = name.trim()
        if (providerName.isBlank()) return null

        val raw = NeonMemoryCache.get(NEON_DOMAIN_MANIFEST_CACHE_KEY) ?: runCatching {
            app.get(
                NEON_DOMAIN_MANIFEST_URL + "?v=" +
                    (System.currentTimeMillis() / NEON_DOMAIN_MANIFEST_TTL_MS),
                headers = neonHeaders,
                timeout = neonRequestTimeoutMs.toLong(),
                allowRedirects = true,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull()?.also {
            NeonMemoryCache.put(
                NEON_DOMAIN_MANIFEST_CACHE_KEY,
                it,
                NEON_DOMAIN_MANIFEST_TTL_MS,
            )
        } ?: return null

        return runCatching {
            val value = JSONObject(raw).optString(providerName).trim()
            if (value.isBlank()) return@runCatching null

            val uri = URI(value)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                null
            } else {
                buildString {
                    append(uri.scheme)
                    append("://")
                    append(uri.host)
                    if (uri.port > 0) append(":").append(uri.port)
                }.trimEnd('/')
            }
        }.getOrNull()
    }

    private suspend fun neonRuntimeCandidates(
        normalized: String,
        referer: String,
    ): List<Pair<String, String>> {
        val mainUri = runCatching { URI(mainUrl) }.getOrNull()
            ?: return listOf(normalized to referer)
        val requestUri = runCatching { URI(normalized) }.getOrNull()
            ?: return listOf(normalized to referer)

        if (!mainUri.host.isNullOrBlank() &&
            !requestUri.host.isNullOrBlank() &&
            !requestUri.host.equals(mainUri.host, ignoreCase = true)
        ) {
            return listOf(normalized to referer)
        }

        val remoteDomain = neonRemoteDomain()
        if (remoteDomain.isNullOrBlank()) {
            return listOf(normalized to referer)
        }

        val remoteUri = runCatching { URI(remoteDomain) }.getOrNull()
            ?: return listOf(normalized to referer)

        if (remoteUri.host.equals(mainUri.host, ignoreCase = true)) {
            return listOf(normalized to referer)
        }

        val rewritten = runCatching {
            URI(
                remoteUri.scheme,
                remoteUri.rawAuthority,
                requestUri.rawPath,
                requestUri.rawQuery,
                requestUri.rawFragment,
            ).toString()
        }.getOrNull() ?: return listOf(normalized to referer)

        return listOf(
            rewritten to "$remoteDomain/",
            normalized to referer,
        ).distinctBy { it.first }
    }

    private suspend fun neonGetHtmlWithFallback(
        url: String,
        referer: String,
        attempts: Int = 2,
    ): Pair<String, String>? {
        for ((requestUrl, requestReferer) in neonRuntimeCandidates(url, referer)) {
            val maxAttempts = attempts.coerceIn(1, 3)
            val uri = runCatching { URI(requestUrl) }.getOrNull()
            // Never log query parameters: player URLs can contain short-lived signatures.
            val safeTarget = uri?.let { "${it.host}${it.path.orEmpty()}" } ?: "invalid-url"
            val refererHost = runCatching { URI(requestReferer).host }.getOrNull() ?: "unknown"

            for (attempt in 0 until maxAttempts) {
                val result = runCatching {
                    app.get(
                        requestUrl,
                        headers = neonHeaders + ("Referer" to requestReferer),
                        referer = requestReferer,
                        timeout = neonRequestTimeoutMs.toLong(),
                        allowRedirects = true,
                    )
                }
                val response = result.getOrNull()

                if (response?.isSuccessful == true && response.text.isNotBlank()) {
                    return requestUrl to response.text
                }

                if (response != null && !response.isSuccessful) {
                    Log.w(
                        "NeonResolver",
                        "HTTP ${response.code} for $safeTarget (refererHost=$refererHost)"
                    )
                    // A 403/404 is a definitive refusal/missing resource for this request.
                    // Retry only temporary client errors such as timeout or rate limiting.
                    if (response.code in 400..499 && response.code !in setOf(408, 429)) {
                        break
                    }
                } else if (response == null) {
                    Log.w(
                        "NeonResolver",
                        "Request failed for $safeTarget (refererHost=$refererHost, error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"})"
                    )
                } else if (response.text.isBlank()) {
                    Log.w("NeonResolver", "Empty response for $safeTarget (HTTP ${response.code})")
                }

                if (attempt + 1 < maxAttempts) delay(250L)
            }
        }
        return null
    }

    protected suspend fun neonCachedHtml(
        url: String,
        referer: String = mainUrl,
        ttlMs: Long = neonCacheTtlMs,
    ): String? {
        val normalized = neonNormalizeUrl(url, referer)
        if (normalized.isBlank()) return null

        val key = "html:$normalized"
        NeonMemoryCache.get(key)?.let { return it }

        val fetched = neonGetHtmlWithFallback(normalized, referer)
        if (fetched != null) {
            NeonMemoryCache.put(key, fetched.second, ttlMs)
            return fetched.second
        }

        return null
    }

    protected suspend fun neonCachedDocument(
        url: String,
        referer: String = mainUrl,
        ttlMs: Long = neonCacheTtlMs,
    ): Document? {
        return neonCachedHtml(url, referer, ttlMs)?.let {
            Jsoup.parse(it, neonNormalizeUrl(url, referer))
        }
    }

    protected suspend fun neonFindHealthyDomain(
        candidates: List<String>,
        markers: List<String> = emptyList(),
        timeoutMs: Int = 8_000,
    ): String? {
        for (candidate in candidates.distinct()) {
            val normalized = neonNormalizeUrl(candidate)
            val body = runCatching {
                app.get(
                    normalized,
                    headers = neonHeaders,
                    timeout = timeoutMs.toLong(),
                    allowRedirects = true,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull() ?: continue

            if (markers.isEmpty() || markers.any { body.contains(it, ignoreCase = true) }) {
                return normalized.trimEnd('/')
            }
        }
        return null
    }
}

/**
 * Process-local, bounded-by-usage TTL cache. It never persists account data,
 * cookies, tokens or permanent stream URLs across application restarts.
 */
object NeonMemoryCache {
    private data class Entry(
        val value: String,
        val expiresAt: Long,
    )

    private val values = LinkedHashMap<String, Entry>()
    private const val MAX_ENTRIES = 128

    @Synchronized
    fun get(key: String): String? {
        val entry = values[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            values.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: String, value: String, ttlMs: Long) {
        values.remove(key)
        while (values.size >= MAX_ENTRIES) {
            val eldest = values.entries.firstOrNull()?.key ?: break
            values.remove(eldest)
        }
        values[key] = Entry(
            value = value,
            expiresAt = System.currentTimeMillis() + ttlMs.coerceAtLeast(1_000L),
        )
    }

    @Synchronized
    fun clear() {
        values.clear()
    }
}

/**
 * Strict, shared relevance filter for provider search results.
 * Rejects homepage/catalogue cards when a source ignores its search query.
 */
private fun neonSearchNormalize(value: String): String {
    val lower = value.lowercase(java.util.Locale.ROOT).replace('ı', 'i')
    return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}

private fun neonSearchTokens(value: String): List<String> =
    neonSearchNormalize(value)
        .split(Regex("""\s+"""))
        .filter { token -> token.isNotBlank() && (token.length >= 2 || token.all { ch -> ch.isDigit() }) }

private fun neonTokenMatches(queryToken: String, titleToken: String): Boolean {
    if (queryToken == titleToken) return true
    return queryToken.length >= 4 &&
        titleToken.startsWith(queryToken) &&
        titleToken.length - queryToken.length in 1..2
}

/**
 * Match complete title words in the same order as the query.
 * A substring such as "man" inside "Superman" is not a valid match.
 * Extra title words may occur between query words ("House of the Dragon").
 */
private fun neonOrderedTokensMatch(queryTokens: List<String>, titleTokens: List<String>): Boolean {
    if (queryTokens.isEmpty() || titleTokens.isEmpty()) return false

    var nextTitleIndex = 0
    for (queryToken in queryTokens) {
        var matched = false
        for (index in nextTitleIndex until titleTokens.size) {
            if (neonTokenMatches(queryToken, titleTokens[index])) {
                nextTitleIndex = index + 1
                matched = true
                break
            }
        }
        if (!matched) return false
    }
    return true
}

/**
 * Strict title-only relevance filter. Do not match against URL paths: a site's
 * slug, category, search endpoint or unrelated query string can otherwise make
 * unrelated catalogue cards look like valid results.
 */
fun neonRelevantSearchResults(
    results: Iterable<com.lagradost.cloudstream3.SearchResponse>,
    query: String,
    limit: Int = 60,
): List<com.lagradost.cloudstream3.SearchResponse> {
    val normalizedQuery = neonSearchNormalize(query)
    val queryTokens = neonSearchTokens(query)
    if (normalizedQuery.isBlank() || queryTokens.isEmpty()) return emptyList()

    data class Ranked(
        val originalIndex: Int,
        val rank: Int,
        val result: com.lagradost.cloudstream3.SearchResponse,
    )

    val seen = HashSet<String>()
    return results.mapIndexedNotNull { index, result ->
        if (!seen.add(result.url)) return@mapIndexedNotNull null
        val title = neonSearchNormalize(result.name)
        val titleTokens = neonSearchTokens(result.name)
        if (title.isBlank()) return@mapIndexedNotNull null
        // Never use raw substring matching for relevance: query "man" must not
        // accept "Superman", and query "it" must not accept "Little Women".
        if (!neonOrderedTokensMatch(queryTokens, titleTokens)) return@mapIndexedNotNull null

        // Rank exact and phrase matches above other valid ordered-token matches.
        val phraseMatch = (" $title ").contains(" $normalizedQuery ")
        val rank = when {
            title == normalizedQuery -> 0
            title.startsWith("$normalizedQuery ") -> 1
            phraseMatch -> 2
            else -> 3
        }
        Ranked(index, rank, result)
    }.sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.originalIndex })
        .take(limit.coerceIn(1, 120))
        .map { it.result }
}
