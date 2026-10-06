package com.neoncs3

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
        val normalized = neonCleanText(raw).orEmpty()
        if (normalized.isBlank()) return emptyList()

        val directPattern = Regex(
            """(?i)(?:https?:)?//[^"'<>\s]+?(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov)(?:\?[^"'<>\s]*)?"""
        )

        return directPattern
            .findAll(normalized)
            .mapNotNull {
                neonNormalizeUrl(it.value)
                    .trimEnd('"', '\'', ')', ']', '}', ',', ';')
                    .takeIf(::neonIsMediaUrl)
            }
            .distinct()
            .toList()
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
                    timeout = neonRequestTimeoutMs,
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
                timeout = neonRequestTimeoutMs,
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
                    timeout = timeoutMs,
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

    private val values = ConcurrentHashMap<String, Entry>()

    fun get(key: String): String? {
        val entry = values[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            values.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: String, ttlMs: Long) {
        values[key] = Entry(
            value = value,
            expiresAt = System.currentTimeMillis() + ttlMs.coerceAtLeast(1_000L),
        )
    }

    fun clear() {
        values.clear()
    }
}
