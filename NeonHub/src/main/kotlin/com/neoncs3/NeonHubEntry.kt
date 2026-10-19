package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.neoncs3.settings.Settings
import kotlinx.coroutines.runBlocking

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

        // Keep CineStream's initialization and settings behavior.
        runBlocking { init() }
        Settings.initSeenProviders()

        // Only register site providers that are enabled in the NeonHub settings.
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_ASYAFILM)) {
            registerMainAPI(AsyaFilmIzle())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DIZIMAG)) {
            registerMainAPI(AutoDdizimagCom())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_HDFILMDIZI)) {
            registerMainAPI(AutoHdfilmdiziizleCom())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_CANLI)) {
            registerMainAPI(CanliProvider())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_TVDIZILER)) {
            registerMainAPI(AutoTvdizilerTv())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DDIZI)) {
            registerMainAPI(DDizi())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DIZIBOXIZLE)) {
            registerMainAPI(DiziBoxizle())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DIZIDAD)) {
            registerMainAPI(DiziDad())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DIZIPAL)) {
            registerMainAPI(DiziPal())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DIZIGECESI)) {
            registerMainAPI(Dizigecesi())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_DRAMADIZILERIM)) {
            registerMainAPI(Dramadizilerim())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_FILMMODU)) {
            registerMainAPI(FilmModu())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_JETFILMIZLE)) {
            registerMainAPI(JetFilmizle())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_SETFILMIZLE)) {
            registerMainAPI(SetFilmIzle())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_SINEMACX)) {
            registerMainAPI(SinemaCX())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_TIZAM)) {
            registerMainAPI(Tizam())
        }
        if (Settings.neonHubSourceEnabled(Settings.HUB_SOURCE_WEBDRAMATURKEY)) {
            registerMainAPI(WebDramaTurkey())
        }

        // Keep provider-specific extractors registered by the standalone plugins.
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(WebDramaTurkeyExtractor())

        // CineStream's provider toggles are retained for its three catalogs.
        if (Settings.neonHubSourceEnabled(Settings.PROVIDER_CINESTREAM)) {
            registerMainAPI(CineStreamProvider())
        }
        if (Settings.neonHubSourceEnabled(Settings.PROVIDER_SIMKL)) {
            registerMainAPI(CineSimklProvider())
        }
        if (Settings.neonHubSourceEnabled(Settings.PROVIDER_TMDB)) {
            registerMainAPI(CineTmdbProvider())
        }

        // Keep the extra host extractors that CineStream depends on.
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
            Settings.showSettingsDialog(ctx) {
                MainActivity.reloadHomeEvent.invoke(true)
            }
        }
    }
}
