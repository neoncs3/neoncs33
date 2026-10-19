package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Interceptor

/**
 * The single provider exposed to CloudStream.
 *
 * Searches and homepage sections are gathered from the enabled site implementations.
 * Loading and playback are routed back to the originating site by internal route tags.
 */
class NeonHubAPI : MainAPI() {
    override var name = "NeonHub"
    override var mainUrl = "https://neonhub.invalid"
    override var lang = "tr"
    override var canBeOverridden = false

    override val hasMainPage: Boolean
        get() = NeonHubController.enabledSources().any { it.second.hasMainPage }

    override val hasQuickSearch: Boolean
        get() = NeonHubController.enabledSources().any { it.second.hasQuickSearch }

    override val mainPage: List<MainPageData>
        get() = NeonHubController.enabledSources().flatMap { (key, source) ->
            if (!source.hasMainPage) emptyList()
            else source.mainPage
                .filter { it.data.isNotBlank() }
                .map { section ->
                    MainPageData(
                        name = source.name + " • " + section.name,
                        data = packPage(key, section.name, section.data),
                        horizontalImages = section.horizontalImages
                    )
                }
        }

    override val supportedTypes: Set<TvType>
        get() = NeonHubController.enabledSources()
            .flatMap { it.second.supportedTypes }
            .toSet()
            .ifEmpty { setOf(TvType.Movie, TvType.TvSeries) }

    override val usesWebView: Boolean
        get() = NeonHubController.enabledSources().any { it.second.usesWebView }

    override val hasDownloadSupport: Boolean
        get() = NeonHubController.enabledSources().all { it.second.hasDownloadSupport }

    override val hasChromecastSupport: Boolean
        get() = NeonHubController.enabledSources().all { it.second.hasChromecastSupport }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val route = unpackPage(request.data) ?: return null
        val source = NeonHubController.source(route.key) ?: return null
        val mappedRequest = MainPageRequest(
            name = route.sectionName,
            data = route.sectionData,
            horizontalImages = request.horizontalImages
        )
        val response = runCatching { source.getMainPage(page, mappedRequest) }.getOrNull()
            ?: return null

        val lists = response.items.map { section ->
            HomePageList(
                name = source.name + " • " + section.name,
                list = section.list.map { rebrandSearchResponse(it, route.key) },
                isHorizontalImages = section.isHorizontalImages
            )
        }
        return newHomePageResponse(lists, response.hasNext)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val limiter = Semaphore(4)
        val results = coroutineScope {
            NeonHubController.enabledSources().map { (key, source) ->
                async {
                    limiter.withPermit {
                        val response = runCatching { source.search(query, page) }.getOrNull()
                        response?.let { Triple(key, it.items, it.hasNext) }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val items = results.flatMap { result ->
            result.second.map { rebrandSearchResponse(it, result.first) }
        }
        if (items.isEmpty()) return null
        return newSearchResponseList(items, results.any { it.third })
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val limiter = Semaphore(4)
        val results = coroutineScope {
            NeonHubController.enabledSources()
                .filter { it.second.hasQuickSearch }
                .map { (key, source) ->
                    async {
                        limiter.withPermit {
                            val found = runCatching { source.quickSearch(query) }.getOrNull()
                            found?.let { key to it }
                        }
                    }
                }.awaitAll().filterNotNull()
        }

        val items = results.flatMap { (key, entries) ->
            entries.map { rebrandSearchResponse(it, key) }
        }
        return items.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val key = NeonHubController.sourceKeyForUrl(url) ?: return null
        val source = NeonHubController.source(key) ?: return null
        NeonHubController.rememberUrl(key, url)

        val response = runCatching { source.load(url) }.getOrNull() ?: return null
        NeonHubController.rememberUrl(key, response.url)
        response.apiName = name
        response.recommendations = response.recommendations?.map {
            rebrandSearchResponse(it, key)
        }

        @Suppress("DEPRECATION_ERROR")
        when (response) {
            is MovieLoadResponse -> {
                NeonHubController.rememberData(key, response.dataUrl)
                response.dataUrl = wrapData(key, response.dataUrl)
            }
            is LiveStreamLoadResponse -> {
                NeonHubController.rememberData(key, response.dataUrl)
                response.dataUrl = wrapData(key, response.dataUrl)
            }
            is TvSeriesLoadResponse -> {
                response.episodes = response.episodes.map { episode ->
                    NeonHubController.rememberData(key, episode.data)
                    episode.copy(data = wrapData(key, episode.data))
                }
            }
            is AnimeLoadResponse -> {
                response.episodes = response.episodes.mapValues { (_, episodes) ->
                    episodes.map { episode ->
                        NeonHubController.rememberData(key, episode.data)
                        episode.copy(data = wrapData(key, episode.data))
                    }
                }.toMutableMap()
            }
            is TorrentLoadResponse -> {
                // Torrent metadata is consumed directly by CloudStream; preserve its magnet/torrent values.
                response.magnet?.let { NeonHubController.rememberData(key, it) }
                response.torrent?.let { NeonHubController.rememberData(key, it) }
            }
        }
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val route = unpackData(data)
        val key = route?.first ?: NeonHubController.sourceKeyForData(data)
            ?: NeonHubController.sourceKeyForUrl(data)
            ?: return false
        val source = NeonHubController.source(key) ?: return false
        val payload = route?.second ?: data

        return runCatching {
            source.loadLinks(payload, isCasting, subtitleCallback) { link ->
                NeonHubController.rememberUrl(key, link.url)
                callback(link)
            }
        }.getOrDefault(false)
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val key = NeonHubController.sourceKeyForUrl(extractorLink.url) ?: return null
        return NeonHubController.source(key)?.getVideoInterceptor(extractorLink)
    }

    override suspend fun extractorVerifierJob(extractorData: String?) {
        if (extractorData.isNullOrBlank()) return
        val route = unpackData(extractorData)
        val key = route?.first ?: NeonHubController.sourceKeyForData(extractorData) ?: return
        val source = NeonHubController.source(key) ?: return
        runCatching { source.extractorVerifierJob(route?.second ?: extractorData) }
    }

    @Suppress("DEPRECATION_ERROR")
    private fun rebrandSearchResponse(response: SearchResponse, key: String): SearchResponse {
        NeonHubController.rememberUrl(key, response.url)
        return when (response) {
            is MovieSearchResponse -> response.copy(apiName = name)
            is TvSeriesSearchResponse -> response.copy(apiName = name)
            is AnimeSearchResponse -> response.copy(apiName = name)
            is LiveSearchResponse -> response.copy(apiName = name)
            is TorrentSearchResponse -> response.copy(apiName = name)
            else -> response
        }
    }

    private data class PageRoute(
        val key: String,
        val sectionName: String,
        val sectionData: String
    )

    private fun packPage(key: String, sectionName: String, sectionData: String): String =
        PAGE_PREFIX + key + "::" + sectionName + "::" + sectionData

    private fun unpackPage(value: String): PageRoute? {
        if (!value.startsWith(PAGE_PREFIX)) return null
        val rest = value.removePrefix(PAGE_PREFIX)
        val first = rest.indexOf("::")
        if (first < 1) return null
        val second = rest.indexOf("::", first + 2)
        if (second < 0) return null
        return PageRoute(
            key = rest.substring(0, first),
            sectionName = rest.substring(first + 2, second),
            sectionData = rest.substring(second + 2)
        )
    }

    private fun wrapData(key: String, value: String): String =
        DATA_PREFIX + key + "::" + value

    private fun unpackData(value: String): Pair<String, String>? {
        if (!value.startsWith(DATA_PREFIX)) return null
        val rest = value.removePrefix(DATA_PREFIX)
        val divider = rest.indexOf("::")
        if (divider < 1) return null
        val key = rest.substring(0, divider)
        if (NeonHubController.source(key) == null) return null
        return key to rest.substring(divider + 2)
    }

    private companion object {
        const val PAGE_PREFIX = "__NEONHUB_PAGE__::"
        const val DATA_PREFIX = "__NEONHUB_DATA__::"
    }
}
