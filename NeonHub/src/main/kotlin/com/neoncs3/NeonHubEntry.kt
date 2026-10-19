package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
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

        registerMainAPI(AsyaFilmIzle())
        registerMainAPI(AutoDdizimagCom())
        registerMainAPI(AutoHdfilmdiziizleCom())
        registerMainAPI(CanliProvider())
        registerMainAPI(AutoTvdizilerTv())
        registerMainAPI(DDizi())
        registerMainAPI(DiziBoxizle())
        registerMainAPI(DiziDad())
        registerMainAPI(DiziPal())
        registerMainAPI(Dizigecesi())
        registerMainAPI(Dramadizilerim())
        registerMainAPI(FilmModu())
        registerMainAPI(JetFilmizle())
        registerMainAPI(SetFilmIzle())
        registerMainAPI(SinemaCX())
        registerMainAPI(Tizam())
        registerMainAPI(WebDramaTurkey())

        // CineStream's provider toggles are retained for its three catalogs.
        if (getKey<Boolean>(Settings.PROVIDER_CINESTREAM) ?: true) {
            registerMainAPI(CineStreamProvider())
        }
        if (getKey<Boolean>(Settings.PROVIDER_SIMKL) ?: true) {
            registerMainAPI(CineSimklProvider())
        }
        if (getKey<Boolean>(Settings.PROVIDER_TMDB) ?: true) {
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
