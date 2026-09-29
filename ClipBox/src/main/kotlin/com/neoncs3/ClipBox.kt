package com.neoncs3
import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.parseJson
import dev.wiojelt.turksinema.vendor.bronze_cinestream.com.megix.CineTmdbProvider

/**
 * Reconstructed from the supplied ClipBox.cs3/classes.dex.
 *
 * The original source was not embedded in the .cs3 archive, so this is a
 * source-level reconstruction of the compiled ClipBoxProvider logic.
 */
class ClipBoxProvider : CineTmdbProvider() {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    }

    override var lang: String = "tr"
    override var name: String = "ClipBox"

    private val host by lazy {
        String(
            Base64.decode("aHR0cHM6Ly92aXhzcmMudG8=", Base64.DEFAULT),
            Charsets.UTF_8
        )
    }

    private fun getHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$host/",
        "Accept" to "application/json, text/javascript, */*; q=0.01"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (super.loadLinks(data, isCasting, subtitleCallback, callback)) {
            return true
        }

        val item = data.parseJson<CineTmdbProvider.LinkData>() ?: return false

        val candidates = listOfNotNull(
            item.id?.toString(),
            item.imdbId
        ).distinct()

        var found = false

        for (candidate in candidates) {
            val path = if (item.season != null && item.episode != null) {
                "/api/tv/$candidate/${item.season}/${item.episode}"
            } else {
                "/api/movie/$candidate"
            }

            val apiResponse = runCatching {
                app.get("$host$path", headers = getHeaders())
            }.getOrNull() ?: continue

            if (apiResponse.code != 200) continue

            val src = runCatching {
                apiResponse.text.parseJson<ClipBoxSource>()?.src
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue

            val pageUrl = if (src.startsWith("http")) {
                src
            } else {
                "$host$src"
            }

            val page = runCatching {
                app.get(pageUrl, headers = getHeaders())
            }.getOrNull() ?: continue

            if (page.code != 200) continue

            val pageContent = page.text

            val streamBaseUrl = Regex(
                "(?:url|file)\\s*:\\s*['\"]([^'\"]+)['\"]"
            ).find(pageContent)?.groupValues?.getOrNull(1) ?: continue

            val token = Regex(
                "['\"]?token['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]"
            ).find(pageContent)?.groupValues?.getOrNull(1) ?: continue

            val expiresIn = Regex(
                "['\"]?expires['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]"
            ).find(pageContent)?.groupValues?.getOrNull(1) ?: continue

            val separator = if (streamBaseUrl.contains('?')) '&' else '?'

            val stream = buildString {
                append(streamBaseUrl)
                append(separator)
                append("token=")
                append(token)
                append("&expires=")
                append(expiresIn)
                append("&h=1")
            }

            val link = newExtractorLink(
                source = name,
                name = "ClipBox • VixSrc",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "$host/"
                headers = mapOf("User-Agent" to USER_AGENT)
                quality = Qualities.P1080.value
            }

            callback(link)
            found = true
        }

        return found
    }
}

data class ClipBoxSource(
    val src: String
)
