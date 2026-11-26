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
        val separator = if (value.endsWith(".php", true)) "?" else "&"
        return value + separator + "u_id=0&cmail=&vn=1005"
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
                    body.startsWith("https://", true)
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
            val title = string(obj, "Name", "name", "title", "Title")
            val id = string(obj, "ID", "id", "movie_id", "episode_id", "channel_id")
            val link = string(obj, "Link", "link", "url", "Url")
            if (title.isBlank() || (id.isBlank() && link.isBlank())) return
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
        string(item, "Name", "name", "Title", "title").ifBlank { "İsimsiz içerik" }

    private fun getId(item: JSONObject): String =
        string(item, "ID", "id", "movie_id", "channel_id")

    private fun getPoster(item: JSONObject): String =
        string(item, "Image", "image", "poster", "Poster", "Backdrop", "backdrop")

    private fun getSummary(item: JSONObject): String =
        string(item, "Summary", "summary", "Description", "description", "plot")

    private fun getType(item: JSONObject, fallback: String = ""): String =
        string(item, "Type", "type", "p_type").ifBlank { fallback }.lowercase(Locale.ROOT)

    private fun asPayload(item: JSONObject, kind: String): String =
        JSONObject(item.toString()).apply { put("__seyir_kind", kind) }.toString()

    private fun searchResponse(item: JSONObject, kindHint: String = ""): SearchResponse? {
        val title = getName(item)
        val kind = getType(item, kindHint)
        val poster = getPoster(item).takeIf { it.isNotBlank() }
        val payload = asPayload(item, kind)

        return when {
            kind.contains("iptv") || kind.contains("live") ||
                kind.contains("channel") -> newLiveSearchResponse(title, payload, TvType.Live) {
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

        if (kind.contains("iptv") || kind.contains("live") || kind.contains("channel")) {
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
            value.contains(".aac") || value.contains(".ts") ||
            value.contains("/hls/") || value.contains("/manifest")
    }

    private fun parseStreamItems(raw: String?): List<JSONObject> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()

        val result = mutableListOf<JSONObject>()
        val seen = HashSet<String>()

        fun addStream(item: JSONObject) {
            val link = string(item, "Link", "link", "url", "Url")
            if (!link.startsWith("http", true)) return
            if (seen.add(link)) result.add(item)
        }

        fun walk(value: Any?, depth: Int) {
            if (depth > 5 || value == null || value == JSONObject.NULL) return
            when (value) {
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                }
                is JSONObject -> {
                    addStream(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is JSONArray || child is JSONObject) walk(child, depth + 1)
                    }
                }
            }
        }

        runCatching {
            when {
                body.startsWith("[") -> walk(JSONArray(body), 0)
                body.startsWith("{") -> walk(JSONObject(body), 0)
            }
        }
        return result
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
        if (id.isNotBlank()) {
            val path = buildString {
                append("back/streams.php?id=").append(encoded(id))
                if (episodeId.isNotBlank()) {
                    append("&isTv=1&e=").append(encoded(episodeId))
                }
            }
            streams.addAll(parseStreamItems(apiGet(path)))
        }
        if (streams.isEmpty() && string(item, "Link", "link").isNotBlank()) {
            streams.add(item)
        }

        var found = false
        val seen = HashSet<String>()

        for (stream in streams) {
            val link = string(stream, "Link", "link", "url", "Url")
            if (!link.startsWith("http", true) || !seen.add(link)) continue

            val label = string(stream, "Provider", "provider", "Name", "name")
                .ifBlank { "SeyirTURK" }
            val subtitle = string(stream, "Subtitle", "subtitle", "Subtitles", "subtitles")
            if (subtitle.startsWith("http", true)) {
                runCatching { subtitleCallback(newSubtitleFile("Türkçe", subtitle)) }
            }

            if (isDirectMedia(link)) {
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
                        referer = "https://seyirturk.net/"
                        headers = requestHeaders
                    }
                )
                found = true
            } else {
                val extracted = runCatching {
                    loadExtractor(link, "https://seyirturk.net/", subtitleCallback) {
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
