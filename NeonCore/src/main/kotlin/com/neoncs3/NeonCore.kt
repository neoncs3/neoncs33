package com.neoncs3

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

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
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) {
            val scheme = runCatching { URI(baseUrl).scheme }.getOrNull() ?: "https"
            return "$scheme:$raw"
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

    protected fun neonIsMediaUrl(url: String): Boolean {
        val value = url.lowercase().substringBefore('#')
        return value.contains(".m3u8") ||
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
            "video[src], video source[src], [data-video], [data-file], [data-source], " +
                "[data-video-url], [data-stream], [data-hls], [data-url]"
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
        return document.select("iframe[src], iframe[data-src], iframe[data-url]")
            .mapNotNull {
                val value = it.attr("src")
                    .ifBlank { it.attr("data-src") }
                    .ifBlank { it.attr("data-url") }

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

        val link = newExtractorLink(
            source = sourceName,
            name = label,
            url = normalized,
            type = type,
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = neonHeaders + ("Referer" to referer)
        }

        callback(link)
        return true
    }

    protected suspend fun neonResolveLinks(
        data: String,
        sourceName: String = name,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        maxDepth: Int = 2,
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

            val cacheKey = "html:$normalized"
            val html = NeonMemoryCache.get(cacheKey) ?: runCatching {
                app.get(
                    normalized,
                    headers = neonHeaders + ("Referer" to referer),
                    referer = referer,
                    timeout = neonRequestTimeoutMs.toLong(),
                    allowRedirects = true,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull()?.also {
                NeonMemoryCache.put(cacheKey, it, neonCacheTtlMs)
            }

            if (html.isNullOrBlank()) return

            val document = Jsoup.parse(html, normalized)

            neonExtractMediaUrls(html).forEach { media ->
                if (!mediaSeen.add(media)) return@forEach
                if (neonEmitMedia(media, sourceName, normalized, callback)) {
                    found = true
                }
            }

            neonSubtitleUrls(document, normalized).forEach { (url, label) ->
                neonEmitSubtitle(url, label, normalized, subtitleCallback)
            }

            if (depth < maxDepth) {
                neonExtractIframeUrls(document, normalized).forEach { iframe ->
                    visit(iframe, normalized, depth + 1)
                }
            }
        }

        visit(root, mainUrl, 0)
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
                ).firstOrNull { it.isNotBlank() }

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
                )
            ) {
                found = true
            }
        }

        return found
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

        val html = runCatching {
            app.get(
                normalized,
                headers = neonHeaders + ("Referer" to referer),
                referer = referer,
                timeout = neonRequestTimeoutMs.toLong(),
                allowRedirects = true,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull() ?: return null

        NeonMemoryCache.put(key, html, ttlMs)
        return html
    }

    protected suspend fun neonCachedDocument(
        url: String,
        referer: String = mainUrl,
        ttlMs: Long = neonCacheTtlMs,
    ): Document? {
        return neonCachedHtml(url, referer, ttlMs)?.let { Jsoup.parse(it, neonNormalizeUrl(url, referer)) }
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
