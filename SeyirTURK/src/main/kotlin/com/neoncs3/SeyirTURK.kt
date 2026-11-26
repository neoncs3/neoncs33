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
 * SeyirTURK integration limited to its public IPTV and radio catalogues.
 * Film/series catalogue and protected/account-only playback flows are not used.
 */
class SeyirTURK : MainAPI() {
    override var mainUrl = "https://seyirturk.net"
    override var name = "SeyirTURK"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Live)

    override val mainPage: List<MainPageData> = listOf(
        MainPageData("IPTV", "iptv"),
        MainPageData("Radyo", "radio")
    )

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

    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun clean(value: String?): String = value.orEmpty()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .trim()

    private fun decodeRootCheck(raw: String): String? {
        val firstLine = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isBlank()) return null
        return runCatching {
            String(Base64.decode(firstLine.reversed(), Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun decodeApiResponse(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        if (
            value.startsWith("{") || value.startsWith("[") ||
            value.startsWith("#EXTM3U", true) ||
            value.startsWith("http://", true) ||
            value.startsWith("https://", true) ||
            value.startsWith("<")
        ) return value

        val compact = value.replace(Regex("\\s+"), "")
        if (compact.length < 8 || !Regex("^[A-Za-z0-9+/=_-]+$").matches(compact)) {
            return value
        }

        return runCatching {
            val decoded = String(
                Base64.decode(compact.reversed(), Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()
            val readable =
                decoded.startsWith("{") || decoded.startsWith("[") ||
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

    private suspend fun apiRoots(): List<String> {
        cachedRoots?.let { return it }
        val discovered = mutableListOf<String>()
        val checks = listOf(
            "http://seyirturk.net/rootcheck/android.php",
            "https://seyirturk.net/rootcheck/android.php"
        )

        for (checkUrl in checks) {
            val body = runCatching {
                app.get(checkUrl, headers = requestHeaders, timeout = 8000).text.trim()
            }.getOrDefault("")
            if (body.isBlank()) continue

            decodeRootCheck(body)?.let(::normalizeRoot)?.let(discovered::add)
            val candidates = listOf(body, decodeApiResponse(body)).distinct()
            for (candidateBody in candidates) {
                val jsonRoot = runCatching {
                    val json = JSONObject(candidateBody)
                    sequenceOf("Root", "root", "url", "Url", "baseUrl", "BaseUrl", "server")
                        .mapNotNull { key ->
                            json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                        }
                        .firstOrNull()
                }.getOrNull()

                jsonRoot?.let { normalizeRoot(it) }?.let(discovered::add)
                Regex("""(?i)https?://[A-Za-z0-9.-]+(?::\d+)?(?:/[^\s"'<>]*)?""")
                    .findAll(candidateBody)
                    .mapNotNull { normalizeRoot(it.value) }
                    .forEach(discovered::add)
                normalizeRoot(candidateBody)?.let(discovered::add)
            }
        }

        val roots = (discovered + listOf("https://habba.cloud/"))
            .mapNotNull(::normalizeRoot)
            .distinct()
        cachedRoots = roots
        Log.i(name, "API sunucuları: " + roots.joinToString(","))
        return roots
    }

    /**
     * The supplied Android APK appends these guest request parameters in
     * GetsPack.GetMovies.getUrlContent(). Existing endpoint query values are
     * retained, including the IPTV endpoint's own u_id parameter.
     */
    private fun addGuestParameters(path: String): String {
        val base = path.trim().trimEnd('?', '&')
        val separator = if (base.contains('?')) "&" else "?"
        return base + separator + "u_id=0&vidid=&cmail=&vn=1005"
    }

    private suspend fun apiGet(path: String): String? {
        val cleanPath = path.trimStart('/')
        val requestPath = addGuestParameters(cleanPath)

        for (root in apiRoots()) {
            val rootBase = root.trimEnd('/')
            val apiBase = if (rootBase.endsWith("/sey", true)) rootBase else rootBase + "/sey"
            val urls = listOf(
                apiBase + "/" + requestPath,
                rootBase + "/" + requestPath
            ).distinct()

            for (url in urls) {
                val response = runCatching {
                    app.get(
                        url,
                        headers = requestHeaders + ("Referer" to (rootBase + "/")),
                        timeout = 15000,
                        allowRedirects = true
                    )
                }.getOrNull() ?: continue

                if (!response.isSuccessful) {
                    Log.w(name, "API HTTP " + response.code + ": " + cleanPath)
                    continue
                }

                val raw = response.text
                if (raw.isBlank()) continue
                val body = decodeApiResponse(raw)
                val lower = body.lowercase(Locale.ROOT)
                if (
                    lower.contains("yeniden bağlanın") ||
                    lower.contains("yeniden baglanin") ||
                    lower.contains("reconnect")
                ) {
                    Log.w(name, "API yeniden bağlanma yanıtı verdi: " + cleanPath)
                    continue
                }

                val structured =
                    body.startsWith("{") || body.startsWith("[") ||
                    body.startsWith("#EXTM3U", true) ||
                    body.startsWith("http://", true) ||
                    body.startsWith("https://", true) ||
                    body.startsWith("\"")
                if (!structured) {
                    Log.w(name, "API beklenmeyen yanıt verdi: " + cleanPath)
                    continue
                }

                Log.d(name, "API yanıtı alındı: " + cleanPath + " (" + body.length + " karakter)")
                return body
            }
        }

        Log.w(name, "API yanıtı alınamadı: " + cleanPath)
        return null
    }

    private fun string(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = item.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", true)) return clean(value)
        }
        return ""
    }

    private fun getName(item: JSONObject): String =
        string(item, "Name", "name", "Title", "title", "ChannelName", "channelName", "RadioName", "radioName")
            .ifBlank { "İsimsiz yayın" }

    private fun getId(item: JSONObject): String =
        string(item, "ID", "Id", "id", "K_ID", "channel_id", "channelId", "radio_id", "radioId")

    private fun getPoster(item: JSONObject): String =
        string(item, "Image", "image", "Logo", "logo", "tvg-logo", "poster", "Poster", "icon")

    private fun getGroup(item: JSONObject): String =
        string(item, "Group", "group", "Category", "category", "categoryName", "groupName", "Genres", "genres")
            .ifBlank { "Gruplandırılmamış" }

    private fun getStreamUrl(item: JSONObject): String =
        string(
            item,
            "LinkTV", "linkTV", "LinkTv", "Link", "link", "url", "Url", "URL",
            "stream", "Stream", "stream_url", "streamUrl", "file", "File",
            "src", "Src", "source", "Source", "play_url", "playUrl",
            "video_url", "videoUrl", "m3u", "M3U", "playlist", "Playlist"
        )

    private fun parseM3u(raw: String): List<JSONObject> {
        if (!raw.contains("#EXTINF", true)) return emptyList()
        val result = mutableListOf<JSONObject>()
        var info = ""

        for (lineValue in raw.lines()) {
            val line = lineValue.trim()
            if (line.startsWith("#EXTINF", true)) {
                info = line
                continue
            }
            if (line.isBlank() || line.startsWith("#") || info.isBlank()) continue

            val title = info.substringAfterLast(',', "").trim()
                .ifBlank { "Canlı yayın " + (result.size + 1) }
            val logo = Regex("""(?i)tvg-logo=["']([^"']+)["']""")
                .find(info)?.groupValues?.getOrNull(1).orEmpty()
            val group = Regex("""(?i)group-title=["']([^"']+)["']""")
                .find(info)?.groupValues?.getOrNull(1).orEmpty()

            result.add(JSONObject().apply {
                put("ID", "m3u_" + result.size)
                put("Name", title)
                put("Image", clean(logo))
                put("LinkTV", clean(line))
                put("Group", group.ifBlank { "Gruplandırılmamış" })
            })
            info = ""
        }
        return result
    }

    private fun parseItems(raw: String?): List<JSONObject> {
        val body = raw?.trim().orEmpty()
        if (body.isBlank()) return emptyList()

        val m3u = parseM3u(body)
        if (m3u.isNotEmpty()) return m3u

        val result = mutableListOf<JSONObject>()
        val seen = HashSet<String>()

        fun walk(value: Any?, depth: Int) {
            if (depth > 7 || value == null || value == JSONObject.NULL) return
            when (value) {
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index), depth + 1)
                }
                is JSONObject -> {
                    val title = getName(value)
                    val id = getId(value)
                    val link = getStreamUrl(value)
                    if (title != "İsimsiz yayın" && (id.isNotBlank() || link.isNotBlank() || value.has("Group"))) {
                        val key = id + "|" + title + "|" + link + "|" + getGroup(value)
                        if (seen.add(key)) result.add(JSONObject(value.toString()))
                    }

                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is JSONArray || child is JSONObject) walk(child, depth + 1)
                    }
                }
                is String -> {
                    val nested = value.trim()
                    if (nested.startsWith("{") || nested.startsWith("[")) {
                        val parsed = runCatching { JSONTokener(nested).nextValue() }.getOrNull()
                        if (parsed is JSONArray || parsed is JSONObject) walk(parsed, depth + 1)
                    } else if (nested.startsWith("http", true)) {
                        val obj = JSONObject().put("Name", nested.substringAfterLast('/').ifBlank { "Canlı yayın" })
                            .put("LinkTV", nested)
                        val key = getId(obj) + "|" + getName(obj) + "|" + getStreamUrl(obj)
                        if (seen.add(key)) result.add(obj)
                    }
                }
            }
        }

        runCatching {
            val parsed = JSONTokener(body).nextValue()
            walk(parsed, 0)
        }.onFailure {
            Log.w(name, "JSON liste çözümlenemedi: " + (it.message ?: "bilinmeyen hata"))
        }

        return result
    }

    private fun asPayload(item: JSONObject, kind: String): String =
        JSONObject(item.toString()).put("__seyir_kind", kind).toString()

    private suspend fun loadIptvItems(): List<JSONObject> {
        // The APK loads both the general IPTV list and the user-ID variant.
        val general = parseItems(apiGet("kodi/iptv.php"))
        val userList = parseItems(apiGet("kodi/iptv.php?u_id=0"))
        val merged = linkedMapOf<String, JSONObject>()

        for (item in general + userList) {
            val key = getId(item) + "|" + getName(item) + "|" + getStreamUrl(item)
            merged.putIfAbsent(key, item)
        }

        Log.i(name, "IPTV kanal sayısı: " + merged.size)
        return merged.values.toList()
    }

    private suspend fun loadRadioItems(): List<JSONObject> {
        val items = parseItems(apiGet("kodi/radyo.php?"))
        Log.i(name, "Radyo sayısı: " + items.size)
        return items
    }

    private fun groupResponse(group: String, channels: List<JSONObject>): SearchResponse {
        val firstPoster = channels.firstNotNullOfOrNull { getPoster(it).takeIf(String::isNotBlank) }
        val payload = JSONObject()
            .put("__seyir_kind", "iptv_group")
            .put("__seyir_group", group)
            .toString()

        return newTvSeriesSearchResponse(group, payload, TvType.TvSeries) {
            posterUrl = firstPoster
        }
    }

    private fun radioResponse(item: JSONObject): SearchResponse {
        val title = getName(item)
        val poster = getPoster(item).takeIf { it.isNotBlank() }
        return newLiveSearchResponse(title, asPayload(item, "radio"), TvType.Live) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val results = when (request.data) {
            "iptv" -> {
                val channels = loadIptvItems()
                val groups = channels.groupBy { getGroup(it) }
                val names = listOf("Bütün Kanallar") + groups.keys
                    .filter { it.isNotBlank() && it != "Bütün Kanallar" }
                    .sortedBy { it.lowercase(Locale.ROOT) }

                names.map { group ->
                    val members = if (group == "Bütün Kanallar") channels else groups[group].orEmpty()
                    groupResponse(group, members)
                }
            }
            "radio" -> loadRadioItems().map(::radioResponse)
            else -> emptyList()
        }

        Log.i(name, "Ana sayfa " + request.name + ": " + results.size + " kayıt")
        return newHomePageResponse(request.name, results, false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = runCatching { JSONObject(url) }.getOrNull() ?: return null
        val kind = string(item, "__seyir_kind")
        val title = getName(item)

        if (kind == "iptv_group") {
            val group = string(item, "__seyir_group").ifBlank { title }
            val allChannels = loadIptvItems()
            val channels = if (group == "Bütün Kanallar") {
                allChannels
            } else {
                allChannels.filter { getGroup(it).equals(group, true) }
            }

            val episodes = channels.mapIndexed { index, channel ->
                newEpisode(asPayload(channel, "iptv_channel")) {
                    name = getName(channel)
                    posterUrl = getPoster(channel).takeIf { it.isNotBlank() }
                    season = 1
                    episode = index + 1
                }
            }

            return newTvSeriesLoadResponse(
                group,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = channels.firstNotNullOfOrNull { getPoster(it).takeIf(String::isNotBlank) }
            }
        }

        if (kind == "radio" || kind == "iptv_channel") {
            val payload = asPayload(item, kind)
            return newLiveStreamLoadResponse(title, payload, payload) {
                posterUrl = getPoster(item).takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun collectStreamItems(item: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = HashSet<String>()

        fun add(value: JSONObject) {
            val link = getStreamUrl(value)
            if (!link.startsWith("http", true) || !seen.add(link)) return
            result.add(JSONObject(value.toString()))
        }

        fun walk(value: Any?, depth: Int) {
            if (depth > 6 || value == null || value == JSONObject.NULL) return
            when (value) {
                is JSONObject -> {
                    if (getStreamUrl(value).startsWith("http", true)) add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is JSONObject || child is JSONArray) {
                            walk(child, depth + 1)
                        } else if (child is String &&
                            key.lowercase(Locale.ROOT) in setOf("linktv", "link", "url", "stream", "file", "src")
                        ) {
                            add(JSONObject().put("Name", key).put("LinkTV", child))
                        }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) walk(value.opt(index), depth + 1)
                }
                is String -> {
                    val nested = value.trim()
                    if (nested.startsWith("http", true)) {
                        add(JSONObject().put("LinkTV", nested))
                    } else if (nested.startsWith("{") || nested.startsWith("[")) {
                        val parsed = runCatching { JSONTokener(nested).nextValue() }.getOrNull()
                        if (parsed is JSONObject || parsed is JSONArray) walk(parsed, depth + 1)
                    }
                }
            }
        }

        add(item)
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.lowercase(Locale.ROOT) in setOf("streams", "sources", "links", "streamlist")) {
                walk(item.opt(key), 0)
            }
        }
        return result
    }

    private fun isDirectMedia(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return listOf(
            ".m3u8", ".m3u", ".mp4", ".mpd", ".ts", ".mp3",
            ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac",
            "/hls/", "/manifest", "/live/", "output=mpegts"
        ).any { value.contains(it) }
    }

    private fun linkType(url: String): ExtractorLinkType {
        val value = url.lowercase(Locale.ROOT)
        return when {
            value.contains(".m3u8") || value.contains(".m3u") ||
                value.contains("/hls/") || value.contains("output=mpegts") ->
                ExtractorLinkType.M3U8
            value.contains(".mpd") || value.contains("/manifest") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val streams = collectStreamItems(item)
        val refererDefault = "https://seyirturk.net/"
        var found = false

        for (stream in streams) {
            val link = getStreamUrl(stream)
            if (!link.startsWith("http", true)) continue

            val label = string(stream, "Provider", "provider", "Name", "name", "Title", "title")
                .ifBlank { name }
            val referer = string(stream, "Referer", "referer", "referrer")
                .ifBlank { refererDefault }
            val subtitle = string(stream, "Subtitle", "subtitle", "Subtitles", "subtitles")
            if (subtitle.startsWith("http", true)) {
                runCatching { subtitleCallback(newSubtitleFile("Türkçe", subtitle)) }
            }

            if (isDirectMedia(link)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = link,
                        type = linkType(link)
                    ) {
                        this.referer = referer
                        headers = requestHeaders + ("Referer" to referer)
                    }
                )
                found = true
            } else {
                val extracted = runCatching {
                    loadExtractor(link, referer, subtitleCallback) {
                        callback.invoke(it)
                    }
                }.getOrDefault(false)
                found = found || extracted
            }
        }

        Log.i(name, "Yayın bağlantısı: " + found + ", kaynak sayısı: " + streams.size)
        return found
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val results = linkedMapOf<String, SearchResponse>()
        val endpointItems = parseItems(apiGet("kodi/iptv/search.php?name=" + encoded(q)))
        val localItems = (loadIptvItems() + loadRadioItems()).filter {
            getName(it).contains(q, true)
        }

        for (item in endpointItems + localItems) {
            val response = radioResponse(item)
            results.putIfAbsent(response.url, response)
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
}
