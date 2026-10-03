package com.neoncs3

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.newExtractorLink

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val pixelId = url.substringAfterLast("/")
            .substringBefore("?")
            .trim()

        if (pixelId.isBlank()) return

        val downloadLink = "$mainUrl/api/file/$pixelId?download"

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = downloadLink,
                type = INFER_TYPE,
            ) {
                referer = url
                quality = Qualities.Unknown.value
            }
        )
    }
}
