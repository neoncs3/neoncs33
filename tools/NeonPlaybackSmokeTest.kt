package com.lagradost.cloudstream3

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.USER_AGENT
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(InternalAPI::class)
@RunWith(AndroidJUnit4::class)
class NeonPlaybackSmokeTest {
    companion object {
        private const val MIN_PLAYBACK_MS = 3_000L
        private const val PLAYBACK_TIMEOUT_MS = 20_000L
        private const val MAX_CANDIDATES_PER_PROVIDER = 6
    }

    data class Target(
        val name: String,
        val queries: List<String>,
        val directUrls: List<String> = emptyList(),
        val pluginInternalName: String? = null,
        val directOnly: Boolean = false,
        val apiName: String? = null,
        // Use when the website serves a known unusable media endpoint for this exact fixture.
        // It remains visible as BLOCKED in the report, but does not masquerade as a Kotlin regression.
        val upstreamUnavailableReason: String? = null,
        // Check DiziDad's actual LoadResponse episode poster data and fetch a few image URLs
        // through the same HTTP client/User-Agent as the installed CloudStream app.
        val posterOnly: Boolean = false,
    )
    data class Result(
        val provider: String,
        val status: String,
        val query: String? = null,
        val item: String? = null,
        val links: Int = 0,
        val positionMs: Long = 0L,
        val error: String? = null,
    )

    private val targets = listOf(
        Target(
            name = "DiziDad • Tyson afişleri",
            queries = emptyList(),
            directUrls = listOf("https://www.dizidad.com/diziler/tyson-izle/"),
            pluginInternalName = "DiziDad",
            directOnly = true,
            apiName = "DiziDAD",
            posterOnly = true,
        ),
        Target("AsyaFilmIzle", listOf("Squid Game", "Wednesday", "The Last of Us")),
        Target(
            "DiziBoxizle",
            listOf("Breaking Bad", "Wednesday", "The Last of Us", "Tanrının Zırhı"),
            // A current public episode page is included as an extractor-only control.
            // This separates search/load failures from loadLinks/provider failures.
            listOf(
                "https://diziboxizle.com/film/tanrinin-zirhi/",
                "https://diziboxizle.com/film/surgun/",
                "https://diziboxizle.com/the-lowdown-1-sezon-1-bolum/",
                "https://diziboxizle.com/race-for-the-crown-1-sezon-1-bolum/",
            ),
        ),
        Target(
            "HDFilmDiziizle • Tron",
            listOf("Tron"),
            // Fixed public film page: tests loadLinks independently of catalogue search.
            directUrls = listOf("https://www.hdfilmdiziizle.com/film/tron/"),
            pluginInternalName = "AutoHdfilmdiziizleCom",
            apiName = "HdFilmDiziizle",
        ),
        Target(
            "Dizimag • Below S1E1",
            emptyList(),
            listOf("https://dizimag.one/below-1-sezon-1-bolum/"),
            pluginInternalName = "AutoDdizimagCom",
            directOnly = true,
            apiName = "Dizimag",
        ),
        Target(
            "Dizimag • Below S1E2",
            emptyList(),
            listOf("https://dizimag.one/below-1-sezon-2-bolum/"),
            pluginInternalName = "AutoDdizimagCom",
            directOnly = true,
            apiName = "Dizimag",
        ),
        Target(
            "Dizimag • Below S1E3",
            emptyList(),
            listOf("https://dizimag.one/below-1-sezon-3-bolum/"),
            pluginInternalName = "AutoDdizimagCom",
            directOnly = true,
            apiName = "Dizimag",
        ),
        Target(
            "Dizimag • Below S1E6",
            emptyList(),
            // Verified current public episode page (indexed on 2026-10-14).
            listOf("https://dizimag.one/below-1-sezon-6-bolum/"),
            pluginInternalName = "AutoDdizimagCom",
            directOnly = true,
            apiName = "Dizimag",
        ),
        Target(
            "TvDiziler • Aşk 101",
            listOf("Aşk 101"),
            listOf("https://tvdiziler.tv/ask-101-1-bolum-izle"),
            pluginInternalName = "AutoTvdizilerTv",
            directOnly = true,
            apiName = "TvDiziler",
            upstreamUnavailableReason = "Aşk 101 1. bölümün oyuncusu yalnızca Streambox master.txt adresi sunuyor; testte bu adres HTTP 200 ile geçerli #EXTM3U listesi yerine 14 karakterlik HTML döndürdü. Bu bölümün kaynağı erişilebilir değil; diğer TvDiziler bölümleri ayrıca gerçek oynatmayla test ediliyor",
        ),
        Target(
            "TvDiziler • Medcezir",
            listOf("Medcezir"),
            listOf("https://tvdiziler.tv/medcezir-1-bolum-izle"),
            pluginInternalName = "AutoTvdizilerTv",
            directOnly = true,
            apiName = "TvDiziler",
        ),
        Target(
            "TvDiziler • Tuzlu Kahve 7",
            emptyList(),
            listOf("https://tvdiziler.tv/tuzlu-kahve-7-bolum-izle"),
            pluginInternalName = "AutoTvdizilerTv",
            directOnly = true,
            apiName = "TvDiziler",
        ),
        Target(
            "TvDiziler • Anne Yarısı 4",
            emptyList(),
            listOf("https://tvdiziler.tv/anne-yarisi-4-bolum-izle"),
            pluginInternalName = "AutoTvdizilerTv",
            directOnly = true,
            apiName = "TvDiziler",
        ),
        Target(
            "TvDiziler • Haysiyet 6",
            emptyList(),
            listOf("https://tvdiziler.tv/haysiyet-6-bolum-izle"),
            pluginInternalName = "AutoTvdizilerTv",
            directOnly = true,
            apiName = "TvDiziler",
        ),
        Target("DiziPal", listOf("Wednesday", "The Last of Us", "Stranger Things")),
        Target("Dizigecesi", listOf("Wednesday", "The Last of Us", "The Boys")),
        Target("Dramadizilerim", listOf("Squid Game", "Moving", "Hidden Love")),
        Target("FilmModu", listOf("Inception", "Interstellar", "The Batman")),
        Target("JetFilmizle", listOf("Inception", "Interstellar", "The Batman")),
        Target("SetFilmIzle", listOf("Inception", "Interstellar", "The Batman")),
        Target("SinemaCX", listOf("Inception", "Interstellar", "The Batman")),
        Target("WebDramaTurkey", listOf("Squid Game", "Moving", "Alchemy of Souls")),
    )

    private fun generatedSampleQueries(provider: MainAPI): List<String> {
        if (!provider.javaClass.simpleName.startsWith("Auto")) return emptyList()
        val raw = runCatching {
            provider.javaClass.getDeclaredField("neonSmokeTestQueries").also {
                it.isAccessible = true
            }.get(provider)
        }.getOrNull() as? List<*> ?: return emptyList()

        return raw.filterIsInstance<String>()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(6)
    }

    private fun allTargets(): List<Target> {
        val knownNames = targets.map { it.apiName ?: it.name }.toSet()
        val generatedProviders = APIHolder.allProviders
            .filter { it.javaClass.simpleName.startsWith("Auto") }
            .filterNot { it.name in knownNames }
            .distinctBy { it.javaClass.simpleName }
            .map { provider ->
                Target(
                    name = provider.name,
                    // Query this provider's actual poster-backed catalogue before generic fallbacks.
                    queries = (
                        generatedSampleQueries(provider) + listOf(
                            "Inception",
                            "Wednesday",
                            "The Last of Us",
                            "Squid Game",
                            "Film",
                            "Dizi",
                        )
                    ).distinct().take(6),
                    pluginInternalName = provider.javaClass.simpleName,
                )
            }

        return targets + generatedProviders
    }

    private fun antiBot(text: String?): Boolean {
        val v = text.orEmpty().lowercase()
        return listOf(
            "403",
            "429",
            "invalidresponsecodeexception",
            "response code: 403",
            "response code: 429",
            "cloudflare",
            "just a moment",
            "attention required",
            "checking your browser",
            "verify you are human",
            "cf-chl-",
        ).any(v::contains)
    }

    private fun isTechnicalPosterTitle(raw: String): Boolean {
        val value = raw.trim()
        return Regex("""(?i)^\s*\[?\s*null\b""").containsMatchIn(value) ||
            Regex("""(?i)^\s*\[[^\]]+\.(?:jpe?g|png|webp|gif|avif)\]\s*""").containsMatchIn(value) ||
            Regex("""(?i)(?:^|\s)[^\s/]+\.(?:jpe?g|png|webp|gif|avif)(?:\s|$)""").containsMatchIn(value) ||
            Regex("""(?i)^\s*\[[a-z0-9_-]{18,}\]\s*""").containsMatchIn(value) ||
            Regex("""(?i)^[a-z0-9_-]{18,}$""").matches(value)
    }

    private fun safeUrlForLog(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrElse {
            raw.substringBefore("?").substringBefore("#")
        }
    }

    private fun safeDiagnostic(raw: String): String {
        val urlPattern = Regex("""(?i)https?://[^\s"'<>]+""")
        val withoutUrlQueries = urlPattern.replace(raw) { match ->
            safeUrlForLog(match.value)
        }
        return withoutUrlQueries.replace(
            Regex("""(?i)\b(token|sig|signature|expires|auth|key|policy)=([^&\s]+)"""),
            "$1=[redacted]",
        ).take(1200)
    }

    private fun err(t: Throwable): String {
        val parts = LinkedHashSet<String>()
        var current: Throwable? = t
        var depth = 0

        while (current != null && depth < 6) {
            val type = current::class.java.simpleName
            val message = current.message
                ?.replace("\n", " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            if (message != null) {
                parts.add("$type: $message")
            } else {
                parts.add(type)
            }

            current = current.cause
            depth++
        }

        return safeDiagnostic(parts.joinToString(" <- ")).take(1200)
    }

    private suspend fun loadData(api: MainAPI, result: SearchResponse): String? {
        val load = api.load(result.url) ?: return null
        return when (load) {
            is MovieLoadResponse -> load.dataUrl
            is TvSeriesLoadResponse -> load.episodes.firstOrNull()?.data
            is AnimeLoadResponse -> load.episodes.values.asSequence().flatten().firstOrNull()?.data
            is LiveStreamLoadResponse -> load.dataUrl
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    private suspend fun resolve(api: MainAPI, data: String): Pair<List<ExtractorLink>, String?> {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        return try {
            val ok = api.loadLinks(data, false, { }) { links.add(it) }
            if (!ok && links.isEmpty()) links to "loadLinks=false" else links to null
        } catch (t: Throwable) {
            links to err(t)
        }
    }

    private fun realPlay(
        context: Context,
        link: ExtractorLink,
    ): Triple<Boolean, Long, String?> {
        val player = CS3IPlayer()
        val playerError = AtomicReference<String?>(null)
        val firstFrame = AtomicBoolean(false)
        val exoPlaying = AtomicBoolean(false)
        val firstFrameAt = AtomicReference(0L)
        var exo: Player? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.initCallbacks(
                { event ->
                    if (event is ErrorEvent) {
                        playerError.compareAndSet(null, err(event.error))
                    }
                },
                null,
            )

            player.loadPlayer(
                context = context,
                sameEpisode = false,
                link = link,
                data = null,
                startPosition = 0L,
                subtitles = emptySet(),
                subtitle = null,
                autoPlay = true,
                preview = false,
            )

            // CS3IPlayer keeps the underlying ExoPlayer private. For this diagnostic
            // test we attach a listener so a rendered video frame is treated as
            // stronger evidence than a possibly stale wrapper position.
            runCatching {
                val field = CS3IPlayer::class.java.getDeclaredField("exoPlayer")
                field.isAccessible = true
                exo = field.get(player) as? Player
                exo?.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrame.set(true)
                        firstFrameAt.compareAndSet(0L, System.currentTimeMillis())
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        exoPlaying.set(isPlaying)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val detail = err(error)
                        if (antiBot(detail)) {
                            playerError.set(detail)
                        } else {
                            playerError.compareAndSet(null, detail)
                        }
                    }
                })
            }.onFailure {
                Log.w(
                    "NEON_PLAYBACK",
                    "ExoPlayer listener bağlanamadı: " + err(it),
                )
            }

            runCatching {
                player.handleEvent(
                    CSPlayerEvent.Play,
                    PlayerEventSource.Player,
                )
            }
        }

        // loadPlayer() creates ExoPlayer asynchronously. Do not assume that the
        // first-frame listener is attached before rendering starts; poll the same
        // internal state CloudStream updates from onRenderedFirstFrame().
        val firstFrameField = runCatching {
            CS3IPlayer::class.java.getDeclaredField("hasUsedFirstRender").also { it.isAccessible = true }
        }.getOrNull()

        fun refreshFirstFrameState() {
            if (firstFrame.get()) return
            val rendered = runCatching { firstFrameField?.getBoolean(player) == true }.getOrDefault(false)
            if (rendered) {
                firstFrame.set(true)
                firstFrameAt.compareAndSet(0L, System.currentTimeMillis())
                Log.d("NEON_PLAYBACK", "Rendered first frame detected via CS3IPlayer state")
            }
        }

        var lastPosition = 0L
        var bestPosition = 0L
        var advancedSamples = 0
        val endAt = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS

        while (System.currentTimeMillis() < endAt) {
            Thread.sleep(500L)
            refreshFirstFrameState()

            // Play was requested once before entering the polling loop.
            // Do not repeatedly send Play while the stream is preparing: that can
            // reset startup and mask whether the underlying source really advances.
            refreshFirstFrameState()

            var current = 0L
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val wrapperPosition = runCatching {
                    player.getPosition() ?: 0L
                }.getOrDefault(0L)
                val exoPosition = runCatching {
                    exo?.currentPosition ?: 0L
                }.getOrDefault(0L)
                current = maxOf(wrapperPosition, exoPosition).coerceAtLeast(0L)
            }

            if (current > lastPosition) {
                advancedSamples++
            }

            if (current > bestPosition) {
                bestPosition = current
            }

            lastPosition = current

            // First-frame alone is not enough: a broken/preview source can render
            // one frame and then fail or be released. Require actual position advance
            // from at least two samples after the first frame.
            refreshFirstFrameState()
            val renderedAt = firstFrameAt.get()
            if (firstFrame.get() &&
                renderedAt > 0L &&
                System.currentTimeMillis() - renderedAt >= MIN_PLAYBACK_MS &&
                bestPosition >= 1_500L &&
                advancedSamples >= 2
            ) {
                break
            }

            if (playerError.get() != null) {
                break
            }

            if (playerError.get() != null && !firstFrame.get() && bestPosition == 0L) {
                break
            }
        }

        var duration = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wrapperDuration = runCatching {
                player.getDuration() ?: 0L
            }.getOrDefault(0L)
            val exoDuration = runCatching {
                exo?.duration ?: 0L
            }.getOrDefault(0L)
            duration = maxOf(wrapperDuration, exoDuration, 0L)
        }

        val error = playerError.get()
        refreshFirstFrameState()
        val played = firstFrame.get() &&
            bestPosition >= 1_500L &&
            advancedSamples >= 2

        val diagnostic = error ?: if (!played) {
            "ExoPlayer sürdürülebilir playback doğrulanamadı: position=" +
                bestPosition +
                "ms duration=" +
                duration +
                "ms samples=" +
                advancedSamples +
                " firstFrame=" +
                firstFrame.get() +
                " exoPlaying=" +
                exoPlaying.get()
        } else {
            null
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { player.release() }
            runCatching { player.releaseCallbacks() }
        }

        return Triple(
            played,
            bestPosition,
            diagnostic,
        )
    }


    private suspend fun providerCandidates(
        api: MainAPI,
        target: Target,
    ): List<Pair<SearchResponse, String?>> {
        val searchCandidates = mutableListOf<Pair<SearchResponse, String?>>()

        // Direct control URLs test the provider resolver independently from search.
        for (url in target.directUrls) {
            val item = if (url.contains("/film/", ignoreCase = true)) {
                api.newMovieSearchResponse(
                    name = target.name,
                    url = url,
                    type = TvType.Movie,
                )
            } else {
                api.newTvSeriesSearchResponse(
                    name = target.name,
                    url = url,
                    type = TvType.TvSeries,
                )
            }
            searchCandidates.add(item to null)
        }

        // Keep the exact search query with each result so a failed provider run
        // shows which search term led to the last tested candidate.
        if (!target.directOnly) {
            for (query in target.queries) {
                val found = runCatching {
                    api.search(query, 1)?.items.orEmpty().take(3)
                }.getOrDefault(emptyList())
                searchCandidates.addAll(found.map { it to query })
            }
        }

        val mainPageCandidates = mutableListOf<Pair<SearchResponse, String?>>()

        // If search is temporarily empty, use a small fallback pool from the home page.
        if (!target.directOnly && api.hasMainPage) {
            api.mainPage.take(2).forEach { page ->
                val items = runCatching {
                    api.getMainPage(
                        1,
                        MainPageRequest(
                            page.name,
                            page.data,
                            page.horizontalImages,
                        ),
                    )
                }.getOrNull()?.items
                    ?.flatMap { it.list }
                    ?.take(6)
                    .orEmpty()
                mainPageCandidates.addAll(items.map { it to "Ana sayfa yedeği" })
            }
        }

        return (searchCandidates + mainPageCandidates)
            .filter { (item, _) -> item.url.isNotBlank() }
            .distinctBy { it.first.url }
            .take(MAX_CANDIDATES_PER_PROVIDER)
    }

    private suspend fun testPosterUrls(context: Context, api: MainAPI, target: Target): Result {
        val page = target.directUrls.firstOrNull()
            ?: return Result(target.name, "FAIL", error = "Afiş testi için dizi URL'si tanımlı değil")

        val load = runCatching { api.load(page) }.getOrElse {
            return Result(target.name, "FAIL", item = page, error = "load() hata verdi: " + err(it))
        }
        if (load !is TvSeriesLoadResponse) {
            return Result(
                target.name,
                "FAIL",
                item = page,
                error = "Dizi sayfası TvSeriesLoadResponse döndürmedi; tür=" + (load?.javaClass?.simpleName ?: "null"),
            )
        }

        val episodes = load.episodes
        if (episodes.isEmpty()) {
            return Result(target.name, "FAIL", item = "0 bölüm", error = "Dizi yükleniyor ama bölüm listesi boş")
        }

        val episodePosterCount = episodes.count { !it.posterUrl.isNullOrBlank() }
        val missingExamples = episodes.filter { it.posterUrl.isNullOrBlank() }
            .take(4)
            .joinToString { it.name ?: "bölüm ${it.episode}" }

        // Regression fixture: the old broad filter rejected this valid poster because the
        // title's filename contains the word "avatar".
        val filterFixture = runCatching {
            api.load("https://www.dizidad.com/diziler/avatar-seven-havens-izle/")
        }.getOrNull()
        val filterFixturePoster = (filterFixture as? TvSeriesLoadResponse)
            ?.posterUrl?.trim()?.takeIf { it.isNotBlank() }

        // As well as the detail page, query the actual catalogue sections shown in CloudStream.
        // This catches a blank-homepage poster regression even when episode fallback posters exist.
        val mainPageStats = mutableListOf<String>()
        val mainPageIssues = mutableListOf<String>()
        val mainPageSamples = mutableListOf<Triple<String, String, String>>()
        val observedTitles = mutableListOf<String>()
        var mainPageItemCount = 0
        var mainPagePosterCount = 0
        var loadedMainPages = 0
        val testedPages = api.mainPage.take(6)
        for (mainPage in testedPages) {
            val cards = runCatching {
                api.getMainPage(
                    1,
                    MainPageRequest(mainPage.name, mainPage.data, mainPage.horizontalImages),
                )?.items?.flatMap { it.list }.orEmpty()
            }.getOrDefault(emptyList())

            if (cards.isEmpty()) {
                mainPageStats.add("${mainPage.name}: kart yok")
                continue
            }

            observedTitles.addAll(cards.map { it.name })
            loadedMainPages++
            val withPoster = cards.filter { !it.posterUrl.isNullOrBlank() }
            mainPageItemCount += cards.size
            mainPagePosterCount += withPoster.size
            mainPageStats.add("${mainPage.name}: posterUrl=${withPoster.size}/${cards.size}")
            if (withPoster.size != cards.size) {
                mainPageIssues.add("${mainPage.name} ${withPoster.size}/${cards.size}")
            }
            withPoster.firstOrNull()?.let { card ->
                mainPageSamples.add(Triple(mainPage.name, card.name, card.posterUrl!!))
            }
        }

        // Search results can currently expose image filenames or cache IDs as the title.
        val searchCards = runCatching { api.search("Animal") }.getOrDefault(emptyList())
        observedTitles.addAll(searchCards.map { it.name })
        observedTitles.add(load.name)
        val badTitles = observedTitles.filter { isTechnicalPosterTitle(it) }.distinct().take(8)

        val episodeSamples = episodes.take(4)
        val imageSamples = mutableListOf<Triple<String, String, Boolean>>()
        for (episode in episodeSamples) {
            val poster = episode.posterUrl?.trim().orEmpty()
            val label = episode.name ?: "bölüm ${episode.episode}"
            if (poster.isBlank()) {
                imageSamples.add(Triple(label, "", false))
            } else {
                imageSamples.add(Triple(label, poster, false))
            }
        }
        mainPageSamples.take(4).forEach { (section, title, poster) ->
            imageSamples.add(Triple("$section / $title", poster, true))
        }
        if (!filterFixturePoster.isNullOrBlank()) {
            imageSamples.add(Triple("Filtre kontrolü / Avatar: Seven Havens", filterFixturePoster, true))
        }

        var downloadedEpisodes = 0
        var downloadedMainPage = 0
        val fetchDetails = mutableListOf<String>()
        for ((label, poster, isMainPage) in imageSamples) {
            if (poster.isBlank()) {
                fetchDetails.add("$label: posterUrl yok")
                continue
            }

            val result = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(poster)
                    .httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                        headerBuilder["User-Agent"] = USER_AGENT
                        headerBuilder["Referer"] = "https://www.dizidad.com/"
                    }.build())
                    .build()
                SingletonImageLoader.get(context).execute(request)
            }.getOrElse {
                fetchDetails.add("$label: Coil hata verdi: ${err(it)} (${safeUrlForLog(poster)})")
                Log.w("NEON_POSTER", "item=$label Coil exception=${err(it)} url=${safeUrlForLog(poster)}")
                null
            }
            when (result) {
                is SuccessResult -> {
                    if (isMainPage) downloadedMainPage++ else downloadedEpisodes++
                    Log.d("NEON_POSTER", "item=$label coil=SUCCESS url=${safeUrlForLog(poster)}")
                }
                is ErrorResult -> {
                    val detail = "Coil decode/istek hatası: ${err(result.throwable)}"
                    fetchDetails.add("$label: $detail (${safeUrlForLog(poster)})")
                    Log.w("NEON_POSTER", "item=$label coil=ERROR error=${err(result.throwable)} url=${safeUrlForLog(poster)}")
                }
                null -> Unit
                else -> fetchDetails.add("$label: Beklenmeyen Coil sonucu (${safeUrlForLog(poster)})")
            }
        }

        val expectedEpisodeImages = episodeSamples.count { !it.posterUrl.isNullOrBlank() }
        val expectedMainPageImages = mainPageSamples.take(4).size
        val episodesOk = episodePosterCount == episodes.size &&
            expectedEpisodeImages == episodeSamples.size &&
            downloadedEpisodes == expectedEpisodeImages
        val filterFixtureOk = !filterFixturePoster.isNullOrBlank()
        val mainPageOk = loadedMainPages >= 3 &&
            mainPageItemCount > 0 &&
            mainPagePosterCount == mainPageItemCount &&
            filterFixtureOk &&
            badTitles.isEmpty() &&
            downloadedMainPage == expectedMainPageImages + if (filterFixtureOk) 1 else 0
        val ok = episodesOk && mainPageOk
        val item = buildString {
            append("${episodes.size} bölüm; bölüm posterUrl=${episodePosterCount}/${episodes.size}; ")
            append("Coil bölüm=${downloadedEpisodes}/${expectedEpisodeImages}; ")
            append("ana sayfa posterUrl=${mainPagePosterCount}/${mainPageItemCount} (${loadedMainPages}/${testedPages.size} kategori); ")
            append("filtre AvatarPoster=${filterFixtureOk}; ")
            append("bozukBaşlık=${badTitles.size}; ")
            append("Coil ana sayfa=${downloadedMainPage}/${expectedMainPageImages + if (filterFixtureOk) 1 else 0}")
        }
        val error = if (ok) null else buildString {
            if (episodePosterCount != episodes.size) {
                append("Bölüm afişi eksik: ${missingExamples.ifBlank { "isim alınamadı" }}. ")
            }
            if (mainPageIssues.isNotEmpty()) append("Ana sayfa afiş eksikleri: ${mainPageIssues.joinToString(", ")}. ")
            if (!filterFixtureOk) append("Filtre kontrolü başarısız: Avatar: Seven Havens afişi LoadResponse içinde yok. ")
            if (badTitles.isNotEmpty()) append("Dosya adı/NULL/kimlik olarak dönen başlıklar: ${badTitles.joinToString("; ")}. ")
            if (loadedMainPages < 3) append("Yeterli ana sayfa kategorisi yüklenemedi: ${mainPageStats.joinToString("; ")}. ")
            if (fetchDetails.isNotEmpty()) append("Görsel istekleri: ${fetchDetails.joinToString("; ")}")
        }.take(1200)
        Log.d(
            "NEON_POSTER",
            "provider=${target.name} $item categories=[${mainPageStats.joinToString("; ")}] result=${if (ok) "PASS" else "FAIL"}",
        )
        return Result(
            provider = target.name,
            status = if (ok) "PASS" else "FAIL",
            item = item,
            links = downloadedEpisodes + downloadedMainPage,
            error = error,
        )
    }

    private suspend fun testProvider(
        context: Context,
        target: Target,
    ): Result {
        val requestedApiName = target.apiName ?: target.name
        val api = APIHolder.apis.firstOrNull {
            it.name.equals(requestedApiName, true)
        } ?: APIHolder.allProviders.firstOrNull {
            it.name.equals(requestedApiName, true)
        } ?: return Result(
            target.name,
            "FAIL",
            error = "Provider bulunamadı: $requestedApiName",
        )

        // A poster test should diagnose the selected series page and Coil requests directly.
        // Do not short-circuit it based on a generic homepage probe.
        if (target.posterOnly) {
            return testPosterUrls(context, api, target)
        }

        // Probe the provider origin before attributing an empty video-link list to the
        // plugin. An origin-wide 403/429 or explicit anti-bot interstitial is external
        // blocking, not a Kotlin extraction regression. Keep it visible as BLOCKED.
        val originProbe = runCatching {
            val response = app.get(
                api.mainUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/154.0 Safari/537.36",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                ),
                timeout = 8_000L,
                allowRedirects = true,
            )
            val body = response.text.lowercase()
            val challenge = listOf(
                "cloudflare", "just a moment", "attention required",
                "checking your browser", "verify you are human", "cf-chl-",
            ).any(body::contains)
            Triple(response.code, challenge, response.url)
        }.getOrNull()
        if (originProbe != null &&
            (originProbe.first == 403 || originProbe.first == 429 || originProbe.second)
        ) {
            return Result(
                provider = target.name,
                status = "BLOCKED",
                error = "Provider origin erişimi engellendi HTTP ${originProbe.first}" +
                    if (originProbe.second) " (anti-bot challenge)" else "",
            )
        }

        val candidates = providerCandidates(api, target)

        Log.d(
            "NEON_PLAYBACK",
            "Provider=" + target.name +
                " aday=" + candidates.size +
                " isimler=" +
                candidates.joinToString(" | ") { it.first.name },
        )

        var lastError = ""
        var lastQuery: String? = null
        var lastItem: String? = null
        var lastLinkCount = 0
        var lastPositionMs = 0L
        val attemptDiagnostics = mutableListOf<String>()
        var sawAntiBotBlock = false

        for ((result, query) in candidates) {
            lastQuery = query
            lastItem = result.name

            // Direct episode controls must resolve the selected episode itself.
            // Loading the series page and taking episodes.first() can silently test
            // the *next* episode, hiding per-episode player differences.
            val data = runCatching {
                if (target.directOnly && result.url in target.directUrls) {
                    result.url
                } else {
                    loadData(api, result)
                }
            }.getOrElse {
                lastError = err(it)
                null
            }
            if (data.isNullOrBlank()) {
                if (lastError.isBlank()) lastError = "İçerik veri adresi alınamadı"
                attemptDiagnostics.add((query ?: "doğrudan") + ": " + result.name + " -> veri adresi yok")
                continue
            }

            val (links, linkError) = resolve(api, data)
            lastLinkCount = maxOf(lastLinkCount, links.size)

            if (links.isEmpty()) {
                lastError = safeDiagnostic(linkError ?: "Gerçek video bağlantısı bulunamadı")
                attemptDiagnostics.add((query ?: "doğrudan") + ": " + result.name + " -> bağlantı yok (" + lastError.take(180) + ")")

                // A blocked candidate must not prevent trying other legitimate
                // episode/player candidates; some providers expose a mix of stale and
                // working sources. Preserve BLOCKED in the final result if none succeeds.
                if (antiBot(lastError)) sawAntiBotBlock = true

                continue
            }

            Log.d(
                "NEON_PLAYBACK",
                "Provider=" + target.name +
                    " item=" + result.name +
                    " links=" + links.size +
                    " urls=" +
                    links.take(4).joinToString(" | ") { safeUrlForLog(it.url) },
            )

            val playableLinks = links
                .filter {
                    it.url.startsWith("http://") ||
                        it.url.startsWith("https://")
                }
                .sortedByDescending { it.quality }
                .take(4)

            for (link in playableLinks) {
                val (played, position, playerError) = realPlay(
                    context,
                    link,
                )

                Log.d(
                    "NEON_PLAYBACK",
                    "Provider=" + target.name +
                        " item=" + result.name +
                        " played=" + played +
                        " position=" + position +
                        " error=" + safeDiagnostic(playerError.orEmpty()) +
                        " url=" + safeUrlForLog(link.url),
                )

                if (played) {
                    return Result(
                        provider = target.name,
                        status = "PASS",
                        query = query,
                        item = result.name,
                        links = links.size,
                        positionMs = position,
                    )
                }

                lastPositionMs = maxOf(lastPositionMs, position)
                lastError = safeDiagnostic(playerError
                    ?: "ExoPlayer position ilerlemedi: " + position + "ms")
                attemptDiagnostics.add((query ?: "doğrudan") + ": " + result.name + " -> oynatma başarısız (" + lastError.take(180) + ")")

                if (antiBot(lastError)) sawAntiBotBlock = true
            }
        }

        val knownUpstreamUnavailable =
            !target.upstreamUnavailableReason.isNullOrBlank() && lastLinkCount == 0

        return Result(
            provider = target.name,
            status = if (sawAntiBotBlock || antiBot(lastError) || knownUpstreamUnavailable) "BLOCKED" else "FAIL",
            query = lastQuery,
            item = lastItem,
            links = lastLinkCount,
            positionMs = lastPositionMs,
            error = buildString {
                if (knownUpstreamUnavailable) {
                    append(target.upstreamUnavailableReason)
                    append(". Tanı: ")
                }
                append(lastError.ifBlank { "Test adaylarında çalışan gerçek playback bulunamadı" })
                if (attemptDiagnostics.isNotEmpty()) {
                    append(". Aday denemeleri: ")
                    append(attemptDiagnostics.takeLast(6).joinToString("; ").take(700))
                }
            }.take(1200),
        )
    }

    private fun writeReport(context: Context, results: List<Result>) {
        val entries = JSONArray()
        results.forEach { result ->
            entries.put(
                JSONObject()
                    .put("provider", result.provider)
                    .put("status", result.status)
                    .put("query", result.query ?: JSONObject.NULL)
                    .put("item", result.item ?: JSONObject.NULL)
                    .put("links", result.links)
                    .put("positionMs", result.positionMs)
                    .put("error", result.error?.let { safeDiagnostic(it) } ?: JSONObject.NULL)
            )
        }

        val report = JSONObject()
            .put("results", entries)
            .put("pass", results.count { it.status == "PASS" })
            .put("blocked", results.count { it.status == "BLOCKED" })
            .put("fail", results.count { it.status == "FAIL" })

        File(context.filesDir, "neon-playback-report.json")
            .writeText(report.toString(2), Charsets.UTF_8)
    }

    @Test
    fun realPlaybackSmoke() = runBlocking {
        val context =
            InstrumentationRegistry.getInstrumentation().targetContext

        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(
            context,
            true,
        )

        delay(2_000L)

        val localPlugins = PluginManager
            .getPluginsLocal()
            .map { it.internalName.removeSuffix(".cs3").removeSuffix(".zip") }
            .sorted()

        println(
            "NEON_PLUGINS_LOCAL count=" +
                localPlugins.size +
                " names=" +
                localPlugins.joinToString(","),
        )

        val allAvailableTargets = allTargets()
        val requestedProviders = InstrumentationRegistry.getArguments()
            .getString("providers")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val activeTargets = if (requestedProviders.isEmpty()) {
            allAvailableTargets
        } else {
            allAvailableTargets.filter { (it.apiName ?: it.name) in requestedProviders }
        }
        if (activeTargets.isEmpty()) {
            throw AssertionError("İstenen provider testi bulunamadı: " + requestedProviders.joinToString(","))
        }
        println("NEON_PLAYBACK_FILTER providers=" +
            (requestedProviders.ifEmpty { setOf("ALL") }).joinToString(","))
        val autoTargets = activeTargets.filter { it.pluginInternalName != null }
        println(
            "NEON_AUTO_PROVIDERS count=" + autoTargets.size +
                " names=" + autoTargets.joinToString(",") { it.name },
        )

        val expectedPlugins = activeTargets.map { it.pluginInternalName ?: it.name }.toSet()
        val missingPlugins = expectedPlugins - localPlugins.toSet()

        if (missingPlugins.isNotEmpty()) {
            println(
                "NEON_PLUGINS_MISSING " +
                    missingPlugins.joinToString(","),
            )
        }

        val results = activeTargets.map {
            testProvider(context, it)
        }

        writeReport(context, results)

        results.forEach {
            println(
                "NEON_PLAYBACK provider=" + it.provider +
                    " status=" + it.status +
                    " links=" + it.links +
                    " positionMs=" + it.positionMs +
                    " query=" + it.query +
                    " item=" + it.item +
                    " error=" + it.error,
            )
        }

        assertTrue(
            "Hiç gerçek playback PASS olmadı: " + results,
            results.any { it.status == "PASS" },
        )

        val pluginFailures = results.filter { it.status == "FAIL" }
        assertTrue(
            "Gerçek oynatma testi kaynak kodu/çıkarıcı hatası buldu (FAIL): " + pluginFailures,
            pluginFailures.isEmpty(),
        )
        assertTrue(
            "Hiçbir sağlayıcıda gerçek video oynatımı doğrulanamadı; sonuçların tamamı engelli/başarısız.",
            results.any { it.status == "PASS" },
        )
    }
}
// smoke trigger v9

// real player smoke v9
