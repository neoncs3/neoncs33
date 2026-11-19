package com.neoncs3.newtvlive

import android.util.Base64
import android.util.Log
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.ArrayDeque
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
import org.json.JSONArray
import java.net.URI

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

    private suspend fun emit(mediaUrl: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(pageUrl) ?: mainUrl
        callback(
            newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Origin" to origin
                )
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channelId = idRegex.find(data)?.groupValues?.getOrNull(1) ?: return false
        val channelName = getChannels().firstOrNull { it.url == data }?.name ?: "Kanal " + channelId
        val queue = ArrayDeque<Pair<String, String>>()
        listOf(
            "https://dembed.st/premiumtv/daddy.php?id=" + channelId,
            "https://dembed.top/premiumtv/daddy.php?id=" + channelId,
            "https://dlive.sx/stream/stream-" + channelId + ".php",
            "https://dlhd.pk/stream/stream-" + channelId + ".php",
            data,
        ).forEach { queue.addLast(it to (mainUrl + "/")) }

        val visited = linkedSetOf<String>()
        while (queue.isNotEmpty() && visited.size < 12) {
            val pair = queue.removeFirst()
            val pageUrl = pair.first
            val referer = pair.second
            if (!visited.add(pageUrl)) continue
            val response = runCatching {
                app.get(pageUrl, headers = headers(referer), referer = referer, timeout = 18)
            }.onFailure {
                Log.w(name, "Player sayfası başarısız: " + it.javaClass.simpleName)
            }.getOrNull() ?: continue
            if (response.code !in 200..299) {
                Log.w(name, "Player sayfası HTTP " + response.code)
                continue
            }

            val player = parseDembedPlayer(response.text)
            if (player != null) {
                val localUrl = NewTVLiveProxy.register(player, channelName)
                callback(
                    newExtractorLink(name, "$name • Tokenlı yayın", localUrl, ExtractorLinkType.M3U8) {
                        this.referer = "http://127.0.0.1/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    },
                )
                Log.i(name, "Tokenlı yayın oturumu oluşturuldu: " + channelName)
                return true
            }

            response.document.select("iframe[src]").forEach { frame ->
                val absolute = runCatching {
                    URI(response.url).resolve(frame.attr("src").trim().replace("&amp;", "&")).toString()
                }.getOrNull() ?: return@forEach
                val host = runCatching { URI(absolute).host?.lowercase().orEmpty() }.getOrDefault("")
                if (host.contains("dembed.") || absolute.contains("premiumtv/daddy.php", true)) {
                    queue.addLast(absolute to response.url)
                }
            }

            val currentHost = runCatching { URI(pageUrl).host?.lowercase().orEmpty() }.getOrDefault("")
            if (!currentHost.contains("dembed.")) {
                extractM3u8(response.text).firstOrNull { raw ->
                    val host = runCatching { URI(raw).host?.lowercase().orEmpty() }.getOrDefault("")
                    raw.contains(".m3u8", true) && !host.contains("dembed.") && !host.endsWith("newkso.ru")
                }?.let { raw ->
                    val direct = runCatching { URI(response.url).resolve(raw).toString() }.getOrDefault(raw)
                    emit(direct, response.url, callback)
                    return true
                }
            }
        }

        Log.e(name, "Dembed oynatıcısı bulunamadı; kanal=" + channelId + ", taranan=" + visited.size)
        return false
    }
}


private object NewTVLiveProxy {
    private const val TAG = "NewTVLiveProxy"
    private const val TOKEN_ORIGIN = "https://dembed.st"
    private val sessions = ConcurrentHashMap<String, Session>()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "NewTVLiveProxy-Worker").apply { isDaemon = true }
    }
    private val serverLock = Any()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var localPort = -1

    private class Session(val id: String, val player: DembedPlayer, val channel: String) {
        val mutex = Any()
        @Volatile var token: String? = null
        @Volatile var key: ByteArray? = null
        @Volatile var renewAt = 0L
        @Volatile var lastRenew = 0L
        val created = System.currentTimeMillis()
    }

    fun register(player: DembedPlayer, channel: String): String {
        val port = start()
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.created > 6 * 60 * 60 * 1000L }
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(id, player, channel)
        return "http://127.0.0.1:" + port + "/playlist.m3u8?sid=" + id
    }

    private fun start(): Int {
        if (server?.isClosed == false && localPort > 0) return localPort
        synchronized(serverLock) {
            if (server?.isClosed == false && localPort > 0) return localPort
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 32)
            server = socket
            localPort = socket.localPort
            Thread({
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor.execute { serve(client) }
                    } catch (e: Exception) {
                        if (!socket.isClosed) Log.w(TAG, "accept failed: " + e.message)
                    }
                }
            }, "NewTVLiveProxy-Accept").apply { isDaemon = true; start() }
            Log.i(TAG, "Local HLS proxy started at 127.0.0.1:" + localPort)
            return localPort
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val firstLine = reader.readLine().orEmpty()
                if (firstLine.isBlank()) return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val parts = firstLine.split(' ')
                if (parts.size < 2 || !parts[0].equals("GET", true)) {
                    send(client, 405, "text/plain", ByteArray(0))
                    return
                }
                val uri = URI("http://127.0.0.1" + parts[1])
                val query = parseQuery(uri.rawQuery.orEmpty())
                val session = sessions[query["sid"].orEmpty()]
                if (session == null) {
                    send(client, 410, "text/plain", ByteArray(0))
                    return
                }
                val upstream = query["url"].orEmpty()
                when (uri.path) {
                    "/playlist.m3u8" -> send(client, 200, "application/vnd.apple.mpegurl",
                        playlist(session, upstream.takeIf { it.isNotBlank() }).toByteArray(StandardCharsets.UTF_8))
                    "/segment" -> send(client, 200, "video/mp2t", segment(session, upstream))
                    "/resource" -> send(client, 200, "application/octet-stream", resource(upstream))
                    "/health" -> send(client, 200, "text/plain", "ok".toByteArray())
                    else -> send(client, 404, "text/plain", ByteArray(0))
                }
            } catch (e: Exception) {
                Log.e(TAG, "proxy error: " + e.javaClass.simpleName + ": " + e.message)
                runCatching { send(client, 502, "text/plain", "upstream error".toByteArray()) }
            }
        }
    }

    private fun send(socket: Socket, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 410 -> "Gone"; else -> "Bad Gateway"
        }
        val head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            if (part.isNotBlank()) {
                result[URLDecoder.decode(part.substringBefore('='), "UTF-8")] =
                    URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
        }
        return result
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun getTokenUrl(s: Session): String {
        val base = s.player.edge + "/api/playlist-token?slug=" + enc(s.player.slug)
        return if (s.player.ticket.isNullOrBlank()) base else base + "&tk=" + enc(s.player.ticket)
    }

    private fun renew(s: Session, force: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!force && !s.token.isNullOrBlank() && now < s.renewAt) return s.token
        synchronized(s.mutex) {
            val checkTime = System.currentTimeMillis()
            if (!force && !s.token.isNullOrBlank() && checkTime < s.renewAt) return s.token
            if (force && checkTime - s.lastRenew < 2_000L) return s.token
            s.lastRenew = checkTime
            val req = Request.Builder()
                .url(getTokenUrl(s))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", TOKEN_ORIGIN)
                .header("Referer", TOKEN_ORIGIN + "/")
                .build()
            val fresh = runCatching {
                app.getBaseClient().newCall(req).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "playlist-token HTTP " + response.code + "; channel=" + s.channel)
                        null
                    } else {
                        val json = JSONObject(body)
                        val value = json.optString("token").takeIf { it.isNotBlank() && it != "null" }
                        if (value.isNullOrBlank()) null else {
                            val k = json.optString("k").takeIf { it.isNotBlank() && it != "null" }
                            s.key = k?.let { decodeB64(it) }?.takeIf { it.size == 16 }
                            val ttl = json.optLong("ttl", 120L).coerceAtLeast(20L)
                            s.token = value
                            s.renewAt = System.currentTimeMillis() + ttl * 500L
                            Log.i(TAG, "token acquired ttl=" + ttl + "s; sealed=" + (s.key != null))
                            value
                        }
                    }
                }
            }.getOrElse {
                Log.e(TAG, "token request exception: " + it.javaClass.simpleName + ": " + it.message)
                null
            }
            return fresh ?: if (!force && checkTime < s.renewAt) s.token else null
        }
    }

    private fun decodeB64(raw: String): ByteArray {
        val value = raw.replace('-', '+').replace('_', '/')
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun withToken(url: String, token: String?, s: Session): String {
        if (token.isNullOrBlank()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val edge = runCatching { URI(s.player.edge).host }.getOrNull()
        if (edge.isNullOrBlank() || !edge.equals(uri.host, true)) return url
        val params = uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() && !it.startsWith("t=") && !it.startsWith("_=") }
            .toMutableList()
        params.add("t=" + enc(token))
        params.add("_=" + System.currentTimeMillis())
        return uri.scheme + "://" + uri.rawAuthority + uri.rawPath.orEmpty() + "?" + params.joinToString("&")
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Referer", TOKEN_ORIGIN + "/")
        .header("Origin", TOKEN_ORIGIN)
        .header("Accept", "*/*")
        .build()

    private fun playlist(s: Session, nested: String?): String {
        val upstream = nested?.takeIf { it.isNotBlank() } ?: s.player.source
        var token = renew(s) ?: throw IllegalStateException("playlist-token unavailable")
        var reqUrl = withToken(upstream, token, s)
        var status = 0
        var body = ""
        var finalUrl = upstream
        for (attempt in 0..1) {
            app.getBaseClient().newCall(request(reqUrl)).execute().use { response ->
                status = response.code
                finalUrl = response.request.url.toString()
                body = response.body?.string().orEmpty()
            }
            if (status == 403 && attempt == 0) {
                token = renew(s, true) ?: throw IllegalStateException("playlist 403; token renew failed")
                reqUrl = withToken(upstream, token, s)
            } else break
        }
        if (status !in 200..299) throw IllegalStateException("playlist HTTP " + status)
        return rewrite(decryptPlaylist(body, s.key), finalUrl, s)
    }

    private fun decryptPlaylist(body: String, key: ByteArray?): String {
        if (key == null || key.size != 16) return body
        val media = body.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (media.any { it.contains("://") }) return body
        var sequence = 0L
        val out = StringBuilder(body.length + 256)
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                }
                out.append(line).append('\n')
            } else {
                val iv = ByteArray(16)
                val current = sequence++
                for (i in 0 until 8) iv[15 - i] = ((current ushr (i * 8)) and 0xff).toByte()
                val decoded = runCatching {
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    String(cipher.doFinal(decodeB64(line)), StandardCharsets.UTF_8)
                }.getOrNull()
                out.append(decoded?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: line).append('\n')
            }
        }
        return out.toString()
    }

    private fun rewrite(body: String, base: String, s: Session): String {
        val out = StringBuilder(body.length + 1024)
        val uriAttr = Regex("""URI=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                out.append(uriAttr.replace(line) { match ->
                    "URI=\"" + localUrl(resolve(match.groupValues[1], base), s) + "\""
                }).append('\n')
            } else out.append(localUrl(resolve(line, base), s)).append('\n')
        }
        return out.toString()
    }

    private fun resolve(raw: String, base: String): String =
        runCatching { URI(base).resolve(raw).toString() }.getOrDefault(raw)

    private fun localUrl(url: String, s: Session): String {
        val clean = url.substringBefore('?')
        val path = when {
            clean.endsWith(".m3u8", true) -> "/playlist.m3u8"
            clean.endsWith(".key", true) || clean.endsWith(".mp4", true) -> "/resource"
            else -> "/segment"
        }
        return "http://127.0.0.1:" + localPort + path + "?sid=" + s.id + "&url=" + enc(url)
    }

    private fun fetch(url: String): Pair<Int, ByteArray> =
        app.getBaseClient().newCall(request(url)).execute().use { response ->
            response.code to (response.body?.bytes() ?: ByteArray(0))
        }

    private fun segment(s: Session, url: String): ByteArray {
        var result = fetch(url)
        if (result.first == 403) {
            renew(s, true)
            result = fetch(url)
        }
        if (result.first !in 200..299 || result.second.isEmpty()) throw IllegalStateException("segment HTTP " + result.first)
        return unwrap(result.second)
    }

    private fun resource(url: String): ByteArray {
        val result = fetch(url)
        if (result.first !in 200..299 || result.second.isEmpty()) throw IllegalStateException("resource HTTP " + result.first)
        return result.second
    }

    private fun unwrap(input: ByteArray): ByteArray {
        if (input.size >= 189 && input[0] == 0x47.toByte() && input[188] == 0x47.toByte()) return input
        if (input.size < 16) throw IllegalStateException("segment body too small")
        val raw = "TIKTIKRAW".toByteArray(StandardCharsets.US_ASCII)
        val rawAt = find(input, raw)
        if (rawAt >= 0) {
            val pos = rawAt + raw.size
            if (pos < input.size && input[pos] == 0x47.toByte()) return input.copyOfRange(pos, input.size)
        }
        if (input.size >= 33 && input[0] == 0x89.toByte() && input[1] == 'P'.code.toByte() &&
            input[2] == 'N'.code.toByte() && input[3] == 'G'.code.toByte()
        ) {
            val pixels = pngPixels(input)
            if (pixels != null && pixels.size >= 12 &&
                pixels.copyOfRange(0, 8).contentEquals("TIKTIKPX".toByteArray(StandardCharsets.US_ASCII))
            ) {
                val length = readU32(pixels, 8)
                if (length > 0 && length <= pixels.size - 12L) {
                    return GZIPInputStream(ByteArrayInputStream(pixels, 12, length.toInt())).use { it.readBytes() }
                }
            }
            if (pixels != null && pixels.size >= 189 && pixels[0] == 0x47.toByte() && pixels[188] == 0x47.toByte()) return pixels
        }
        throw IllegalStateException("unknown disguised segment")
    }

    private fun pngPixels(input: ByteArray): ByteArray? = runCatching {
        var offset = 8
        var width = 0
        var height = 0
        var depth = 0
        var color = 0
        var interlace = 0
        val idat = ByteArrayOutputStream()
        while (offset + 12 <= input.size) {
            val length = readU32(input, offset)
            if (length < 0 || length > input.size - offset - 12L) break
            val size = length.toInt()
            val type = String(input, offset + 4, 4, StandardCharsets.US_ASCII)
            val dataAt = offset + 8
            when (type) {
                "IHDR" -> {
                    if (size < 13) return@runCatching null
                    width = readU32(input, dataAt).toInt()
                    height = readU32(input, dataAt + 4).toInt()
                    depth = input[dataAt + 8].toInt() and 0xff
                    color = input[dataAt + 9].toInt() and 0xff
                    interlace = input[dataAt + 12].toInt() and 0xff
                }
                "IDAT" -> idat.write(input, dataAt, size)
                "IEND" -> {
                    val trailer = offset + 12
                    if (trailer < input.size && input[trailer] == 0x47.toByte()) return@runCatching input.copyOfRange(trailer, input.size)
                    break
                }
            }
            offset += 12 + size
        }
        val bpp = when (color) { 2 -> 3; 6 -> 4; else -> return@runCatching null }
        if (width <= 0 || height <= 0 || depth != 8 || interlace != 0 ||
            width.toLong() * height.toLong() * bpp > 32L * 1024L * 1024L || idat.size() == 0
        ) return@runCatching null
        val stride = width * bpp
        val expected = (stride.toLong() + 1L) * height.toLong()
        if (expected > 40L * 1024L * 1024L) return@runCatching null
        val inflated = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { it.readBytes() }
        if (inflated.size.toLong() < expected) return@runCatching null
        val rgb = ByteArrayOutputStream(width * height * 3)
        val prev = ByteArray(stride)
        val row = ByteArray(stride)
        var p = 0
        for (y in 0 until height) {
            val filter = inflated[p++].toInt() and 0xff
            for (x in 0 until stride) {
                val raw = inflated[p++].toInt() and 0xff
                val left = if (x >= bpp) row[x - bpp].toInt() and 0xff else 0
                val up = prev[x].toInt() and 0xff
                val upperLeft = if (x >= bpp) prev[x - bpp].toInt() and 0xff else 0
                val pred = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upperLeft)
                    else -> return@runCatching null
                }
                row[x] = (raw + pred).toByte()
            }
            if (color == 2) rgb.write(row) else {
                var x = 0
                while (x < stride) { rgb.write(row, x, 3); x += 4 }
            }
            System.arraycopy(row, 0, prev, 0, stride)
        }
        rgb.toByteArray()
    }.getOrNull()

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when { pa <= pb && pa <= pc -> a; pb <= pc -> b; else -> c }
    }

    private fun find(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || pattern.size > data.size) return -1
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readU32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xff) shl 24) or ((data[at + 1].toLong() and 0xff) shl 16) or
            ((data[at + 2].toLong() and 0xff) shl 8) or (data[at + 3].toLong() and 0xff)
}
