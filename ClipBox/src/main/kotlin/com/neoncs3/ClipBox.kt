package com.neoncs3

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * Standalone source reconstruction of the ClipBox provider found in ClipBox.cs3.
 * The original .kt source is not embedded in the .cs3 archive, so this file is
 * reconstructed from the compiled classes.dex and adapted to the com.neoncs3
 * CloudStream module structure.
 */
class ClipBox : MainAPI() {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    }

    override var mainUrl: String = "https://vixsrc.to"
    override var name: String = "ClipBox"
    override var lang: String = "tr"
    override val hasMainPage: Boolean = false
    override val supportedTypes: Set<TvType> = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

    private data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseLinkData(data: String): LinkData? {
        return runCatching {
            val json = JSONObject(data)

            LinkData(
                id = json.optString("id", "").toIntOrNull(),
                imdbId = json.optString("imdbId", "").takeIf { it.isNotBlank() },
                season = json.optString("season", "").toIntOrNull(),
                episode = json.optString("episode", "").toIntOrNull()
            )
        }.getOrNull()
    }

    private fun buildPageUrl(source: String): String {
        return when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> source

            source.startsWith("//") -> "https:$source"
            source.startsWith("/") -> "$mainUrl$source"
            else -> "$mainUrl/$source"
        }
    }

    private fun extractString(jsonText: String, key: String): String? {
        return runCatching {
            JSONObject(jsonText)
                .optString(key, "")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseLinkData(data) ?: return false

        val candidates = listOfNotNull(
            linkData.id?.toString(),
            linkData.imdbId
        ).distinct()

        if (candidates.isEmpty()) return false

        var found = false

        for (candidate in candidates) {
            val path = if (linkData.season != null && linkData.episode != null) {
                "/api/tv/$candidate/${linkData.season}/${linkData.episode}"
            } else {
                "/api/movie/$candidate"
            }

            val apiResponse = runCatching {
                app.get(
                    url = "$mainUrl$path",
                    headers = requestHeaders
                )
            }.getOrNull() ?: continue

            if (apiResponse.code != 200) continue

            val source = extractString(apiResponse.text, "src") ?: continue
            val pageUrl = buildPageUrl(source)

            val playerResponse = runCatching {
                app.get(
                    url = pageUrl,
                    headers = requestHeaders
                )
            }.getOrNull() ?: continue

            if (playerResponse.code != 200) continue

            val html = playerResponse.text

            val streamBaseUrl = Regex(
                pattern = """(?:url|file)\s*:\s*['\"]([^'\"]+)['\"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val token = Regex(
                pattern = """['\"]?token['\"]?\s*:\s*['\"]([^'\"]+)['\"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val expires = Regex(
                pattern = """['\"]?expires['\"]?\s*:\s*['\"]([^'\"]+)['\"]""",
                options = setOf(RegexOption.IGNORE_CASE)
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val separator = if (streamBaseUrl.contains('?')) '&' else '?'
            val streamUrl = buildString {
                append(streamBaseUrl)
                append(separator)
                append("token=")
                append(token)
                append("&expires=")
                append(expires)
                append("&h=1")
            }

            newExtractorLink(
                source = name,
                name = "ClipBox • VixSrc",
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$mainUrl/"
                headers = mapOf("User-Agent" to USER_AGENT)
                quality = Qualities.P1080.value
            }.also { link ->
                callback(link)
            }

            found = true
        }

        return found
    }
}
