package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Unified CloudStream entry point.
 *
 * Each website remains its own MainAPI provider, so site-specific catalog,
 * episode parsing, subtitles and playback logic stay separate.
 */
@CloudstreamPlugin
class NeonHubPlugin : Plugin() {
    override fun load(context: Context) {
        super.load(context)

        // NeonHub owns the only user-facing settings screen.

        // Only register site providers that are enabled in the NeonHub settings.
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_ASYAFILM)) {
            registerMainAPI(AsyaFilmIzle())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DIZIMAG)) {
            registerMainAPI(AutoDdizimagCom())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_HDFILMDIZI)) {
            registerMainAPI(AutoHdfilmdiziizleCom())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_CANLI)) {
            registerMainAPI(CanliProvider())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_TVDIZILER)) {
            registerMainAPI(AutoTvdizilerTv())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DDIZI)) {
            registerMainAPI(DDizi())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DIZIBOXIZLE)) {
            registerMainAPI(DiziBoxizle())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DIZIDAD)) {
            registerMainAPI(DiziDad())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DIZIPAL)) {
            registerMainAPI(DiziPal())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DIZIGECESI)) {
            registerMainAPI(Dizigecesi())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_DRAMADIZILERIM)) {
            registerMainAPI(Dramadizilerim())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_FILMMODU)) {
            registerMainAPI(FilmModu())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_JETFILMIZLE)) {
            registerMainAPI(JetFilmizle())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_SETFILMIZLE)) {
            registerMainAPI(SetFilmIzle())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_SINEMACX)) {
            registerMainAPI(SinemaCX())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_TIZAM)) {
            registerMainAPI(Tizam())
        }
        if (NeonHubSettings.enabled(NeonHubSettings.HUB_SOURCE_WEBDRAMATURKEY)) {
            registerMainAPI(WebDramaTurkey())
        }

        // Keep provider-specific extractors registered by the standalone plugins.
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(WebDramaTurkeyExtractor())

        // Register shared host extractors used by bundled website providers.
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(Wootly())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Videostr())
        registerExtractorAPI(Streameeeeee())
        registerExtractorAPI(PpzjYoutube())
        registerExtractorAPI(Asianload())
        registerExtractorAPI(Embedload())
        registerExtractorAPI(FlixCloud())
        registerExtractorAPI(VibePlayer())
        registerExtractorAPI(Bibiemb())
        registerExtractorAPI(Vivibebe())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Otakuvid())
        registerExtractorAPI(Otakuhg())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(Modiplay())
        registerExtractorAPI(Iqsmartgames())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())

        this.openSettings = { ctx: Context ->
            NeonHubSettings.showSettingsDialog(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
