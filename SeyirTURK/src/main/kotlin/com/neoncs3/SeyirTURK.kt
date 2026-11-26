package com.neoncs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Public guest-facing SeyirTURK catalogue adapter.
 * It deliberately does not reproduce account/VIP checks, private identifiers,
 * undocumented tokens, or protected-stream mechanisms.
 */
class SeyirTURK : MainAPI() {
    override var mainUrl = "https://seyirturk.net"
    override var name = "SeyirTURK"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val requestHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json,text/plain,*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://seyirturk.net/",
            "X-Requested-With" to "XMLHttpRequest",
            "bety" to "jughead"
        )

    @Volatile
    private var cachedRoots: List<String>? = null

    override val mainPage: List<MainPageData> = listOf(
        MainPageData("Yeni Bölümü Eklenenler", "back/mainpage.php#first"),
        MainPageData("İzlemeye Devam Et", "back/mainpage.php#second"),
        MainPageData("Popüler İçerikler", "back/mainpage.php#third"),
        MainPageData("Canlı Kanallar", "kodi/iptv.php"),
        MainPageData("Radyo", "kodi/radyo.php?")
    )

    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

    // Values used by the supplied APK (versionCode 1005; guest defaults id=0/email="").
    private fun addApkRequestParameters(path: String): String {
        val value = path.trim()
        val isMenuRequest = value.startsWith("back/menu.php", true) ||
            value.startsWith("back/yearmenu.php", true) ||
            value.startsWith("back/ulkeler.php", true) ||
            value.startsWith("kodi/studios", true) ||
            value.startsWith("kodi/sites", true) ||
            value.startsWith("back/v2/menuler.php", true)

        if (isMenuRequest || !value.contains(".php", true)) return value
        // The Android app builds these query parameters after the endpoint's
        // own query string. Strip a dangling '?' so radyo.php does not become ?&u_id=...
        val base = value.trimEnd('?', '&')
        val separator = if (base.contains("?")) "&" else "?"
        return base + separator + "u_id=0&cmail=&vn=1005"
    }

    private fun decodeRootCheck(raw: String): String? {
        val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank()) return null
        return runCatching {
            String(Base64.decode(firstLine.reversed(), Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun clean(value: String?): String = value.orEmpty()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .trim()


    /**
     * SeyirTURK's Android client decodes API responses by reversing the
     * response text and then Base64-decoding it. The web API uses this for
     * rootcheck as well as catalogue/detail/stream endpoints.
     */
    private fun decodeApiResponse(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        // Plain responses must be preserved. The Android Base64 decoder can
        // be permissive, so always validate the decoded payload before using it.
        if (
            value.startsWith("{") ||
            value.startsWith("[") ||
            value.startsWith("#EXTM3U", true) ||
            value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("<")
        ) return value

        val compact = value.replace(Regex("\\s+"), "")
        if (compact.length < 8 ||
            !Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)
        ) return value

        return runCatching {
            val decoded = String(
                Base64.decode(compact.reversed(), Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()

            val readable =
                decoded.startsWith("{") ||
                decoded.startsWith("[") ||
                decoded.startsWith("#EXTM3U", true) ||
                decoded.startsWith("http://", true) ||
                decoded.startsWith("https://", true) ||
                Regex("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(?::\\d+)?(?:/.*)?$").matches(decoded)

            if (readable) decoded else value
        }.getOrDefault(value)
    }

    private fun normalizeRoot(value: String): String? {
        var candidate = clean(value).trim('"', '\'', ' ', '\n', '\r', '\t')
        if (candidate.isBlank()) return null
        if (candidate.startsWith("//")) candidate = "https:" + candidate

        if (!candidate.startsWith("http://", true) &&
            !candidate.startsWith("https://", true)
        ) {
            // The root-check endpoint can return a bare hostname. Reject
            // arbitrary messages and JSON instead of treating them as hosts.
            if (!Regex("(?i)^[a-z0-9.-]+(?::\\d+)?(?:/.*)?$").matches(candidate)) {
                return null
            }
            candidate = "https://" + candidate
        }

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.contains('.') || !Regex("(?i)^[a-z0-9.-]+$").matches(host)) return null
        return candidate.trimEnd('/')
    }

    /**
     * The APK uses http://seyirturk.net/rootcheck/android.php first.
     * Read that response as a server address (raw JSON/text), not as a
     * catalogue payload. Try decoded text only as a secondary representation.
     */
    private suspend fun apiRoots(): List<String> {
        cachedRoots?.let { return it }
        val discovered = mutableListOf<String>()

        val checks = listOf(
            "http://seyirturk.net/rootcheck/android.php",
            "https://seyirturk.net/rootcheck/android.php"
        )

        for (checkUrl in checks) {
            val rawBody = runCatching {
                app.get(checkUrl, headers = requestHeaders, timeout = 8000).text
            }.getOrNull().orEmpty().trim()
            if (rawBody.isBlank()) continue

            decodeRootCheck(rawBody)?.let(::normalizeRoot)?.let(discovered::add)
            val bodies = listOf(rawBody, decodeApiResponse(rawBody)).distinct()
            for (body in bodies) {
                val rootValue = runCatching {
                    val json = JSONObject(body)
                    sequenceOf("Root", "root", "url", "Url", "baseUrl", "BaseUrl", "server")
                        .mapNotNull { key ->
                            json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                        }
                        .firstOrNull()
                }.getOrNull()

                rootValue?.let { candidate ->
                    (normalizeRoot(candidate) ?: normalizeRoot(decodeApiResponse(candidate)))
                }?.let(discovered::add)

                Regex("""(?i)https?://[A-Za-z0-9.-]+(?::\d+)?(?:/[^\s"'<>]*)?""")
                    .findAll(body)
                    .mapNotNull { normalizeRoot(it.value) }
                    .forEach(discovered::add)

                normalizeRoot(body)?.let(discovered::add)
            }
        }

        // This is the APK's own configured default API root. seyirturk.net is
        // only the root-check host, not a confirmed catalogue backend.
        val roots = (discovered + listOf("https://habba.cloud/"))
            .mapNotNull(::normalizeRoot)
            .distinct()

        cachedRoots = roots
        Log.i(name, "API sunucuları bulundu: " + roots.joinToString(","))
        return roots
    }

    private suspend fun apiGet(path: String): String? {
        val cleanPath = path.trimStart('/')
        for (root in apiRoots()) {
            val rootBase = root.trimEnd('/')
            val apiBase = if (rootBase.endsWith("/sey", true)) rootBase else "$rootBase/sey"
            val adjustedPath = addApkRequestParameters(cleanPath)
            val urls = listOf(
                "$apiBase/$adjustedPath",
                "$rootBase/$adjustedPath"
            ).distinct()

            for (url in urls) {
                val response = runCatching {
                    app.get(
                        url,
                        headers = requestHeaders + ("Referer" to (rootBase + "/")),
                        timeout = 12000,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!response.isSuccessful) {
                    Log.w(name, "API HTTP " + response.code + ": " + url)
                    continue
                }

                val rawBody = response.text
                if (rawBody.isBlank()) continue

                val body = decodeApiResponse(rawBody)
                val lowerBody = body.lowercase(Locale.ROOT)
                val isReconnectMessage = lowerBody.contains("yeniden bağlanın") ||
                    lowerBody.contains("yeniden baglanin") ||
                    lowerBody.contains("reconnect")
                val isStructuredResponse = body.startsWith("{") ||
                    body.startsWith("[") ||
                    body.startsWith("#EXTM3U", true) ||
                    body.startsWith("http://", true) ||
                    body.startsWith("https://", true) ||
                    body.startsWith("\"") ||
                    (body.startsWith("<") && (
                        body.contains("<iframe", true) ||
                        body.contains("<source", true) ||
                        body.contains("<video", true) ||
                        body.contains("jwplayer", true)
                    ))
                if (isReconnectMessage || !isStructuredResponse) {
                    Log.w(name, "API kullanılabilir veri döndürmedi: " + url)
                    continue
                }
                Log.d(name, "API yanıtı alındı: " + cleanPath + " (" + body.length + " karakter)")
                return body
            }
        }

        Log.w(name, "Genel API yanıtı alınamadı: " + cleanPath)
        return null
    }

    private fun string(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", true)) return clean(value)
        }
        return ""
    }

    private fun int(item: JSONObject, fallback: Int, vararg keys: String): Int {
        for (key in keys) {
            val value = item.optInt(key, Int.MIN_VALUE)
            if (value != Int.MIN_VALUE && value >= 0) return value
        }
        return fallback
    }

    private fun objectsFromJson(raw: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val visited = HashSet<String>()

        fun addObject(obj: JSONObject) {
            val title = getName(obj)
            val id = getId(obj)
            val link = getStreamUrl(obj)
            if (title == "İsimsiz içerik" || (id.isBlank() && link.isBlank())) return
            val key = id + "|" + title + "|" + link
            if (visited.add(key)) result.add(obj)
        }

        fun walk(value: Any?, depth: Int) {
            if (depth > 5 || value == null || value == JSONObject.NULL) return
            when (value) {
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                }
                is JSONObject -> {
                    addObject(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is JSONArray || child is JSONObject) walk(child, depth + 1)
                    }
                }
            }
        }

        val body = raw.trim()
        runCatching {
            when {
                body.startsWith("[") -> walk(JSONArray(body), 0)
                body.startsWith("{") -> walk(JSONObject(body), 0)
            }
        }
        return result
    }

    private fun parseM3u(raw: String): List<JSONObject> {
        if (!raw.contains("#EXTINF", true)) return emptyList()
        val result = mutableListOf<JSONObject>()
        var currentInfo = ""

        for (lineValue in raw.lines()) {
            val line = lineValue.trim()
            if (line.startsWith("#EXTINF", true)) {
                currentInfo = line
                continue
            }
            if (line.isBlank() || line.startsWith("#") || currentInfo.isBlank()) continue

            val title = currentInfo.substringAfterLast(',', "").trim()
                .ifBlank { "Canlı yayın " + (result.size + 1) }
            val image = Regex("""(?i)tvg-logo=["']([^"']+)["']""")
                .find(currentInfo)?.groupValues?.getOrNull(1).orEmpty()
            val group = Regex("""(?i)group-title=["']([^"']+)["']""")
                .find(currentInfo)?.groupValues?.getOrNull(1).orEmpty()

            result.add(JSONObject().apply {
                put("ID", "iptv_" + result.size)
                put("Name", title)
                put("Image", clean(image))
                put("Link", clean(line))
                put("Type", "IPTV")
                put("Group", group)
            })
            currentInfo = ""
        }
        return result
    }

    private fun parseItems(raw: String?): List<JSONObject> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()
        val m3u = parseM3u(body)
        if (m3u.isNotEmpty()) return m3u
        return objectsFromJson(body)
    }

    private fun getName(item: JSONObject): String =
        string(item, "Name", "name", "Title", "title", "ChannelName", "channelName", "RadioName", "radioName")
            .ifBlank { "İsimsiz içerik" }

    private fun getId(item: JSONObject): String =
        string(
            item, "ID", "Id", "id", "movie_id", "movieId", "m_id",
            "episode_id", "episodeId", "series_id", "seriesId",
            "channel_id", "channelId", "radio_id", "radioId"
        )

    private fun getPoster(item: JSONObject): String =
        string(item, "Image", "image", "poster", "Poster", "Backdrop", "backdrop", "Logo", "logo", "tvg-logo")

    private fun getSummary(item: JSONObject): String =
        string(item, "Summary", "summary", "Description", "description", "plot")

    private fun getType(item: JSONObject, fallback: String = ""): String =
        string(item, "Type", "type", "p_type", "MediaType", "mediaType", "category")
            .ifBlank { fallback }
            .lowercase(Locale.ROOT)

    private fun isLiveKind(kind: String): Boolean =
        kind.contains("iptv") || kind.contains("live") ||
            kind.contains("channel") || kind.contains("radio") ||
            kind.contains("radyo") || kind.contains("audio")

    private fun getStreamUrl(item: JSONObject): String =
        string(
            item,
            "Link", "link", "url", "Url", "URL",
            "stream", "Stream", "stream_url", "streamUrl",
            "file", "File", "src", "Src", "source", "Source",
            "play_url", "playUrl", "video_url", "videoUrl",
            "m3u", "M3U", "playlist", "Playlist"
        )

    private fun asPayload(item: JSONObject, kind: String): String =
        JSONObject(item.toString()).apply { put("__seyir_kind", kind) }.toString()

    private fun searchResponse(item: JSONObject, kindHint: String = ""): SearchResponse? {
        val title = getName(item)
        val kind = getType(item, kindHint)
        val poster = getPoster(item).takeIf { it.isNotBlank() }
        val payload = asPayload(item, kind)

        return when {
            isLiveKind(kind) -> newLiveSearchResponse(title, payload, TvType.Live) {
                posterUrl = poster
            }
            kind.contains("tv") || kind.contains("series") ||
                kind.contains("dizi") -> newTvSeriesSearchResponse(title, payload, TvType.TvSeries) {
                    posterUrl = poster
                }
            else -> newMovieSearchResponse(title, payload, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private fun parseHomeRail(raw: String?, rail: String): List<JSONObject> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return parseItems(body)
        val keys = when (rail.lowercase(Locale.ROOT)) {
            "first" -> listOf("first", "first_alt")
            "second" -> listOf("second")
            "third" -> listOf("third")
            else -> emptyList()
        }
        if (keys.isEmpty()) return parseItems(body)

        val merged = JSONArray()
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) merged.put(array.opt(index))
        }
        return objectsFromJson(merged.toString())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val endpoint = request.data.substringBefore('#')
        val rail = request.data.substringAfter('#', "")
        val raw = apiGet(endpoint)
        val items = if (rail.isNotBlank()) parseHomeRail(raw, rail) else parseItems(raw)
        val kindHint = if (
            request.data.contains("iptv", true) || request.data.contains("radyo", true)
        ) "iptv" else ""
        val results = items.mapNotNull { searchResponse(it, kindHint) }
        Log.i(name, "Ana sayfa bölümü: " + request.name + ", kayıt: " + results.size)
        return newHomePageResponse(request.name, results, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encodedQuery = encoded(q)
        val results = linkedMapOf<String, SearchResponse>()
        val requests = listOf(
            Pair("back/search.php?name=" + encodedQuery, ""),
            Pair("back/search.php?p_type=TV&name=" + encodedQuery, "tv")
        )

        for ((path, hint) in requests) {
            for (item in parseItems(apiGet(path))) {
                val response = searchResponse(item, hint) ?: continue
                results.putIfAbsent(response.url, response)
            }
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val original = runCatching { JSONObject(url) }.getOrNull() ?: return null
        val kind = string(original, "__seyir_kind").ifBlank { getType(original) }
        val id = getId(original)
        val title = getName(original)
        val poster = getPoster(original).takeIf { it.isNotBlank() }
        val summary = getSummary(original).takeIf { it.isNotBlank() }
        val year = string(original, "ReleaseDate", "releaseDate", "Year", "year")
            .take(4).toIntOrNull()

        if (isLiveKind(kind)) {
            val payload = asPayload(original, "iptv")
            return newLiveStreamLoadResponse(title, payload, payload) {
                posterUrl = poster
            }
        }
        if (id.isBlank()) return null

        val detailRaw = apiGet("back/show.php?type=single&id=" + encoded(id))
        val detail = parseItems(detailRaw).firstOrNull()
            ?: runCatching { detailRaw?.let { JSONObject(it) } }.getOrNull()
            ?: original

        val resolvedTitle = getName(detail).ifBlank { title }
        val resolvedPoster = getPoster(detail).ifBlank { poster.orEmpty() }.takeIf { it.isNotBlank() }
        val resolvedSummary = getSummary(detail).ifBlank { summary.orEmpty() }.takeIf { it.isNotBlank() }
        val resolvedYear = string(detail, "ReleaseDate", "releaseDate", "Year", "year")
            .take(4).toIntOrNull() ?: year

        if (kind.contains("tv") || kind.contains("series") || kind.contains("dizi")) {
            val episodeItems = parseItems(apiGet("back/episodes.php?id=" + encoded(id)))
            val episodes = episodeItems.mapIndexed { index, episodeItem ->
                val episodeId = getId(episodeItem)
                val episodeData = JSONObject(episodeItem.toString()).apply {
                    put("__seyir_kind", "episode")
                    put("__seyir_parent_id", id)
                    put("__seyir_episode_id", episodeId)
                }.toString()

                newEpisode(episodeData) {
                    name = getName(episodeItem).ifBlank { "Bölüm " + (index + 1) }
                    posterUrl = getPoster(episodeItem).ifBlank { resolvedPoster.orEmpty() }.takeIf { it.isNotBlank() }
                    season = int(episodeItem, 1, "Season", "season", "SeasonNumber", "seasonNumber")
                    episode = int(episodeItem, index + 1, "Episode", "episode", "EpisodeNumber", "episodeNumber")
                }
            }

            return newTvSeriesLoadResponse(
                resolvedTitle,
                asPayload(detail, "tv"),
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = resolvedPoster
                plot = resolvedSummary
                this.year = resolvedYear
            }
        }

        val payload = asPayload(detail, "movie")
        return newMovieLoadResponse(
            resolvedTitle,
            payload,
            TvType.Movie,
            payload
        ) {
            posterUrl = resolvedPoster
            plot = resolvedSummary
            this.year = resolvedYear
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") ||
            value.contains(".mpd") || value.contains(".mp3") ||
            value.contains(".m4a") || value.contains(".aac") ||
            value.contains(".ogg") || value.contains(".opus") ||
            value.contains(".wav") || value.contains(".flac") ||
            value.contains(".ts") || value.contains("/hls/") ||
            value.contains("/manifest") || value.contains("/live/") ||
            value.contains("/movie/") || value.contains("/series/") ||
            value.contains("output=mpegts") || value.contains("type=m3u")
    }

    private fun parseStreamItems(raw: String?): List<JSONObject> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()

        val result = mutableListOf<JSONObject>()
        val seen = HashSet<String>()

        fun addLink(linkValue: String, source: JSONObject? = null) {
            val link = clean(linkValue).trim('"', '\'', ' ')
            if (!link.startsWith("http", true) || !seen.add(link)) return
            val item = source?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (getStreamUrl(item).isBlank()) item.put("Link", link)
            result.add(item)
        }

        fun walk(value: Any?, depth: Int) {
            if (depth > 7 || value == null || value == JSONObject.NULL) return
            when (value) {
                is String -> {
                    val text = value.trim()
                    if (text.startsWith("http", true)) addLink(text)
                    else if (text.startsWith("#EXTM3U", true)) {
                        parseM3u(text).forEach { addLink(getStreamUrl(it), it) }
                    } else {
                        // Some API responses put stream URLs inside escaped JSON strings.
                        val nested = runCatching { JSONTokener(text).nextValue() }.getOrNull()
                        if (nested is JSONObject || nested is JSONArray) walk(nested, depth + 1)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                }
                is JSONObject -> {
                    val link = getStreamUrl(value)
                    if (link.isNotBlank()) addLink(link, value)
                    val streamKeys = setOf(
                        "link", "url", "stream", "stream_url", "streamurl",
                        "file", "src", "source", "play_url", "playurl",
                        "video_url", "videourl", "m3u", "playlist"
                    )
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        when (child) {
                            is JSONArray, is JSONObject -> walk(child, depth + 1)
                            is String -> {
                                if (key.lowercase(Locale.ROOT) in streamKeys) {
                                    addLink(child, value)
                                } else if (child.trim().startsWith("{") ||
                                    child.trim().startsWith("[")
                                ) {
                                    walk(child, depth + 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        when {
            body.startsWith("#EXTM3U", true) -> {
                parseM3u(body).forEach { addLink(getStreamUrl(it), it) }
            }
            body.startsWith("http://", true) || body.startsWith("https://", true) -> {
                addLink(body)
            }
            else -> {
                runCatching {
                    val parsed = JSONTokener(body).nextValue()
                    walk(parsed, 0)
                }
                // A few provider pages return HTML containing a direct media URL.
                if (result.isEmpty() && body.startsWith("<")) {
                    val urlPattern = Regex(
                        """(?i)(?:file|src|href|url)\s*[:=]\s*["']?(https?://[^"'\s<>,]+)"""
                    )
                    urlPattern.findAll(body).forEach { match ->
                        addLink(match.groupValues[1])
                    }
                }
            }
        }
        return result
    }

    /**
     * FourPichive uses the public ContentX flow:
     * iframe.php -> window.openPlayer(id) -> source2.php?v=id -> HLS file.
     * Some API records contain ?v=ID?l=0; try the normalized ?v=ID&l=0 URL first.
     */
    private suspend fun resolvePichive(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT).orEmpty() }
            .getOrDefault("")
        if (host != "four.pichive.online" && host != "pichive.online") return false

        val candidates = mutableListOf<String>()
        val firstQuery = url.indexOf('?')
        if (firstQuery >= 0) {
            val secondQuery = url.indexOf('?', firstQuery + 1)
            if (secondQuery >= 0) {
                candidates.add(url.substring(0, secondQuery) + "&" + url.substring(secondQuery + 1))
            }
        }
        candidates.add(url)

        val extReferer = referer.ifBlank { mainUrl.trimEnd('/') + "/" }
        val pageHeaders = requestHeaders + ("Referer" to extReferer)

        for (candidate in candidates.distinct()) {
            val pageResponse = runCatching {
                app.get(candidate, headers = pageHeaders, timeout = 12000, allowRedirects = true)
            }.getOrNull() ?: continue
            val page = pageResponse.text
            if (page.isBlank()) continue

            val playerId = Regex(
                """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]"""
            ).find(page)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

            if (playerId.isNullOrBlank()) {
                Log.w(name, "Pichive oynatıcı kimliği bulunamadı; HTTP " + pageResponse.code)
                continue
            }

            val subtitlePattern = Regex(
                """["']file["']\s*:\s*["']([^"']+)["']\s*,\s*["']label["']\s*:\s*["']([^"']+)["']"""
            )
            for (match in subtitlePattern.findAll(page)) {
                val subtitleUrl = clean(match.groupValues[1]).replace("\\", "")
                val subtitleLang = clean(match.groupValues[2])
                    .replace("\\u0131", "ı")
                    .replace("\\u0130", "İ")
                    .replace("\\u00fc", "ü")
                    .replace("\\u00e7", "ç")
                if (subtitleUrl.startsWith("http", true)) {
                    runCatching {
                        subtitleCallback(newSubtitleFile(subtitleLang, subtitleUrl))
                    }
                }
            }

            val baseUrl = runCatching {
                val uri = URI(candidate)
                uri.scheme + "://" + uri.host
            }.getOrNull() ?: continue

            val sourceResponse = runCatching {
                app.get(
                    baseUrl + "/source2.php?v=" + encoded(playerId),
                    headers = pageHeaders,
                    timeout = 12000,
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            val rawMediaUrl = Regex(
                """["']file["']\s*:\s*["']([^"']+)["']"""
            ).find(sourceResponse.text)?.groupValues?.getOrNull(1).orEmpty()

            val extracted = clean(rawMediaUrl).replace("\\", "")
            val mediaUrl = when {
                extracted.startsWith("http://", true) || extracted.startsWith("https://", true) ->
                    extracted
                extracted.startsWith("//") -> "https:$extracted"
                extracted.isNotBlank() -> runCatching {
                    URI(baseUrl + "/").resolve(extracted).toString()
                }.getOrDefault("")
                else -> ""
            }

            if (!mediaUrl.startsWith("http", true)) {
                Log.w(name, "Pichive source2 yanıtında geçerli video adresi bulunamadı")
                continue
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "SeyirTURK FourPichive",
                    url = mediaUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = candidate
                    headers = requestHeaders + ("Referer" to candidate)
                }
            )
            Log.i(name, "FourPichive bağlantısı doğrudan çözüldü")
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
        val item = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val kind = string(item, "__seyir_kind").ifBlank { getType(item) }
        val episodeId = string(item, "__seyir_episode_id")
        val parentId = string(item, "__seyir_parent_id")
        val id = if (episodeId.isNotBlank()) parentId else getId(item)

        val streams = mutableListOf<JSONObject>()
        val live = isLiveKind(kind)

        // IPTV and radio list entries commonly already carry their playable URL.
        // Use that URL first; the film/episode streams endpoint is only a fallback.
        val directItemUrl = getStreamUrl(item)
        if (directItemUrl.startsWith("http", true)) {
            streams.add(JSONObject(item.toString()).apply {
                if (getStreamUrl(this).isBlank()) put("Link", directItemUrl)
            })
        }

        if (streams.isEmpty() && id.isNotBlank()) {
            val path = buildString {
                append("back/streams.php?id=").append(encoded(id))
                if (episodeId.isNotBlank()) {
                    append("&isTv=1&e=").append(encoded(episodeId))
                }
            }
            streams.addAll(parseStreamItems(apiGet(path)))
        }

        var found = false
        val seen = HashSet<String>()

        for (stream in streams) {
            val link = getStreamUrl(stream)
            if (!link.startsWith("http", true) || !seen.add(link)) continue

            val label = string(stream, "Provider", "provider", "Name", "name", "Title", "title")
                .ifBlank { if (live) "Canlı yayın" else "SeyirTURK" }
            val subtitle = string(stream, "Subtitle", "subtitle", "Subtitles", "subtitles")
            if (subtitle.startsWith("http", true)) {
                runCatching { subtitleCallback(newSubtitleFile("Türkçe", subtitle)) }
            }

            val streamReferer = string(stream, "Referer", "referer", "referrer")
                .ifBlank { "https://seyirturk.net/" }

            // Prefer a local ContentX-compatible resolver for Pichive embeds.
            val pichiveResolved = runCatching {
                resolvePichive(link, streamReferer, subtitleCallback, callback)
            }.onFailure {
                Log.w(name, "Pichive çözümleme hatası: " + (it.message ?: it.javaClass.simpleName))
            }.getOrDefault(false)
            if (pichiveResolved) {
                found = true
                continue
            }

            if (isDirectMedia(link) || live) {
                val linkType = when {
                    link.contains(".m3u8", true) || link.contains("/hls/", true) ->
                        ExtractorLinkType.M3U8
                    link.contains(".mpd", true) || link.contains("/manifest", true) ->
                        ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = link,
                        type = linkType
                    ) {
                        referer = string(stream, "Referer", "referer", "referrer")
                            .ifBlank { "https://seyirturk.net/" }
                        headers = requestHeaders + mapOf(
                            "Referer" to string(stream, "Referer", "referer", "referrer")
                                .ifBlank { "https://seyirturk.net/" }
                        )
                    }
                )
                found = true
            } else {
                val extracted = runCatching {
                    loadExtractor(
                        link,
                        string(stream, "Referer", "referer", "referrer")
                            .ifBlank { "https://seyirturk.net/" },
                        subtitleCallback
                    ) {
                        callback.invoke(it)
                    }
                }.getOrDefault(false)
                found = found || extracted
            }
        }

        Log.i(name, "Yayın bağlantısı sonucu: " + found + ", kaynak: " + streams.size + ", tip: " + kind)
        return found
    }
}
