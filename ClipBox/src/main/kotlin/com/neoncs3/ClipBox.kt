package com.neoncs3

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Standalone ClipBox provider.
 *
 * TMDB is used for the catalog/search/home-page metadata through the
 * CloudStream TmdbProvider. ClipBox/VixSrc is used only for playback.
 */
class ClipBox : TmdbProvider() {

    companion object {
        private const val VIXSRC_URL = "https://vixsrc.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    override var name: String = "ClipBox"
    override var lang: String = "tr"
    override val hasMainPage: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private data class ClipLinkData(
        val id: String,
        val isTv: Boolean,
        val season: Int? = null,
        val episode: Int? = null
    )

    /**
     * TmdbProvider implementations can use slightly different dataUrl JSON
     * layouts between CloudStream versions. Accept the common field names and
     * also fall back to parsing URLs/plain numeric ids.
     */
    private fun parseLinkData(data: String): ClipLinkData? {
        val raw = data.trim()

        runCatching {
            val obj = JSONObject(raw)

            val id = sequenceOf(
                obj.optString("tmdbId"),
                obj.optString("tmdb_id"),
                obj.optString("id"),
                obj.optString("mediaId"),
                obj.optString("media_id")
            ).firstOrNull { value ->
                value.isNotBlank() && value != "null" && value.toIntOrNull() != null
            }

            val type = sequenceOf(
                obj.optString("type"),
                obj.optString("mediaType"),
                obj.optString("media_type")
            ).firstOrNull { it.isNotBlank() && it != "null" }

            val season = sequenceOf(
                obj.optInt("season", -1),
                obj.optInt("seasonNumber", -1),
                obj.optInt("season_number", -1)
            ).firstOrNull { it >= 0 }

            val episode = sequenceOf(
                obj.optInt("episode", -1),
                obj.optInt("episodeNumber", -1),
                obj.optInt("episode_number", -1)
            ).firstOrNull { it >= 0 }

            if (!id.isNullOrBlank()) {
                val isTv = type?.contains("tv", ignoreCase = true) == true ||
                    type?.contains("serie", ignoreCase = true) == true ||
                    season != null || episode != null

                return ClipLinkData(
                    id = id,
                    isTv = isTv,
                    season = season,
                    episode = episode
                )
            }
        }.getOrNull()

        Regex(
            pattern = "(?:https?:\\/\\/[^\\s]+)?/(movie|tv)/(\\d+)(?:/(\\d+)/(\\d+))?",
            options = setOf(RegexOption.IGNORE_CASE)
        ).find(raw)?.let { match ->
            val kind = match.groupValues[1]
            val id = match.groupValues[2]
            val season = match.groupValues.getOrNull(3)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(4)?.toIntOrNull()
            return ClipLinkData(
                id = id,
                isTv = kind.equals("tv", ignoreCase = true),
                season = season,
                episode = episode
            )
        }

        raw.toIntOrNull()?.let { id ->
            return ClipLinkData(id.toString(), isTv = false)
        }

        return null
    }

    /**
     * Extract a string from a small JSON object. Kept separate so malformed
     * player responses do not abort the whole provider.
     */
    private fun jsonString(json: String, key: String): String? {
        return runCatching {
            JSONObject(json).optString(key, "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun cleanUrl(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    private fun buildPlayerUrl(link: ClipLinkData): String {
        return if (link.isTv && link.season != null && link.episode != null) {
            "$VIXSRC_URL/tv/${link.id}/${link.season}/${link.episode}"
        } else {
            "$VIXSRC_URL/movie/${link.id}"
        }
    }

    private fun extractStreamUrl(html: String): String? {
        val patterns = listOf(
            // JSON/object notation: url: "..." or file: "..."
            Regex(
                pattern = "(?:\\\"|')?(?:url|file)(?:\\\"|')?\\s*[:=]\\s*(?:\\\"|')([^\\\"']+)(?:\\\"|')",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            // Plain assignment / embedded player variables.
            Regex(
                pattern = "(?:const|let|var)\\s+(?:url|file)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            // Direct HLS URL anywhere in the document.
            Regex(
                pattern = "https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?",
                options = setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val candidate = cleanUrl(
                match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() }
                    ?: match.value
            )
            if (candidate.contains(".m3u8", ignoreCase = true) ||
                candidate.contains("/hls", ignoreCase = true)
            ) {
                return candidate
            }
        }

        return null
    }

    private fun extractToken(html: String): String? {
        val patterns = listOf(
            Regex(
                pattern = "(?:\\\"|')token(?:\\\"|')?\\s*[:=]\\s*['\\\"]([^'\\\"]+)['\\\"]",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                pattern = "(?:\\\"|')token(?:\\\"|')?\\s*[:=]\\s*([^,}\\s]+)",
                options = setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val token = match.groupValues.getOrNull(1)?.trim()
            if (!token.isNullOrBlank()) return cleanUrl(token)
        }
        return null
    }

    private fun extractExpires(html: String): String? {
        val patterns = listOf(
            Regex(
                pattern = "(?:\\\"|')expires(?:\\\"|')?\\s*[:=]\\s*['\\\"]([^'\\\"]+)['\\\"]",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                pattern = "(?:\\\"|')expires(?:\\\"|')?\\s*[:=]\\s*([^,}\\s]+)",
                options = setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            val expires = match.groupValues.getOrNull(1)?.trim()
            if (!expires.isNullOrBlank()) return cleanUrl(expires)
        }
        return null
    }

    private fun appendParameter(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$key=$value"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseLinkData(data) ?: return false
        val playerUrl = buildPlayerUrl(linkData)

        val response = runCatching {
            app.get(
                url = playerUrl,
                headers = PAGE_HEADERS
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..399) return false

        val html = response.text

        var streamUrl = extractStreamUrl(html)

        // Current VixSrc players expose url/file together with token/expires.
        if (streamUrl != null) {
            val token = extractToken(html)
            val expires = extractExpires(html)

            if (!token.isNullOrBlank() && !expires.isNullOrBlank() &&
                !streamUrl.contains("token=", ignoreCase = true)
            ) {
                streamUrl = appendParameter(streamUrl, "token", token)
                streamUrl = appendParameter(streamUrl, "expires", expires)
                streamUrl = appendParameter(streamUrl, "h", "1")
                streamUrl = appendParameter(streamUrl, "lang", "en")
            }
        }

        // Final fallback: some pages include the complete signed HLS URL.
        if (streamUrl == null) {
            streamUrl = Regex(
                pattern = "https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?",
                options = setOf(RegexOption.IGNORE_CASE)
            ).find(html)?.value?.let(::cleanUrl)
        }

        streamUrl = streamUrl?.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        } ?: return false

        newExtractorLink(
            source = name,
            name = "ClipBox • VixSrc",
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            referer = playerUrl
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to playerUrl,
                "Origin" to VIXSRC_URL,
                "Accept" to "*/*"
            )
            quality = Qualities.Unknown.value
        }.also(callback)

        return true
    }
}
