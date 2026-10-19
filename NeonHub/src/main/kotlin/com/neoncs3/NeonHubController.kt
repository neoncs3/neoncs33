package com.neoncs3

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Adds/removes the existing website API instances from CloudStream's active
 * registry when a NeonHub switch is saved. This avoids application restarts.
 */
object NeonHubController {
    private var owner: Plugin? = null
    private val sources = linkedMapOf<String, MainAPI>()
    private val registeredKeys = linkedSetOf<String>()

    @Synchronized
    fun install(plugin: Plugin) {
        // Clean up stale instances if this classloader installs NeonHub again.
        sources.values.forEach { api ->
            APIHolder.removePluginMapping(api)
            APIHolder.allProviders.remove(api)
        }
        sources.clear()
        registeredKeys.clear()
        owner = plugin

        sources[NeonHubSettings.HUB_SOURCE_ASYAFILM] = AsyaFilmIzle()
        sources[NeonHubSettings.HUB_SOURCE_DIZIMAG] = AutoDdizimagCom()
        sources[NeonHubSettings.HUB_SOURCE_HDFILMDIZI] = AutoHdfilmdiziizleCom()
        sources[NeonHubSettings.HUB_SOURCE_CANLI] = CanliProvider()
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

        applySettings()
    }

    @Synchronized
    fun applySettings() {
        val plugin = owner ?: return
        sources.forEach { (key, api) ->
            val shouldBeRegistered = NeonHubSettings.enabled(key)
            val currentlyRegistered = key in registeredKeys
            when {
                shouldBeRegistered && !currentlyRegistered -> {
                    plugin.registerMainAPI(api)
                    registeredKeys += key
                }
                !shouldBeRegistered && currentlyRegistered -> {
                    APIHolder.removePluginMapping(api)
                    APIHolder.allProviders.remove(api)
                    registeredKeys -= key
                }
            }
        }
    }
}
