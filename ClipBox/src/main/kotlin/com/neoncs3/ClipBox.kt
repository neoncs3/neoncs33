package com.neoncs3

import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * ClipBox
 *
 * TMDB handles catalog/search/metadata.
 * VixSrc is used for the actual video stream.
 *
 * The original ClipBox DEX uses this flow:
 *
 * TMDB ID + IMDb ID
 *        ->
 * /api/movie/{id}
 * /api/tv/{id}/{season}/{episode}
 *        ->
 * JSON { "src": "..." }
 *        ->
 * VixSrc player page
 *        ->
 * url/file + token + expires
 *        ->
 * signed HLS URL
 */
class ClipBox : TmdbProvider() {

    companion object {
        private const val VIXSRC_URL = "https://vixsrc.to"

        // This matches the original ClipBox binary's request UA.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

        // Used when requesting the VixSrc API and player pages.
        private val REQUEST_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$VIXSRC_URL/",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )
    }

    override var name: String = "ClipBox"
    override var lang: String = "tr"

    // Keep ClipBox visible in the provider/source selector.
    override val hasMainPage: Boolean = true
    override val providerType = ProviderType.DirectProvider
    override val hasQuickSearch: Boolean = true

    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private data class ClipLinkData(
        val tmdbId: String?,
        val imdbId: String?,
        val season: Int?,
        val episode: Int?
    )

    /**
     * TmdbProvider's normal LoadResponse uses TmdbLink JSON:
     *
     * {
     *   "imdbID": "tt....",
     *   "tmdbID": 12345,
     *   "episode": 1,
     *   "season": 1,
     *   "movieName": "..."
     * }
     *
     * Accept that exact format plus a few compatible variants.
     */
    private fun parseLinkData(data: String): ClipLinkData? {
        val raw = data.trim()

        runCatching {
            val obj = JSONObject(raw)

            val tmdb = sequenceOf(
                obj.optString("tmdbID"),
                obj.optString("tmdbId"),
                obj.optString("tmdb_id"),
                obj.optString("id"),
                obj.optString("mediaId"),
                obj.optString("media_id")
            ).firstOrNull {
                it.isNotBlank() &&
                    it != "null" &&
                    it.toIntOrNull() != null
            }

            val imdb = sequenceOf(
                obj.optString("imdbID"),
                obj.optString("imdbId"),
                obj.optString("imdb_id"),
                obj.optString("imdb"),
                obj.optString("externalId")
            ).firstOrNull {
                it.isNotBlank() && it != "null"
            }

            val season = sequenceOf(
                obj.optIntOrNull("season"),
                obj.optIntOrNull("seasonNumber"),
                obj.optIntOrNull("season_number")
            ).firstOrNull()

            val episode = sequenceOf(
                obj.optIntOrNull("episode"),
                obj.optIntOrNull("episodeNumber"),
                obj.optIntOrNull("episode_number")
            ).firstOrNull()

            if (tmdb != null || imdb != null) {
                return ClipLinkData(
                    tmdbId = tmdb,
                    imdbId = imdb,
                    season = season,
                    episode = episode
                )
            }
        }

        // Fallback for data that may simply contain a TMDB URL.
        Regex(
            pattern = """themoviedb\.org/(movie|tv)/(\d+)""",
            options = setOf(RegexOption.IGNORE_CASE)
        ).find(raw)?.let { match ->
            val id = match.groupValues.getOrNull(2) ?: return@let

            return ClipLinkData(
                tmdbId = id,
                imdbId = null,
                season = null,
                episode = null
            )
        }

        // Fallback for an explicit VixSrc URL.
        Regex(
            pattern = """(?:https?://[^/]+)?/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""",
            options = setOf(RegexOption.IGNORE_CASE)
        ).find(raw)?.let { match ->
            return ClipLinkData(
                tmdbId = match.groupValues.getOrNull(2),
                imdbId = null,
                season = match.groupValues.getOrNull(3)?.toIntOrNull(),
                episode = match.groupValues.getOrNull(4)?.toIntOrNull()
            )
        }

        return null
    }

    /**
     * JSONObject has no optIntOrNull on all Android/API combinations used
     * by CloudStream, so keep this local and version-safe.
     */
    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null

        val value = optString(key, "").trim()
        if (value.isBlank() || value == "null") return null

        return value.toIntOrNull()
    }

    private fun cleanValue(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    /**
     * The original ClipBox DEX parses the VixSrc API response as a list of
     * ClipBoxSource objects, each containing a `src` field.
     *
     * Example: [{"src":"/movie/..."}]
     *
     * Accept that format, plus a few wrapped variants for compatibility.
     */
    private fun extractSrc(responseText: String): List<String> {
        val result = mutableListOf<String>()
        val text = responseText.trim()

        // The original ClipBox DEX deserializes the API response as:
        // List<ClipBoxSource>, where each object contains a single "src" field.
        runCatching {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val src = item.optString("src", "").trim()
                    if (src.isNotBlank()) result += cleanValue(src)
                }
            } else if (text.startsWith("{")) {
                val obj = JSONObject(text)
                val src = obj.optString("src", "").trim()
                if (src.isNotBlank()) result += cleanValue(src)

                // Some revisions wrap the source list in a data/results/sources array.
                for (key in listOf("data", "results", "sources")) {
                    val array = obj.optJSONArray(key) ?: continue
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val itemSrc = item.optString("src", "").trim()
                        if (itemSrc.isNotBlank()) result += cleanValue(itemSrc)
                    }
                }
            }
        }

        // Fallback for JSON-like responses that are not valid JSON.
        if (result.isEmpty()) {
            Regex(
                pattern = """[\\\"']?src[\\\"']?\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']""",
                options = setOf(RegexOption.IGNORE_CASE)
            ).findAll(text).forEach { match ->
                match.groupValues.getOrNull(1)?.let { value ->
                    if (value.isNotBlank()) result += cleanValue(value)
                }
            }
        }

        return result.distinct()
    }

    private fun toAbsoluteUrl(value: String): String {
        val url = cleanValue(value)

        return when {
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url

            url.startsWith("//") -> "https:$url"

            url.startsWith("/") -> "$VIXSRC_URL$url"

            else -> "$VIXSRC_URL/${url.trimStart('/')}"
        }
    }

    /**
     * Exact URL pattern used by the original ClipBox implementation.
     */
    private fun extractVideoUrl(html: String): String? {
        val pattern = Regex(
            pattern = """(?:url|file)\s*:\s*['"]([^'"]+)['"]""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        pattern.find(html)?.groupValues?.getOrNull(1)?.let {
            return cleanValue(it)
        }

        // Secondary patterns for quoted JSON/property notation.
        Regex(
            pattern = """["'](?:url|file)["']\s*:\s*["']([^"']+)["']""",
            options = setOf(RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.getOrNull(1)?.let {
            return cleanValue(it)
        }

        // Last fallback: an already-complete HLS URL in page HTML.
        Regex(
            pattern = """https?://[^"'<>\\s]+\.m3u8(?:\?[^"'<>\\s]*)?""",
            options = setOf(RegexOption.IGNORE_CASE)
        ).find(html)?.value?.let {
            return cleanValue(it)
        }

        return null
    }

    private fun extractToken(html: String): String? {
        val patterns = listOf(
            Regex(
                pattern = """['"]?token['"]?\s*:\s*['"]([^'"]+)['"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                pattern = """['"]?token['"]?\s*=\s*['"]([^'"]+)['"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let {
                return cleanValue(it)
            }
        }

        return null
    }

    private fun extractExpires(html: String): String? {
        val patterns = listOf(
            Regex(
                pattern = """['"]?expires['"]?\s*:\s*['"]([^'"]+)['"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            ),
            Regex(
                pattern = """['"]?expires['"]?\s*=\s*['"]([^'"]+)['"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            )
        )

        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let {
                return cleanValue(it)
            }
        }

        return null
    }

    private fun appendParameter(
        url: String,
        key: String,
        value: String
    ): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url$separator$key=$value"
    }

    /**
     * Build the ClipBox/VixSrc API route.
     *
     * The original DEX attempts both TMDB and IMDb IDs and removes duplicates.
     */
    private fun buildApiPaths(data: ClipLinkData): List<String> {
        val ids = listOfNotNull(data.tmdbId, data.imdbId).distinct()

        if (ids.isEmpty()) return emptyList()

        return if (data.season != null && data.episode != null) {
            ids.map { id ->
                "$VIXSRC_URL/api/tv/$id/${data.season}/${data.episode}"
            }
        } else {
            ids.map { id ->
                "$VIXSRC_URL/api/movie/$id"
            }
        }
    }

    /**
     * Ask the VixSrc API first, then resolve its "src" player page.
     *
     * This is the important difference from the previous version:
     * do NOT start with /movie/{id} or /tv/{id}/{season}/{episode}.
     * ClipBox's original binary first calls the /api/... endpoint.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseLinkData(data) ?: return false

        val apiPaths = buildApiPaths(linkData)

        for (apiUrl in apiPaths) {
            val apiResponse = runCatching {
                app.get(
                    url = apiUrl,
                    headers = REQUEST_HEADERS
                )
            }.getOrNull() ?: continue

            if (apiResponse.code !in 200..399) continue

            val sources = extractSrc(apiResponse.text)
            if (sources.isEmpty()) continue

            for (source in sources) {
                val playerUrl = toAbsoluteUrl(source)

                // Never send the TMDB page URL to CloudStream. Only resolve the
                // actual VixSrc source returned by /api/movie or /api/tv.
                if (!playerUrl.contains("vixsrc.to", ignoreCase = true)) continue

                val pageResponse = runCatching {
                    app.get(
                        url = playerUrl,
                        headers = REQUEST_HEADERS
                    )
                }.getOrNull() ?: continue

                if (pageResponse.code !in 200..399) continue

                val html = pageResponse.text
                var videoUrl = extractVideoUrl(html) ?: continue

                val token = extractToken(html)
                val expires = extractExpires(html)

                /*
                 * Match the original binary:
                 *
                 * {url}?token={token}&expires={expires}&h=1
                 *
                 * Note: the original ClipBox did NOT append lang=en.
                 */
                if (!token.isNullOrBlank() &&
                    !expires.isNullOrBlank() &&
                    !videoUrl.contains("token=", ignoreCase = true)
                ) {
                    videoUrl = appendParameter(videoUrl, "token", token)
                    videoUrl = appendParameter(videoUrl, "expires", expires)
                    videoUrl = appendParameter(videoUrl, "h", "1")
                }

                if (!videoUrl.startsWith("http://", ignoreCase = true) &&
                    !videoUrl.startsWith("https://", ignoreCase = true)
                ) {
                    videoUrl = toAbsoluteUrl(videoUrl)
                }

                // Only accept actual stream URLs.
                if (!videoUrl.contains(".m3u8", ignoreCase = true) &&
                    !videoUrl.contains("/hls", ignoreCase = true)
                ) {
                    continue
                }

                newExtractorLink(
                    source = name,
                    name = "ClipBox • VixSrc",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    // Original ClipBox uses the VixSrc host as the Referer.
                    referer = "$VIXSRC_URL/"
                    headers = mapOf(
                        "User-Agent" to USER_AGENT
                    )
                    quality = Qualities.Unknown.value
                }.let(callback)

                return true
            }
        }

        /*
         * Legacy fallback is deliberately disabled. The previous version
         * could fall back to /movie/{id} or /tv/{id}/..., which can return a
         * browser/TMDB page instead of a playable stream.
         */
        return false

    }
}
