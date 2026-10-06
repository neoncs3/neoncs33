package com.lagradost.cloudstream3

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.ErrorEvent
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

@OptIn(InternalAPI::class)
@RunWith(AndroidJUnit4::class)
class NeonPlaybackSmokeTest {
    companion object {
        private const val MIN_PLAYBACK_MS = 1_500L
        private const val PLAYBACK_TIMEOUT_MS = 30_000L
    }

    data class Target(val name: String, val queries: List<String>)
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
        Target("DiziBoxizle", listOf("Breaking Bad", "Wednesday", "The Last of Us")),
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
        return listOf("403", "429", "cloudflare", "just a moment", "attention required", "checking your browser", "verify you are human", "cf-chl-").any(v::contains)
    }

    private fun err(t: Throwable): String = (t.message ?: t::class.java.simpleName).replace("\n", " ").take(1400)

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

    private fun realPlay(context: Context, link: ExtractorLink): Triple<Boolean, Long, String?> {
        val player = CS3IPlayer()
        val playerError = AtomicReference<String?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.initCallbacks({ event -> if (event is ErrorEvent) playerError.set(err(event.error)) }, null)
            player.loadPlayer(context, false, link, null, 0L, emptySet(), null, true, false)
        }

        var position = 0L
        val endAt = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
        while (System.currentTimeMillis() < endAt && position < 1_500L) {
            Thread.sleep(250L)
            position = runCatching { player.getPosition() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)
            if (playerError.get() != null && position == 0L) break
        }

        val ok = position >= MIN_PLAYBACK_MS
        val error = playerError.get()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { player.release() }
            runCatching { player.releaseCallbacks() }
        }
        return Triple(ok, position, error)
    }

    private suspend fun testProvider(context: Context, target: Target): Result {
        val api = APIHolder.apis.firstOrNull { it.name.equals(target.name, true) }
            ?: APIHolder.allProviders.firstOrNull { it.name.equals(target.name, true) }
            ?: return Result(target.name, "FAIL", error = "Provider bulunamadı")

        val homeQuery = runCatching {
            if (!api.hasMainPage) null else {
                val p = api.mainPage.firstOrNull() ?: return@runCatching null
                api.getMainPage(1, MainPageRequest(p.name, p.data, p.horizontalImages))
                    ?.items?.flatMap { it.list }?.firstOrNull()?.name?.split(" ")?.firstOrNull()
            }
        }.getOrNull()

        val queries = (listOfNotNull(homeQuery) + target.queries).distinct()
        var lastError = ""

        for (query in queries) {
            val results = runCatching { api.search(query, 1)?.items.orEmpty() }.getOrElse {
                lastError = err(it); emptyList()
            }
            for (result in results.take(3)) {
                val data = runCatching { loadData(api, result) }.getOrElse {
                    lastError = err(it); null
                } ?: continue

                val (links, linkError) = resolve(api, data)
                if (links.isEmpty()) {
                    lastError = linkError ?: "Gerçek video bağlantısı bulunamadı"
                    if (antiBot(lastError)) return Result(target.name, "BLOCKED", query, result.name, error = lastError)
                    continue
                }

                for (link in links.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }.sortedByDescending { it.quality }.take(4)) {
                    val (played, position, playerError) = realPlay(context, link)
                    if (played) return Result(target.name, "PASS", query, result.name, links.size, position)
                    lastError = playerError ?: "ExoPlayer position ilerlemedi: ${position}ms"
                    if (antiBot(lastError)) return Result(target.name, "BLOCKED", query, result.name, links.size, position, lastError)
                }
            }
        }

        return Result(target.name, if (antiBot(lastError)) "BLOCKED" else "FAIL", error = lastError.ifBlank { "Test sorgularında çalışır sonuç bulunamadı" })
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context, true)
        delay(2_000L)
        val results = targets.map { testProvider(context, it) }
        writeReport(context, results)
        results.forEach { println("NEON_PLAYBACK provider=${it.provider} status=${it.status} links=${it.links} positionMs=${it.positionMs} query=${it.query} item=${it.item} error=${it.error}") }

        assertTrue("Hiç gerçek playback PASS olmadı: $results", results.any { it.status == "PASS" })
        assertTrue("Gerçek playback FAIL bulundu: ${results.filter { it.status == "FAIL" }}", results.none { it.status == "FAIL" })
    }
}
// smoke trigger v2

// real player smoke v5
