package com.neoncs3

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.net.URLEncoder

/**
 * GölgeTV film/dizi catalog provider.
 *
 * Uses the app's public catalogue endpoints. Only ordinary, publicly reachable
 * HLS/MP4 streams are emitted. Official-platform login, ticket and DRM-license
 * APIs are intentionally not reproduced.
 */
class GolgeTVProvider : MainAPI() {
    override var mainUrl = "https://golgetv.com"
    override var name = "GölgeTV"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        (mainUrl + "/filmapi.php") to "Filmler",
        (mainUrl + "/diziapi.php") to "Diziler"
    )

    private enum class Kind { MOVIE, SERIES }

    private data class CatalogueItem(
        val kind: Kind,
        val title: String,
        val detailUrl: String,
        val poster: String? = null,
        val description: String? = null,
        val year: Int? = null,
        val imdb: String? = null,
        val language: String? = null
    )

    private data class EpisodeItem(val title: String, val url: String)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun headers(referer: String = mainUrl): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to referer,
        "Origin" to (runCatching { URL(referer).protocol + "://" + URL(referer).host }.getOrNull() ?: mainUrl),
        "Accept" to "application/json,text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    private fun absolute(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("javascript:", true) || value.startsWith("data:", true)) return null
        return runCatching {
            when {
                value.startsWith("https://", true) || value.startsWith("http://", true) -> URL(value).toString()
                value.startsWith("//") -> "https:" + value
                else -> URL(URL(if (base.endsWith("/")) base else "$base/"), value).toString()
            }
        }.getOrNull()
    }

    private fun JSONObject.string(vararg keys: String): String? {
        for (key in keys) {
            val value = opt(key)
            if (value == null || value == JSONObject.NULL || value is JSONObject || value is JSONArray) continue
            val text = value.toString().trim()
            if (text.isNotBlank() && text != "null" && text != "[]") return text
        }
        return null
    }

    private fun parseJson(text: String): Any? =
        runCatching { JSONTokener(text.trim()).nextValue() }.getOrNull()

    private fun collectObjects(value: Any?, out: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 9) return
        when (value) {
            is JSONObject -> {
                out.add(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectObjects(value.opt(key), out, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) collectObjects(value.opt(i), out, depth + 1)
            }
        }
    }

    private fun collectArrays(value: Any?, keyNames: Set<String>, depth: Int = 0): List<JSONArray> {
        if (depth > 9) return emptyList()
        val arrays = mutableListOf<JSONArray>()
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (key.lowercase() in keyNames && child is JSONArray) arrays.add(child)
                    arrays.addAll(collectArrays(child, keyNames, depth + 1))
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                arrays.addAll(collectArrays(value.opt(i), keyNames, depth + 1))
            }
        }
        return arrays
    }

    private fun parseCatalogue(text: String, kind: Kind, base: String): List<CatalogueItem> {
        val root = parseJson(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectObjects(root, objects)

        val items = objects.mapNotNull { obj ->
            val title = obj.string(
                if (kind == Kind.MOVIE) "filmTitle" else "diziTitle",
                "title",
                "name",
                "film_name",
                "dizi_name"
            ) ?: return@mapNotNull null

            val detail = obj.string(
                "detailUrl", "detail_url", "detail", "detailLink",
                "url", "link", "href"
            ) ?: return@mapNotNull null
            val detailUrl = absolute(detail, base) ?: return@mapNotNull null

            if (title.length > 180 || detailUrl.endsWith(".jpg", true) ||
                detailUrl.endsWith(".png", true) || detailUrl.endsWith(".webp", true)
            ) return@mapNotNull null

            val posterRaw = obj.string(
                "poster", "image", "filmImage", "film_img", "film_image",
                "diziPoster", "dizi_poster", "thumbnail", "cover", "resim"
            )
            val desc = obj.string(
                "description", "filmDescription", "film_description",
                "dizi_aciklama", "plot", "summary"
            )
            val year = obj.string("year", "filmYear", "releaseYear")?.toIntOrNull()
            val imdb = obj.string("imdb", "filmImdb", "imdbRating", "filmImdbRating")
            val language = obj.string("dil", "filmLanguage", "language")

            CatalogueItem(
                kind = kind,
                title = title,
                detailUrl = detailUrl,
                poster = absolute(posterRaw, base),
                description = desc,
                year = year,
                imdb = imdb,
                language = language
            )
        }

        return items.distinctBy { it.detailUrl }.filter {
            it.title.isNotBlank() && it.detailUrl.startsWith("http")
        }
    }

    private suspend fun catalogue(kind: Kind, query: String? = null): List<CatalogueItem> {
        val path = when {
            query != null && kind == Kind.MOVIE -> "search.php?search=" + URLEncoder.encode(query, "UTF-8")
            query != null && kind == Kind.SERIES -> "dizisearch.php?search=" + URLEncoder.encode(query, "UTF-8")
            kind == Kind.MOVIE -> "filmapi.php"
            else -> "diziapi.php"
        }
        val url = mainUrl + "/" + path
        val responseText = runCatching { app.get(url, headers = headers(url)).text }.getOrNull()
            ?: return emptyList()
        return parseCatalogue(responseText, kind, url)
    }

    private fun route(item: CatalogueItem): String {
        val json = JSONObject()
            .put("kind", if (item.kind == Kind.MOVIE) "movie" else "series")
            .put("url", item.detailUrl)
            .put("title", item.title)
            .put("poster", item.poster ?: "")
            .put("description", item.description ?: "")
            .put("year", item.year ?: JSONObject.NULL)
            .put("imdb", item.imdb ?: "")
            .put("language", item.language ?: "")
            .toString()
        val encoded = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "golgetv:" + encoded
    }

    private fun routeItem(value: String): CatalogueItem? {
        if (!value.startsWith("golgetv:")) return null
        val decoded = runCatching {
            String(
                Base64.decode(
                    value.removePrefix("golgetv:"),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val kind = when (json.optString("kind")) {
            "movie" -> Kind.MOVIE
            "series" -> Kind.SERIES
            else -> return null
        }
        val url = absolute(json.optString("url")) ?: return null
        return CatalogueItem(
            kind = kind,
            title = json.optString("title", "GölgeTV"),
            detailUrl = url,
            poster = absolute(json.optString("poster").takeIf { it.isNotBlank() }),
            description = json.optString("description").takeIf { it.isNotBlank() },
            year = json.opt("year")?.toString()?.toIntOrNull(),
            imdb = json.optString("imdb").takeIf { it.isNotBlank() },
            language = json.optString("language").takeIf { it.isNotBlank() }
        )
    }

    private fun searchResponse(item: CatalogueItem): SearchResponse {
        val data = route(item)
        return if (item.kind == Kind.MOVIE) {
            newMovieSearchResponse(item.title, data, TvType.Movie) {
                posterUrl = item.poster
                year = item.year
                plot = item.description
            }
        } else {
            newTvSeriesSearchResponse(item.title, data, TvType.TvSeries) {
                posterUrl = item.poster
                year = item.year
                plot = item.description
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val kind = if (request.data.contains("diziapi.php", true)) Kind.SERIES else Kind.MOVIE
        val items = catalogue(kind).map { searchResponse(it) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.isBlank()) return emptyList()

        val remoteMovies = catalogue(Kind.MOVIE, value)
        val remoteSeries = catalogue(Kind.SERIES, value)
        val combined = (remoteMovies + remoteSeries).distinctBy { it.detailUrl }

        if (combined.isNotEmpty()) return combined.map { searchResponse(it) }

        val allItems = catalogue(Kind.MOVIE) + catalogue(Kind.SERIES)
        return allItems.filter { it.title.contains(value, ignoreCase = true) }
            .distinctBy { it.detailUrl }
            .map { searchResponse(it) }
    }

    private fun allObjects(root: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        collectObjects(root, out)
        return out
    }

    private fun detailObject(root: Any?, keys: Set<String>): JSONObject? =
        allObjects(root).firstOrNull { obj -> keys.any { key -> obj.string(key) != null } }

    private fun episodeArray(root: Any?): JSONArray? =
        collectArrays(
            root,
            setOf("dizibolumler", "episodes", "bolumler", "episode_list", "episodelist")
        ).firstOrNull()

    override suspend fun load(url: String): LoadResponse {
        val item = routeItem(url)
            ?: CatalogueItem(Kind.MOVIE, "GölgeTV", absolute(url) ?: mainUrl)

        val responseText = runCatching {
            app.get(item.detailUrl, headers = headers(item.detailUrl)).text
        }.getOrNull().orEmpty()
        val root = parseJson(responseText)

        return if (item.kind == Kind.MOVIE) {
            val detail = detailObject(
                root,
                setOf("iframeUrl", "iframe_url", "filmName", "filmImg", "filmDescription", "videoUrl")
            ) ?: (root as? JSONObject) ?: JSONObject()

            val streamOrPage = detail.string(
                "iframeUrl", "iframe_url", "videoUrl", "video_url",
                "streamUrl", "stream_url", "playUrl", "play_url", "file", "src"
            )?.let { absolute(it, item.detailUrl) }
                ?: extractPublicMediaFromHtml(responseText, item.detailUrl).firstOrNull()
                ?: item.detailUrl

            newMovieLoadResponse(
                detail.string("filmName", "title", "name") ?: item.title,
                item.detailUrl,
                TvType.Movie,
                streamOrPage
            ) {
                posterUrl = absolute(detail.string("filmImg", "poster", "image"), item.detailUrl) ?: item.poster
                year = item.year
                plot = detail.string("filmDescription", "description", "plot") ?: item.description
            }
        } else {
            val detail = detailObject(
                root,
                setOf("diziBolumler", "diziName", "diziPoster", "diziAciklama")
            ) ?: (root as? JSONObject) ?: JSONObject()
            val episodesJson = episodeArray(root)
            val episodes = mutableListOf<EpisodeItem>()

            if (episodesJson != null) {
                for (i in 0 until episodesJson.length()) {
                    val episode = episodesJson.optJSONObject(i) ?: continue
                    val episodeUrl = episode.string(
                        "bolum", "url", "link", "iframeUrl", "iframe_url",
                        "videoUrl", "video_url", "streamUrl", "stream_url"
                    )?.let { absolute(it, item.detailUrl) } ?: continue
                    val episodeName = episode.string("isim", "title", "name", "episode", "bolumAdi")
                        ?: ("Bölüm " + (episodes.size + 1))
                    episodes.add(EpisodeItem(episodeName, episodeUrl))
                }
            }

            if (episodes.isEmpty()) {
                val fallback = detail.string(
                    "iframeUrl", "iframe_url", "videoUrl", "video_url", "streamUrl", "stream_url"
                )?.let { absolute(it, item.detailUrl) }
                if (fallback != null) episodes.add(EpisodeItem("Oynat", fallback))
            }

            val cloudEpisodes = episodes.mapIndexed { index, episode ->
                newEpisode(episode.url) {
                    name = episode.title
                    season = 1
                    episode = index + 1
                    posterUrl = absolute(detail.string("diziPoster", "poster", "image"), item.detailUrl) ?: item.poster
                }
            }

            newTvSeriesLoadResponse(
                detail.string("diziName", "title", "name") ?: item.title,
                item.detailUrl,
                TvType.TvSeries,
                cloudEpisodes
            ) {
                posterUrl = absolute(detail.string("diziPoster", "poster", "image"), item.detailUrl) ?: item.poster
                plot = detail.string("diziAciklama", "description", "plot") ?: item.description
            }
        }
    }

    private fun isOfficialAccountOrDrmUrl(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return true
        return listOf(
            "digiturkplay.com",
            "digiturk.com.tr",
            "todtv.com.tr",
            "tvplus.com.tr",
            "beinconnect.com.tr"
        ).any { host == it || host.endsWith("." + it) }
    }

    private fun isDirectMedia(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") || clean.endsWith(".m4v")
    }

    private fun extractPublicMediaFromHtml(html: String, base: String): List<String> {
        val found = linkedSetOf<String>()
        val cleaned = html.replace("\\/", "/").replace("&amp;", "&")
        val mediaRegex = Regex("""(?i)https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|m4v)(?:\?[^\s"'<>\\]*)?""")
        mediaRegex.findAll(cleaned).forEach {
            absolute(it.value, base)?.let { link -> found.add(link) }
        }
        val protocolRelative = Regex("""(?i)//[^\s"'<>\\]+?\.(?:m3u8|mp4|m4v)(?:\?[^\s"'<>\\]*)?""")
        protocolRelative.findAll(cleaned).forEach {
            absolute(it.value, base)?.let { link -> found.add(link) }
        }
        return found.toList()
    }

    private fun pageCandidates(html: String, pageUrl: String): List<String> {
        val found = linkedSetOf<String>()
        found.addAll(extractPublicMediaFromHtml(html, pageUrl))
        val document = runCatching { org.jsoup.Jsoup.parse(html, pageUrl) }.getOrNull()
            ?: return found.toList()
        document.select("video[src], source[src], iframe[src]").forEach { element ->
            absolute(element.attr("src"), pageUrl)?.let { found.add(it) }
        }
        document.select("a[href]").forEach { element ->
            val href = absolute(element.attr("href"), pageUrl) ?: return@forEach
            if (isDirectMedia(href)) found.add(href)
        }
        return found.toList()
    }

    private suspend fun emitPublicLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isDirectMedia(url) || isOfficialAccountOrDrmUrl(url)) return false
        val isHls = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", true)

        if (isHls) {
            val playlist = runCatching { app.get(url, headers = headers(referer)).text }.getOrNull()
            if (playlist != null && Regex("""(?i)METHOD\s*=\s*SAMPLE-AES|skd://|widevine|license_url|drmLicense""").containsMatchIn(playlist)) {
                return false
            }
        }

        callback(
            newExtractorLink(
                source = name,
                name = if (isHls) "GölgeTV HLS" else "GölgeTV Video",
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                quality = Qualities.Unknown.value
                this.headers = headers(referer)
            }
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val start = absolute(data, mainUrl) ?: return false
        if (isOfficialAccountOrDrmUrl(start)) return false

        var emitted = false
        val visited = linkedSetOf<String>()

        suspend fun walk(url: String, referer: String, depth: Int) {
            if (depth > 2 || url in visited || isOfficialAccountOrDrmUrl(url)) return
            visited.add(url)

            if (emitPublicLink(url, referer, callback)) {
                emitted = true
                return
            }

            val response = runCatching { app.get(url, headers = headers(referer)) }.getOrNull()
                ?: return
            val body = response.text

            val root = parseJson(body)
            val candidates = mutableListOf<String>()
            if (root != null) {
                allObjects(root).forEach { obj ->
                    obj.string(
                        "iframeUrl", "iframe_url", "videoUrl", "video_url",
                        "streamUrl", "stream_url", "playUrl", "play_url", "m3u8", "file", "src"
                    )?.let { raw -> absolute(raw, url)?.let { candidates.add(it) } }
                }
            }
            candidates.addAll(pageCandidates(body, url))

            for (candidate in candidates.distinct()) {
                if (candidate == url || isOfficialAccountOrDrmUrl(candidate)) continue
                if (emitPublicLink(candidate, url, callback)) {
                    emitted = true
                } else if (!isDirectMedia(candidate) && depth < 2) {
                    walk(candidate, url, depth + 1)
                }
            }
        }

        walk(start, mainUrl, 0)
        return emitted
    }
}
