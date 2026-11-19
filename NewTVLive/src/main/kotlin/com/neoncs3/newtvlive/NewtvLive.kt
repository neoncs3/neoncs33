package com.neoncs3.newtvlive

import android.util.Base64
import android.util.Log
import okhttp3.Request as OkHttpRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


private data class DembedPlayer(val source: String, val edge: String, val slug: String, val ticket: String?)

private val dembedChannelRegex = Regex("""const\s+CHANNEL\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val dembedEdgeRegex = Regex("""const\s+EDGE\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val dembedSrcRegex = Regex("""const\s+SRC\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
private val dembedTicketRegex = Regex(
    """(?:data-ticket|["'](?:ticket|tk)["']\s*[:=]|(?:var|let|const)\s+(?:ticket|tk)\s*=)\s*["']([^"']{12,})["']""",
    RegexOption.IGNORE_CASE,
)

private fun parseDembedPlayer(htmlRaw: String): DembedPlayer? {
    val html = htmlRaw.replace("\\/", "/").replace("&amp;", "&").replace("\\\"", "\"")
    val source = dembedSrcRegex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val edge = dembedEdgeRegex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty().trimEnd('/')
    val slug = dembedChannelRegex.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (source.isBlank() || edge.isBlank() || slug.isBlank()) return null
    val sourceHost = runCatching { URI(source).host }.getOrNull()
    val edgeHost = runCatching { URI(edge).host }.getOrNull()
    if (sourceHost.isNullOrBlank() || edgeHost.isNullOrBlank() || !sourceHost.equals(edgeHost, true)) return null
    return DembedPlayer(source, edge, slug, dembedTicketRegex.find(html)?.groupValues?.getOrNull(1)?.trim())
}

class NewTVLive : MainAPI() {
    override var mainUrl = "https://tv.newtane.com"
    override var name = "NewTV Live"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    companion object {
        private const val API = "https://newtvproxy.newtane.com/api"
        private const val PAGE_SIZE = 100
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private val idRegex = Regex("""/live/(\d+)""")

        // Yayın adresi kalıpları (dex'te görülenler)
        private val m3u8Quoted = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""")
        private val srcConst = Regex("""const\s+SRC\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
        private val fileKey = Regex("""(?:streamUrl|file|source)\s*:\s*["'](https?:[^"']+\.m3u8[^"']*)["']""")
        private val atobRegex = Regex("""(?:source\s*:\s*)?window\.atob\(["']([^"']+)["']\)""")

        // Kanal id'leri DaddyLive id'leriyle aynı görünüyor; embed yedekleri
        private val EMBEDS = listOf(
            "https://dembed.top/premiumtv/daddy.php?id=",
            "https://dembed.st/premiumtv/daddy.php?id="
        )
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/json,*/*;q=0.8"
    )

    // ------------------------------------------------------------------ katalog
    private val cacheMutex = Mutex()
    private var cache: List<LiveSearchResponse> = emptyList()
    private var cacheTime = 0L

    private fun cacheFresh() =
        cache.isNotEmpty() && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS

    private suspend fun getChannels(): List<LiveSearchResponse> {
        if (cacheFresh()) return cache
        return cacheMutex.withLock {
            if (cacheFresh()) return@withLock cache
            val fresh = fetchChannels()
            if (fresh.isNotEmpty()) {
                cache = fresh
                cacheTime = System.currentTimeMillis()
            }
            if (fresh.isNotEmpty()) fresh else cache
        }
    }

    private val cfKiller = CloudflareKiller()
    private var lastError = ""

    // API Cloudflare arkasında; tarayıcı gibi başlıklar gönder
    private fun apiHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/live",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-site"
    )

    private suspend fun requestChannels(useKiller: Boolean): JSONArray? {
        val resp = runCatching {
            if (useKiller) {
                app.get("$API/channels", headers = apiHeaders(), interceptor = cfKiller, timeout = 30)
            } else {
                app.get("$API/channels", headers = apiHeaders(), timeout = 20)
            }
        }.onFailure { lastError = "İstek hatası: ${it.message}" }.getOrNull() ?: return null

        if (resp.code !in 200..299) {
            lastError = "HTTP ${resp.code}"
            return null
        }
        return runCatching { JSONArray(resp.text) }.getOrNull()
            ?: run { lastError = "JSON okunamadı: ${resp.text.take(80)}"; null }
    }

    private suspend fun fetchChannels(): List<LiveSearchResponse> {
        // Önce düz istek, olmazsa Cloudflare çözücüyle tekrar dene
        val arr = requestChannels(false) ?: requestChannels(true) ?: return emptyList()

        val out = ArrayList<LiveSearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("channel_id").trim().takeIf { it.isNotBlank() && it != "null" } ?: continue
            val title = o.optString("channel_name").trim().takeIf { it.isNotBlank() && it != "null" } ?: continue
            val logo = o.optString("logo_url").takeIf { it.isNotBlank() && it != "null" }
            out.add(
                newLiveSearchResponse(title, "$mainUrl/live/$id", TvType.Live) {
                    this.posterUrl = logo
                }
            )
        }
        return out.distinctBy { it.url }.sortedBy { it.name.lowercase() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        if (channels.isEmpty()) {
            throw ErrorLoadingException("Kanal listesi alınamadı ($lastError)")
        }
        val start = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val pageChannels = channels.drop(start).take(PAGE_SIZE)
        return newHomePageResponse(
            listOf(HomePageList("Canlı Kanallar", pageChannels, false)),
            start + pageChannels.size < channels.size
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getChannels().filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        if (idRegex.find(url) == null) throw ErrorLoadingException("Kanal ID bulunamadı")
        val known = runCatching { getChannels().firstOrNull { it.url == url } }.getOrNull()
        return newLiveStreamLoadResponse(known?.name ?: "Kanal", url, url) {
            this.posterUrl = known?.posterUrl
        }
    }

    // ------------------------------------------------------------------ linkler
    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    private fun extractM3u8(raw: String): List<String> {
        val text = raw.replace("\\/", "/").replace("&amp;", "&")
        val out = linkedSetOf<String>()
        m3u8Quoted.findAll(text).forEach { out.add(it.groupValues[1]) }
        srcConst.findAll(text).forEach { out.add(it.groupValues[1]) }
        fileKey.findAll(text).forEach { out.add(it.groupValues[1]) }
        atobRegex.findAll(text).forEach {
            val decoded = runCatching {
                String(android.util.Base64.decode(it.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull()
            if (decoded != null && decoded.contains(".m3u8")) out.add(decoded.trim())
        }
        return out.toList()
    }


    private fun headersFrom(request: OkHttpRequest?): Map<String, String> {
        if (request == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        request.headers.names().forEach { headerName ->
            request.header(headerName)?.takeIf { it.isNotBlank() }?.let { out[headerName] = it }
        }
        return out
    }

    private suspend fun resolvePlayerPage(pageUrl: String): Pair<OkHttpRequest?, List<OkHttpRequest>> {
        val resolver = WebViewResolver(
            interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
            additionalUrls = listOf(
                Regex("""(?i)/api/playlist-token(?:\?|$)"""),
                Regex("""(?i)/premiumtv/daddy\.php(?:\?|$)""")
            ),
            userAgent = null,
            useOkhttp = false,
            timeout = 22_000L
        )
        return resolver.resolveUsingWebView(
            url = pageUrl,
            referer = "$mainUrl/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
            )
        )
    }

    private suspend fun emitLocal(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "Canlı Yayın (HD)",
                url = mediaUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
            }
        )
    }

    private suspend fun tryBrowserPlayer(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = runCatching {
            resolvePlayerPage(pageUrl)
        }.onFailure {
            Log.w(name, "WebView oynatıcı çözümleme hatası: " + it.message)
        }.getOrNull() ?: return false

        val manifestRequest = resolved.first ?: return false
        val manifestUrl = manifestRequest.url.toString()
            .takeIf { it.contains(".m3u8", ignoreCase = true) }
            ?: return false

        val tokenRequest = resolved.second.lastOrNull {
            it.url.encodedPath.contains("/api/playlist-token", ignoreCase = true)
        }
        val manifestHeaders = headersFrom(manifestRequest)
        val tokenHeaders = headersFrom(tokenRequest)
        val playbackReferer = manifestRequest.header("Referer")
            ?: tokenRequest?.header("Referer")
            ?: "https://dembed.st/"
        val playbackOrigin = manifestRequest.header("Origin")
            ?: tokenRequest?.header("Origin")
            ?: originOf(playbackReferer)
            ?: "https://dembed.st"

        Log.i(
            name,
            "Oynatıcı manifest isteği yakalandı; ticket=" +
                (!tokenRequest?.url?.queryParameter("tk").isNullOrBlank())
        )
        val localUrl = NewTVLiveProxy.create(
            manifestUrl = manifestUrl,
            referer = playbackReferer,
            origin = playbackOrigin,
            manifestHeaders = manifestHeaders,
            tokenRequestUrl = tokenRequest?.url?.toString(),
            tokenHeaders = tokenHeaders
        )
        emitLocal(localUrl, playbackReferer, callback)
        return true
    }

    private suspend fun tryLegacyEmbed(
        id: String,
        pageReferer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (base in EMBEDS) {
            val embedUrl = base + id
            val response = runCatching {
                app.get(embedUrl, headers = headers(pageReferer), referer = pageReferer, timeout = 12)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) continue

            val html = response.text.replace("\\/", "/").replace("\\\"", "\"")
            val src = Regex("""const\s+SRC\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                    .find(html)?.value
                ?: continue
            val edge = Regex("""const\s+EDGE\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: originOf(src)
                ?: continue
            val slug = Regex("""const\s+CHANNEL\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""/(premium\d+)/index\.m3u8""").find(src)?.groupValues?.getOrNull(1)
                ?: continue

            val tokenUrl = edge.trimEnd('/') + "/api/playlist-token?slug=" +
                URLEncoder.encode(slug, "UTF-8")
            val tokenHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://dembed.st/",
                "Origin" to "https://dembed.st",
                "Accept" to "application/json, text/plain, */*"
            )
            val tokenResult = runCatching {
                app.get(tokenUrl, headers = tokenHeaders, referer = "https://dembed.st/", timeout = 10)
            }.getOrNull()
            val token = runCatching {
                org.json.JSONObject(tokenResult?.text.orEmpty()).optString("token")
            }.getOrDefault("").takeIf { it.isNotBlank() && it != "null" }

            val sourceUrl = if (token != null) NewTVLiveProxy.withToken(src, token) else src
            val local = NewTVLiveProxy.create(
                manifestUrl = sourceUrl,
                referer = "https://dembed.st/",
                origin = "https://dembed.st",
                manifestHeaders = tokenHeaders,
                tokenRequestUrl = tokenUrl,
                tokenHeaders = tokenHeaders
            )
            emitLocal(local, "https://dembed.st/", callback)
            return true
        }
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idRegex.find(data)?.groupValues?.getOrNull(1) ?: return false
        val playerPages = listOf(
            "https://dlive.sx/stream/stream-" + id + ".php",
            "https://dlhd.pk/stream/stream-" + id + ".php",
            data
        ).distinct()

        for (pageUrl in playerPages) {
            if (tryBrowserPlayer(pageUrl, callback)) return true
        }
        return tryLegacyEmbed(id, "$mainUrl/", callback)
    }

}

/** Local proxy: ticketed HLS token renewal, manifest rewrite and packed TS decoding. */
private object NewTVLiveProxy {
    private data class Session(
        val id: String,
        val manifestUrl: String,
        val referer: String,
        val origin: String,
        val manifestHeaders: Map<String, String>,
        val tokenHeaders: Map<String, String>,
        val edge: String?,
        val slug: String?,
        val ticket: String?,
        @Volatile var token: String?,
        @Volatile var segmentKey: ByteArray?,
        @Volatile var renewAt: Long,
        @Volatile var retryAfter: Long = 0L,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var serverPort: Int = 0
    private val sessions = ConcurrentHashMap<String, Session>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "NewTVLiveProxy-worker").apply { isDaemon = true }
    }

    @Synchronized
    private fun ensureServer(): Int {
        val active = server
        if (active != null && !active.isClosed) return serverPort
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
        server = socket
        serverPort = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                runCatching { workers.execute { handleClient(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }, "NewTVLiveProxyServer").apply {
            isDaemon = true
            start()
        }
        return serverPort
    }

    fun create(
        manifestUrl: String,
        referer: String,
        origin: String,
        manifestHeaders: Map<String, String>,
        tokenRequestUrl: String?,
        tokenHeaders: Map<String, String>
    ): String {
        val uri = runCatching { URI(manifestUrl) }.getOrNull()
        val edge = uri?.let {
            it.scheme + "://" + it.host + if (it.port >= 0) ":" + it.port else ""
        }
        val slug = uri?.path?.let { path ->
            Regex("""/(premium\d+)/index\.m3u8""", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(1)
        } ?: queryValue(tokenRequestUrl.orEmpty(), "slug")
        val ticket = queryValue(tokenRequestUrl.orEmpty(), "tk")
            ?: queryValue(tokenRequestUrl.orEmpty(), "ticket")
        val initialToken = queryValue(manifestUrl, "t")
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = Session(
            id = id,
            manifestUrl = manifestUrl,
            referer = referer.ifBlank { "https://dembed.st/" },
            origin = origin.ifBlank { "https://dembed.st" },
            manifestHeaders = manifestHeaders,
            tokenHeaders = tokenHeaders,
            edge = edge,
            slug = slug,
            ticket = ticket,
            token = initialToken,
            segmentKey = null,
            renewAt = 0L
        )
        if (sessions.size >= 20) {
            sessions.values.sortedBy { it.createdAt }.take(sessions.size - 15).forEach {
                sessions.remove(it.id)
            }
        }
        sessions[id] = session
        return "http://127.0.0.1:" + ensureServer() + "/playlist.m3u8?id=" + id
    }

    fun withToken(url: String, token: String): String =
        replaceQuery(url, mapOf("t" to token, "_" to System.currentTimeMillis().toString()))

    private fun queryValue(url: String, key: String): String? = runCatching {
        val raw = URI(url).rawQuery ?: return@runCatching null
        raw.split("&").firstOrNull {
            URLDecoder.decode(it.substringBefore("="), "UTF-8") == key
        }?.substringAfter("=", "")?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()

    private fun replaceQuery(url: String, values: Map<String, String>): String {
        val base = url.substringBefore("#")
        val path = base.substringBefore("?")
        val old = base.substringAfter("?", "")
        val pairs = old.split("&").filter { it.isNotBlank() }.filterNot {
            values.containsKey(URLDecoder.decode(it.substringBefore("="), "UTF-8"))
        }.toMutableList()
        values.forEach { (key, value) ->
            pairs.add(URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8"))
        }
        return if (pairs.isEmpty()) path else path + "?" + pairs.joinToString("&")
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        raw.split("&").forEach { part ->
            if (part.isBlank()) return@forEach
            val key = runCatching { URLDecoder.decode(part.substringBefore("="), "UTF-8") }
                .getOrDefault(part.substringBefore("="))
            val value = runCatching { URLDecoder.decode(part.substringAfter("=", ""), "UTF-8") }
                .getOrDefault(part.substringAfter("=", ""))
            result[key] = value
        }
        return result
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = 25_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val firstLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = firstLine.split(" ")
                if (parts.size < 2) {
                    send(socket, 400, "Bad Request", "text/plain", ByteArray(0))
                    return
                }
                val target = parts[1]
                val path = target.substringBefore("?")
                val params = parseQuery(target.substringAfter("?", ""))
                val session = sessions[params["id"].orEmpty()]
                if (session == null) {
                    send(socket, 404, "Not Found", "text/plain", ByteArray(0))
                    return
                }
                when (path) {
                    "/playlist.m3u8" -> servePlaylist(socket, session, params["u"])
                    "/seg.ts" -> serveSegment(socket, session, params["u"].orEmpty(), true)
                    "/asset" -> serveSegment(socket, session, params["u"].orEmpty(), false)
                    else -> send(socket, 404, "Not Found", "text/plain", ByteArray(0))
                }
            }.onFailure {
                Log.w("NewTVLiveProxy", "İstek işlenemedi: " + it.message)
                runCatching { send(socket, 502, "Bad Gateway", "text/plain", ByteArray(0)) }
            }
        }
    }

    private fun send(socket: Socket, code: Int, reason: String, type: String, body: ByteArray) {
        val output = socket.getOutputStream()
        val head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + body.size + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun upstreamRequest(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val builder = OkHttpRequest.Builder().url(url).get()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("Host", true) &&
                !key.equals("Content-Length", true) &&
                !key.equals("Connection", true)
            ) runCatching { builder.header(key, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", USER_AGENT)
        }
        val response = app.baseClient.newCall(builder.build()).execute()
        response.use {
            return it.code to (it.body?.bytes() ?: ByteArray(0))
        }
    }

    private fun refreshManifestUrl(session: Session, url: String, force: Boolean = false): String {
        val edge = session.edge ?: return url
        val slug = session.slug ?: return url
        val now = System.currentTimeMillis()
        synchronized(session) {
            if (!force && !session.token.isNullOrBlank() && now < session.renewAt) {
                return withToken(url, session.token!!)
            }
            if (!force && now < session.retryAfter) {
                return session.token?.let { withToken(url, it) } ?: url
            }
            val tokenUrl = edge.trimEnd('/') + "/api/playlist-token?slug=" +
                URLEncoder.encode(slug, "UTF-8") +
                if (!session.ticket.isNullOrBlank()) {
                    "&tk=" + URLEncoder.encode(session.ticket, "UTF-8")
                } else ""
            val headers = linkedMapOf<String, String>()
            headers.putAll(session.tokenHeaders)
            headers.putIfAbsent("User-Agent", session.manifestHeaders["User-Agent"] ?: USER_AGENT)
            headers.putIfAbsent("Referer", session.referer)
            headers.putIfAbsent("Origin", session.origin)
            headers.putIfAbsent("Accept", "application/json, text/plain, */*")

            val result = runCatching { upstreamRequest(tokenUrl, headers) }
                .getOrElse {
                    Log.w("NewTVLiveProxy", "Token isteği başarısız: " + it.message)
                    session.retryAfter = now + 15_000L
                    return session.token?.let { token -> withToken(url, token) } ?: url
                }
            if (result.first !in 200..299) {
                Log.w("NewTVLiveProxy", "Token servisi HTTP " + result.first)
                session.retryAfter = now + 15_000L
                return session.token?.let { token -> withToken(url, token) } ?: url
            }

            val json = runCatching {
                JSONObject(String(result.second, StandardCharsets.UTF_8))
            }.getOrNull() ?: run {
                session.retryAfter = now + 15_000L
                return session.token?.let { token -> withToken(url, token) } ?: url
            }
            val freshToken = json.optString("token")
            if (freshToken.isBlank() || freshToken == "null") {
                session.retryAfter = now + 15_000L
                return session.token?.let { token -> withToken(url, token) } ?: url
            }
            val encodedKey = json.optString("k").takeIf { it.isNotBlank() && it != "null" }
            session.segmentKey = encodedKey?.let { decodeBase64Url(it) }
            val ttl = json.optLong("ttl", 120L).coerceIn(20L, 300L)
            session.token = freshToken
            session.renewAt = now + ttl * 500L
            session.retryAfter = 0L
            return withToken(url, freshToken)
        }
    }

    private fun decodeBase64Url(value: String): ByteArray? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.decode(padded, Base64.NO_WRAP) }.getOrNull()
    }

    private fun decryptSegmentUrl(value: String, key: ByteArray?, sequence: Long): String? {
        if (key == null || key.size !in listOf(16, 24, 32)) return null
        val ciphertext = decodeBase64Url(value) ?: return null
        if (ciphertext.isEmpty()) return null
        return runCatching {
            val counter = ByteArray(16)
            var remaining = sequence
            for (index in 15 downTo 8) {
                counter[index] = (remaining and 0xff).toByte()
                remaining = remaining ushr 8
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(counter)
            )
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    private fun servePlaylist(socket: Socket, session: Session, overrideUrl: String?) {
        val initialUrl = overrideUrl?.takeIf { it.startsWith("http", true) } ?: session.manifestUrl
        var requestUrl = refreshManifestUrl(session, initialUrl)
        var result = runCatching { upstreamRequest(requestUrl, session.manifestHeaders) }
            .getOrElse { 0 to ByteArray(0) }
        if (result.first == 401 || result.first == 403) {
            requestUrl = refreshManifestUrl(session, initialUrl, true)
            result = runCatching { upstreamRequest(requestUrl, session.manifestHeaders) }
                .getOrElse { 0 to ByteArray(0) }
        }
        if (result.first !in 200..299) {
            Log.w("NewTVLiveProxy", "Manifest HTTP " + result.first)
            send(socket, if (result.first in 400..599) result.first else 502, "Upstream Error", "text/plain", ByteArray(0))
            return
        }
        val body = String(result.second, StandardCharsets.UTF_8)
        if (!body.trimStart().startsWith("#EXTM3U")) {
            Log.w("NewTVLiveProxy", "Manifest yanıtı M3U8 değil")
            send(socket, 502, "Bad Gateway", "text/plain", ByteArray(0))
            return
        }
        val rewritten = rewritePlaylist(body, requestUrl, session)
        send(socket, 200, "OK", "application/vnd.apple.mpegurl", rewritten.toByteArray(StandardCharsets.UTF_8))
    }

    private fun rewritePlaylist(text: String, manifestUrl: String, session: Session): String {
        val output = ArrayList<String>()
        val uriAttribute = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        var sequence = 0L
        for (original in text.lines()) {
            val trimmed = original.trim()
            if (trimmed.isEmpty()) {
                output.add(original)
                continue
            }
            if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:", true)) {
                sequence = trimmed.substringAfter(":").trim().toLongOrNull() ?: 0L
                output.add(original)
                continue
            }
            if (trimmed.startsWith("#")) {
                val rewritten = uriAttribute.replace(original) { match ->
                    val absolute = resolveUrl(match.groupValues[1], manifestUrl)
                    if (absolute.isBlank() || absolute.startsWith("data:", true)) {
                        match.value
                    } else {
                        val route = if (absolute.contains(".m3u8", true)) "playlist.m3u8" else "asset"
                        "URI=\"" + localAssetUrl(route, session.id, absolute) + "\""
                    }
                }
                output.add(rewritten)
                continue
            }

            val decrypted = if (!trimmed.startsWith("http", true) && !trimmed.contains(".")) {
                decryptSegmentUrl(trimmed, session.segmentKey, sequence)
            } else null
            val absolute = resolveUrl(decrypted ?: trimmed, manifestUrl)
            if (absolute.isBlank()) {
                output.add(original)
            } else {
                val route = if (absolute.contains(".m3u8", true)) "playlist.m3u8" else "seg.ts"
                output.add(localAssetUrl(route, session.id, absolute))
            }
            sequence++
        }
        return output.joinToString("\n", postfix = "\n")
    }

    private fun localAssetUrl(route: String, id: String, target: String): String {
        val port = ensureServer()
        return "http://127.0.0.1:" + port + "/" + route + "?id=" + id +
            "&u=" + URLEncoder.encode(target, "UTF-8")
    }

    private fun resolveUrl(raw: String, base: String): String {
        val cleaned = raw.trim().replace("&amp;", "&").replace("\\/", "/").trim('"', '\'', ' ')
        if (cleaned.isBlank()) return ""
        if (cleaned.startsWith("data:", true)) return cleaned
        return runCatching { URI(base).resolve(cleaned).toString() }.getOrDefault("")
    }

    private fun serveSegment(socket: Socket, session: Session, url: String, unwrap: Boolean) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            send(socket, 400, "Bad Request", "text/plain", ByteArray(0))
            return
        }
        val result = runCatching { upstreamRequest(url, session.manifestHeaders) }
            .getOrElse {
                Log.w("NewTVLiveProxy", "Segment isteği başarısız: " + it.message)
                0 to ByteArray(0)
            }
        if (result.first !in 200..299 || result.second.isEmpty()) {
            send(socket, if (result.first in 400..599) result.first else 502, "Upstream Error", "text/plain", ByteArray(0))
            return
        }
        val payload = if (unwrap) unwrapPayload(result.second) else result.second
        val contentType = if (unwrap) "video/mp2t" else assetContentType(url)
        send(socket, 200, "OK", contentType, payload)
    }

    private fun assetContentType(url: String): String {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault(url.lowercase())
        return when {
            path.endsWith(".vtt") -> "text/vtt"
            path.endsWith(".m4s") || path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".key") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    private fun findBytes(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun isTs(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset < 188 || data[offset] != 0x47.toByte()) return false
        val packets = minOf(4, (data.size - offset) / 188)
        for (i in 1 until packets) {
            if (data[offset + i * 188] != 0x47.toByte()) return false
        }
        return true
    }

    private fun trimTs(data: ByteArray): ByteArray {
        val size = data.size - data.size % 188
        return if (size == data.size) data else data.copyOf(size)
    }

    private fun gunzip(data: ByteArray): ByteArray? =
        runCatching { GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() } }.getOrNull()

    private fun unwrapPayload(raw: ByteArray): ByteArray {
        if (isTs(raw)) return trimTs(raw)
        val markerPx = "TIKTIKPX".toByteArray(StandardCharsets.US_ASCII)
        val markerRaw = "TIKTIKRAW".toByteArray(StandardCharsets.US_ASCII)
        val markerGzip = "TIKTIKTSGZ".toByteArray(StandardCharsets.US_ASCII)

        if (isPng(raw)) {
            unwrapPng(raw)?.let { pixels ->
                val at = findBytes(pixels, markerPx)
                if (at >= 0 && at + 12 <= pixels.size) {
                    val length = readUInt32BE(pixels, at + 8)
                    if (length > 0 && length <= pixels.size - at - 12) {
                        val packed = pixels.copyOfRange(at + 12, at + 12 + length)
                        gunzip(packed)?.takeIf { isTs(it) }?.let { return trimTs(it) }
                    }
                }
            }
            pngChunkTs(raw)?.let { return trimTs(it) }
        }

        val rawAt = findBytes(raw, markerRaw)
        if (rawAt >= 0) {
            val payload = raw.copyOfRange(rawAt + markerRaw.size, raw.size)
            if (isTs(payload)) return trimTs(payload)
        }
        val gzipAt = findBytes(raw, markerGzip)
        if (gzipAt >= 0) {
            gunzip(raw.copyOfRange(gzipAt + markerGzip.size, raw.size))
                ?.takeIf { isTs(it) }?.let { return trimTs(it) }
        }
        for (offset in 0..raw.size - 188) {
            if (isTs(raw, offset)) return trimTs(raw.copyOfRange(offset, raw.size))
        }
        return raw
    }

    private fun isPng(data: ByteArray): Boolean =
        data.size >= 8 &&
            data[0] == 137.toByte() && data[1] == 80.toByte() &&
            data[2] == 78.toByte() && data[3] == 71.toByte() &&
            data[4] == 13.toByte() && data[5] == 10.toByte() &&
            data[6] == 26.toByte() && data[7] == 10.toByte()

    private fun readUInt32BE(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return 0
        val value = ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)
        return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun pngChunkTs(data: ByteArray): ByteArray? {
        if (!isPng(data)) return null
        var offset = 8
        while (offset + 12 <= data.size) {
            val length = readUInt32BE(data, offset)
            if (offset + 12L + length > data.size) return null
            val type = String(data, offset + 4, 4, StandardCharsets.US_ASCII)
            val payload = data.copyOfRange(offset + 8, offset + 8 + length)
            if ((type == "eXIf" || type == "IDAT") && isTs(payload)) return trimTs(payload)
            offset += 12 + length
            if (type == "IEND") break
        }
        return null
    }

    private fun unwrapPng(data: ByteArray): ByteArray? {
        if (!isPng(data)) return null
        var offset = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val chunks = ArrayList<ByteArray>()
        var total = 0
        while (offset + 12 <= data.size) {
            val length = readUInt32BE(data, offset)
            if (offset + 12L + length > data.size) return null
            val type = String(data, offset + 4, 4, StandardCharsets.US_ASCII)
            val payload = data.copyOfRange(offset + 8, offset + 8 + length)
            when (type) {
                "IHDR" -> {
                    if (payload.size < 13) return null
                    width = readUInt32BE(payload, 0)
                    height = readUInt32BE(payload, 4)
                    bitDepth = payload[8].toInt() and 0xff
                    colorType = payload[9].toInt() and 0xff
                    interlace = payload[12].toInt() and 0xff
                }
                "IDAT" -> {
                    chunks.add(payload)
                    total += payload.size
                }
                "IEND" -> break
            }
            offset += 12 + length
        }
        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0 ||
            (colorType != 2 && colorType != 6) || total <= 0
        ) return null

        val bpp = if (colorType == 6) 4 else 3
        val stride = width.toLong() * bpp
        val expected = height.toLong() * (1L + stride)
        if (stride <= 0 || expected > 32L * 1024 * 1024) return null

        val compressed = ByteArrayOutputStream(total)
        chunks.forEach { compressed.write(it) }
        val inflated = runCatching {
            InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { it.readBytes() }
        }.getOrNull() ?: return null
        if (inflated.size < expected) return null

        val outputSize = width.toLong() * height * 3L
        if (outputSize > 32L * 1024 * 1024) return null
        val output = ByteArray(outputSize.toInt())
        var inputAt = 0
        var outputAt = 0
        var previous = ByteArray(stride.toInt())
        repeat(height) {
            val filter = inflated[inputAt++].toInt() and 0xff
            val current = ByteArray(stride.toInt())
            for (x in current.indices) {
                val value = inflated[inputAt + x].toInt() and 0xff
                val a = if (x >= bpp) current[x - bpp].toInt() and 0xff else 0
                val b = previous[x].toInt() and 0xff
                val c = if (x >= bpp) previous[x - bpp].toInt() and 0xff else 0
                val decoded = when (filter) {
                    0 -> value
                    1 -> value + a
                    2 -> value + b
                    3 -> value + ((a + b) shr 1)
                    4 -> value + paeth(a, b, c)
                    else -> return null
                }
                current[x] = decoded.toByte()
            }
            inputAt += current.size
            if (colorType == 2) {
                current.copyInto(output, outputAt)
                outputAt += current.size
            } else {
                var x = 0
                while (x < current.size) {
                    output[outputAt++] = current[x]
                    output[outputAt++] = current[x + 1]
                    output[outputAt++] = current[x + 2]
                    x += 4
                }
            }
            previous = current
        }
        return output
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}
