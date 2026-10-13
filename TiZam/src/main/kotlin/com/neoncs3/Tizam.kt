package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * BURAYA KENDİ SAĞLAYICI KODUNU YAZ.
 *
 * .cs3 derlenmiş bir pakettir; orijinal Kotlin kaynağı içinde yoktur.
 * Orijinal kaynak elinde yoksa jadx ile classes.dex'i açıp mantığı
 * buraya taşıyabilirsin (README'ye bak).
 */
class Tizam : MainAPI() {
    override var mainUrl = "https://example.com" // <- gerçek adresi yaz
    override var name = "Tizam"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // TODO: request.data + sayfa numarası ile listeyi çek, newHomePageResponse döndür
        return newHomePageResponse(request.name, emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // TODO: newMovieLoadResponse(...)
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // TODO: newExtractorLink(...) ile callback çağır
        return false
    }
}
