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
            "Dizimag",
            listOf("Below", "House of the Dragon", "Eşref Rüya"),
            // A public episode page isolates Dizimag player extraction from catalogue/search parsing.
            listOf("https://dizimag.onl/dizi/below/sezon-1/bolum-1"),
            directOnly = true,
        ),
        Target(
            "TvDiziler",
            listOf("Aşk 101", "Medcezir"),
            listOf(
                "https://tvdiziler.tv/ask-101-1-bolum-izle",
                "https://tvdiziler.tv/medcezir-1-bolum-izle",
            ),
            directOnly = true,
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
        val knownNames = targets.map { it.name }.toSet()
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
                candidates.joinToString(" | ") { it.first.name },
        )

        var lastError = ""
        var lastQuery: String? = null
        var lastItem: String? = null
        var lastLinkCount = 0
        var lastPositionMs = 0L
        val attemptDiagnostics = mutableListOf<String>()

        for ((result, query) in candidates) {
            lastQuery = query
            lastItem = result.name

            val data = runCatching {
                loadData(api, result)
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

                if (antiBot(lastError)) {
                    return Result(
                        provider = target.name,
                        status = "BLOCKED",
                        query = query,
                        item = result.name,
                        links = links.size,
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
                    links.take(4).joinToString(" | ") { safeUrlForLog(it.url) },
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

                if (antiBot(lastError)) {
                    return Result(
                        provider = target.name,
                        status = "BLOCKED",
                        query = query,
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
            query = lastQuery,
            item = lastItem,
            links = lastLinkCount,
            positionMs = lastPositionMs,
            error = buildString {
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

        val activeTargets = allTargets()
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

        assertTrue(
            "Gerçek playback FAIL bulundu: " +
                results.filter { it.status == "FAIL" },
            results.none { it.status == "FAIL" },
        )
    }
}
// smoke trigger v9

// real player smoke v9
