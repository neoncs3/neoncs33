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
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(InternalAPI::class)
@RunWith(AndroidJUnit4::class)
class NeonPlaybackSmokeTest {
    companion object {
        private const val MIN_PLAYBACK_MS = 1_500L
        private const val PLAYBACK_TIMEOUT_MS = 20_000L
        private const val MAX_CANDIDATES_PER_PROVIDER = 6
    }

    data class Target(
        val name: String,
        val queries: List<String>,
        val directUrls: List<String> = emptyList(),
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
        Target("AsyaFilmIzle", listOf("Squid Game", "Wednesday", "The Last of Us")),
        Target(
            "DiziBoxizle",
            listOf("Breaking Bad", "Wednesday", "The Last of Us"),
            // A current public episode page is included as an extractor-only control.
            // This separates search/load failures from loadLinks/provider failures.
            listOf("https://diziboxizle.com/the-lowdown-1-sezon-1-bolum/"),
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

        return parts.joinToString(" <- ").take(1400)
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

            val isPlayingNow = runCatching {
                player.getIsPlaying()
            }.getOrDefault(false)

            if (!isPlayingNow || bestPosition < MIN_PLAYBACK_MS) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    runCatching {
                        player.handleEvent(
                            CSPlayerEvent.Play,
                            PlayerEventSource.Player,
                        )
                    }
                }
            }

            refreshFirstFrameState()

            val current = runCatching {
                player.getPosition() ?: 0L
            }.getOrDefault(0L).coerceAtLeast(0L)

            if (current > lastPosition) {
                advancedSamples++
            }

            if (current > bestPosition) {
                bestPosition = current
            }

            lastPosition = current

            // A rendered frame is the strongest smoke-test signal. We accept both
            // the listener callback and CS3IPlayer's own first-render state so a
            // callback that fired before our reflective listener was attached is
            // not reported as a false negative.
            refreshFirstFrameState()
            val renderedAt = firstFrameAt.get()
            if (firstFrame.get() && renderedAt > 0L &&
                System.currentTimeMillis() - renderedAt >= MIN_PLAYBACK_MS
            ) {
                break
            }

            if (playerError.get() != null && !firstFrame.get() && bestPosition == 0L) {
                break
            }
        }

        val duration = runCatching {
            player.getDuration() ?: 0L
        }.getOrDefault(0L).coerceAtLeast(0L)

        val error = playerError.get()
        refreshFirstFrameState()
        val played = firstFrame.get() ||
            (bestPosition >= MIN_PLAYBACK_MS && advancedSamples >= 2)

        val diagnostic = error ?: if (!played) {
            "ExoPlayer playback ilerlemedi: position=" +
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
    ): List<SearchResponse> {
        val searchCandidates = mutableListOf<SearchResponse>()

        // Direct control URLs test the provider resolver independently from search.
        for (url in target.directUrls) {
            searchCandidates.add(
                newTvSeriesSearchResponse(
                    name = target.name,
                    url = url,
                    type = TvType.TvSeries,
                )
            )
        }

        // Playback smoke testinde hedef içerik, ana sayfadaki rastgele güncel
        // içerikten daha değerlidir. Önce bilinen arama sorgularını dene; aksi
        // halde test, örneğin DiziBoxizle'da ana sayfadaki dört rastgele içeriğe
        // takılıp hedef diziyi hiç denemeden FAIL verebiliyordu.
        for (query in target.queries) {
            runCatching {
                api.search(query, 1)?.items.orEmpty().take(3)
            }.getOrNull()?.let(searchCandidates::addAll)
        }

        val mainPageCandidates = mutableListOf<SearchResponse>()

        // Arama motoru geçici olarak boşsa ana sayfadan gerçek içeriklerle
        // ikinci bir aday havuzu oluştur.
        if (api.hasMainPage) {
            api.mainPage.take(2).forEach { page ->
                runCatching {
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
                    ?.let(mainPageCandidates::addAll)
            }
        }

        return (searchCandidates + mainPageCandidates)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .take(MAX_CANDIDATES_PER_PROVIDER)
    }

    private suspend fun testProvider(
        context: Context,
        target: Target,
    ): Result {
        val api = APIHolder.apis.firstOrNull {
            it.name.equals(target.name, true)
        } ?: APIHolder.allProviders.firstOrNull {
            it.name.equals(target.name, true)
        } ?: return Result(
            target.name,
            "FAIL",
            error = "Provider bulunamadı",
        )

        val candidates = providerCandidates(api, target)

        // A provider may be healthy in code but temporarily blocked by its
        // origin/CDN. Do a lightweight origin probe before calling that a
        // plugin playback failure, so HTTP 403/429 becomes BLOCKED.
        if (candidates.isEmpty()) {
            val originStatus = runCatching {
                app.get(
                    api.mainUrl,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/154.0 Safari/537.36",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    ),
                    allowRedirects = true,
                ).code
            }.getOrDefault(0)

            if (originStatus == 403 || originStatus == 429) {
                return Result(
                    provider = target.name,
                    status = "BLOCKED",
                    error = "Provider origin HTTP $originStatus",
                )
            }
        }

        Log.d(
            "NEON_PLAYBACK",
            "Provider=" + target.name +
                " aday=" + candidates.size +
                " isimler=" +
                candidates.joinToString(" | ") { it.name },
        )

        var lastError = ""

        for (result in candidates) {
            val data = runCatching {
                loadData(api, result)
            }.getOrElse {
                lastError = err(it)
                null
            } ?: continue

            val (links, linkError) = resolve(api, data)

            if (links.isEmpty()) {
                lastError = linkError ?: "Gerçek video bağlantısı bulunamadı"

                if (antiBot(lastError)) {
                    return Result(
                        provider = target.name,
                        status = "BLOCKED",
                        item = result.name,
                        error = lastError,
                    )
                }

                continue
            }

            Log.d(
                "NEON_PLAYBACK",
                "Provider=" + target.name +
                    " item=" + result.name +
                    " links=" + links.size +
                    " urls=" +
                    links.take(4).joinToString(" | ") { it.url },
            )

            val playableLinks = links
                .filter {
                    it.url.startsWith("http://") ||
                        it.url.startsWith("https://")
                }
                .sortedByDescending { it.quality }
                .take(2)

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
                        " error=" + playerError +
                        " url=" + link.url,
                )

                if (played) {
                    return Result(
                        provider = target.name,
                        status = "PASS",
                        item = result.name,
                        links = links.size,
                        positionMs = position,
                    )
                }

                lastError = playerError
                    ?: "ExoPlayer position ilerlemedi: " + position + "ms"

                if (antiBot(lastError)) {
                    return Result(
                        provider = target.name,
                        status = "BLOCKED",
                        item = result.name,
                        links = links.size,
                        positionMs = position,
                        error = lastError,
                    )
                }
            }
        }

        return Result(
            provider = target.name,
            status = if (antiBot(lastError)) "BLOCKED" else "FAIL",
            error = lastError.ifBlank {
                "Test adaylarında çalışan gerçek playback bulunamadı"
            },
        )
    }

    private fun writeReport(context: Context, results: List<Result>) {
        val json = buildString {
            append("{\"results\":[")
            results.forEachIndexed { i, r ->
                if (i > 0) append(",")
                append("{\"provider\":\"").append(r.provider.replace("\"", "\\\"")).append("\",")
                append("\"status\":\"").append(r.status).append("\",")
                append("\"query\":").append(r.query?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null").append(",")
                append("\"item\":").append(r.item?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null").append(",")
                append("\"links\":").append(r.links).append(",")
                append("\"positionMs\":").append(r.positionMs).append(",")
                append("\"error\":").append(r.error?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null")
                append("}")
            }
            append("],\"pass\":").append(results.count { it.status == "PASS" })
            append(",\"blocked\":").append(results.count { it.status == "BLOCKED" })
            append(",\"fail\":").append(results.count { it.status == "FAIL" }).append("}")
        }
        File(context.filesDir, "neon-playback-report.json").writeText(json)
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

        val expectedPlugins = targets.map { it.name }.toSet()
        val missingPlugins = expectedPlugins - localPlugins.toSet()

        if (missingPlugins.isNotEmpty()) {
            println(
                "NEON_PLUGINS_MISSING " +
                    missingPlugins.joinToString(","),
            )
        }

        val results = targets.map {
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

        assertTrue(
            "Gerçek playback FAIL bulundu: " +
                results.filter { it.status == "FAIL" },
            results.none { it.status == "FAIL" },
        )
    }
}
// smoke trigger v9

// real player smoke v9
