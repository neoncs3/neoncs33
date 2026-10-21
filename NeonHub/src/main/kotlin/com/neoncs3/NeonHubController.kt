package com.neoncs3

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Plugin
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the original site implementations privately. CloudStream registers only
 * NeonHubAPI; site providers are called internally and never appear as separate APIs.
 */
object NeonHubController {
    private val sources = linkedMapOf<String, MainAPI>()
    private val urlOwners = ConcurrentHashMap<String, String>()
    private val dataOwners = ConcurrentHashMap<String, String>()

    @Synchronized
    fun install(plugin: Plugin) {
        // Remove any leftover APIs previously registered by an older NeonHub build.
        val pluginFile = plugin.filename
        if (pluginFile != null) {
            APIHolder.allProviders.toList()
                .filter { it.sourcePlugin == pluginFile }
                .forEach { api ->
                    APIHolder.removePluginMapping(api)
                    APIHolder.allProviders.remove(api)
                }
        }

        sources.clear()
        urlOwners.clear()
        dataOwners.clear()

        sources[NeonHubSettings.HUB_SOURCE_ASYAFILM] = AsyaFilmIzle()
        sources[NeonHubSettings.HUB_SOURCE_DIZIMAG] = AutoDdizimagCom()
        sources[NeonHubSettings.HUB_SOURCE_HDFILMDIZI] = AutoHdfilmdiziizleCom()
        sources[NeonHubSettings.HUB_SOURCE_CANLI] = CanliProvider()
        sources[NeonHubSettings.HUB_SOURCE_GOLGETV] = GolgeTVProvider()
        sources[NeonHubSettings.HUB_SOURCE_TVDIZILER] = AutoTvdizilerTv()
        sources[NeonHubSettings.HUB_SOURCE_DDIZI] = DDizi()
        sources[NeonHubSettings.HUB_SOURCE_DIZIBOXIZLE] = DiziBoxizle()
        sources[NeonHubSettings.HUB_SOURCE_DIZIDAD] = DiziDad()
        sources[NeonHubSettings.HUB_SOURCE_DIZIPAL] = DiziPal()
        sources[NeonHubSettings.HUB_SOURCE_DIZIGECESI] = Dizigecesi()
        sources[NeonHubSettings.HUB_SOURCE_DRAMADIZILERIM] = Dramadizilerim()
        sources[NeonHubSettings.HUB_SOURCE_FILMMODU] = FilmModu()
        sources[NeonHubSettings.HUB_SOURCE_JETFILMIZLE] = JetFilmizle()
        sources[NeonHubSettings.HUB_SOURCE_SETFILMIZLE] = SetFilmIzle()
        sources[NeonHubSettings.HUB_SOURCE_SINEMACX] = SinemaCX()
        sources[NeonHubSettings.HUB_SOURCE_TIZAM] = Tizam()
        sources[NeonHubSettings.HUB_SOURCE_WEBDRAMATURKEY] = WebDramaTurkey()

        // Apply CloudStream's provider-override values before routing by URL/name.
        sources.values.forEach { api -> runCatching { api.init() } }

        // This is the only MainAPI registered by NeonHub.
        plugin.registerMainAPI(NeonHubAPI())
    }

    @Synchronized
    fun enabledSources(): List<Pair<String, MainAPI>> =
        sources.entries
            .filter { NeonHubSettings.enabled(it.key) }
            .map { it.key to it.value }

    @Synchronized
    fun source(key: String): MainAPI? = sources[key]

    @Synchronized
    fun sourceKeyForUrl(url: String): String? {
        if (url.isBlank()) return null
        urlOwners[url]?.let { return it }

        // CloudStream can restore a result without the in-memory URL-owner map.
        // These internal GölgeTV routes are self-identifying and must still reach
        // the provider so channel lists and episode payloads can be loaded.
        if (url.startsWith("golgetv:", ignoreCase = true) ||
            url.startsWith("golgetv-channel:", ignoreCase = true) ||
            url.startsWith("golgetv-live:", ignoreCase = true)
        ) return NeonHubSettings.HUB_SOURCE_GOLGETV

        val direct = sources.entries
            .sortedByDescending { it.value.mainUrl.length }
            .firstOrNull { url.startsWith(it.value.mainUrl, ignoreCase = true) }
        if (direct != null) return direct.key

        val requestedHost = host(url) ?: return null
        sources.entries.firstOrNull { host(it.value.mainUrl) == requestedHost }?.key?.let { return it }

        // Keep support for older, indexed Dizimag URLs as the current provider domain changes.
        if (requestedHost == "dizimag.one") {
            return NeonHubSettings.HUB_SOURCE_DIZIMAG
        }
        return null
    }

    fun rememberUrl(key: String, url: String) {
        if (url.isNotBlank()) urlOwners[url] = key
    }

    fun rememberData(key: String, data: String) {
        if (data.isNotBlank()) dataOwners[data] = key
    }

    fun sourceKeyForData(data: String): String? = dataOwners[data]

    private fun host(url: String): String? =
        runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    /** Switches are read dynamically; no provider registration/restart is needed. */
    fun applySettings() {
        // NeonHubAPI reads enabledSources() for every request and homepage refresh.
    }
}
