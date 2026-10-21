package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * GölgeTV companion source for publicly accessible official TRT live channels.
 *
 * The APK contains authenticated/DRM-protected services as well. Those endpoints
 * are deliberately not extracted or reproduced here.
 */
class GolgeTVProvider : MainAPI() {
    override var mainUrl = "https://www.trt.com.tr"
    override var name = "GölgeTV - Açık Kanallar"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Canlı Kanallar"
    )

    private data class Channel(
        val title: String,
        val streamUrl: String,
        val logoUrl: String
    )

    // Public HLS endpoints on official TRT domains. Availability/geo rules remain
    // controlled by the broadcaster; no login, token, or DRM handling is implemented.
    private val channels = listOf(
        Channel("TRT 1", "https://tv-trt1.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt1.png"),
        Channel("TRT Haber", "https://tv-trthaber.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trthaber.png"),
        Channel("TRT Spor", "https://tv-trtspor1.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtspor.png"),
        Channel("TRT Spor 2", "https://tv-trtspor2.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtspor.png"),
        Channel("TRT Çocuk", "https://tv-trtcocuk.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtcocuk.png"),
        Channel("TRT Müzik", "https://tv-trtmuzik.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtmuzik.png"),
        Channel("TRT Belgesel", "https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtbelgesel.png"),
        Channel("TRT Avaz", "https://tv-trtavaz.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtavaz.png"),
        Channel("TRT Türk", "https://tv-trtturk.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtturk.png"),
        Channel("TRT Kurdî", "https://tv-trtkurdi.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtkurdi.png"),
        Channel("TRT World", "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtworld.png"),
        Channel("TRT Arabi", "https://tv-trtarabi.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtarabi.png"),
        Channel("TRT EBA İlkokul", "https://tv-e-okul00.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt1.png"),
        Channel("TRT EBA Ortaokul", "https://tv-e-okul01.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt1.png"),
        Channel("TRT EBA Lise", "https://tv-e-okul02.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trt1.png"),
        Channel("TRT Diyanet Çocuk", "https://tv-trtdiyanetcocuk.medya.trt.com.tr/master.m3u8",
            "https://www.trt.com.tr/Content/Images/Channel/trtcocuk.png")
    )

    private fun collectionSearchResult(): SearchResponse =
        newTvSeriesSearchResponse("GölgeTV - Açık Kanallar", mainUrl, TvType.TvSeries) {
            posterUrl = channels.firstOrNull()?.logoUrl
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return newHomePageResponse(
            request.name,
            listOf(collectionSearchResult()),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.isBlank() ||
            "gölgetv".contains(value, ignoreCase = true) ||
            "açık kanallar".contains(value, ignoreCase = true) ||
            channels.any { it.title.contains(value, ignoreCase = true) }
        ) {
            return listOf(collectionSearchResult())
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val episodes = channels.mapIndexed { index, channel ->
            newEpisode(channel.streamUrl) {
                name = channel.title
                season = 1
                episode = index + 1
                posterUrl = channel.logoUrl
            }
        }

        return newTvSeriesLoadResponse(
            "GölgeTV - Açık Kanallar",
            mainUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = channels.firstOrNull()?.logoUrl
            plot = "Tek listede resmî TRT canlı yayınları. Kanallar 1. sezonun bölümleri olarak listelenir. Oturum veya DRM korumalı ücretli yayınlar bu eklentiye dahil değildir."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = channels.firstOrNull { it.streamUrl == data } ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = channel.title,
                url = channel.streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                referer = "https://www.trt.com.tr/"
                quality = Qualities.Unknown.value
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://www.trt.com.tr/"
                )
            }
        )
        return true
    }
}
