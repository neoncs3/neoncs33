package com.neoncs3

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

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

    // Use path-based routes rather than URL fragments: fragments can be stripped by clients,
    // which sent collection cards to the website root instead of opening the channel list.
    private val liveCollectionData = mainUrl + "/__neonhub/live-channels"
    private val sportCollectionData = mainUrl + "/__neonhub/sport-channels"
    private val legacyLiveCollectionData = mainUrl + "/#neon-live"
    private val legacySportCollectionData = mainUrl + "/#neon-sport"
    private val liveEpisodePrefix = "golgetv-live:"
    private val liveChannelPrefix = "golgetv-channel:"
    private val liveChannelFeed = "https://imgcom.site/panel/rec/recjson.php"
    private val fallbackPoster = "https://raw.githubusercontent.com/neoncs3/neoncs33/master/1792415151509.jpg"

    private fun routeWithoutFragment(value: String): String =
        value.substringBefore('#').trimEnd('/')

    private fun isLiveCollectionRoute(value: String): Boolean {
        if (value == legacySportCollectionData) return false
        return value == legacyLiveCollectionData ||
            routeWithoutFragment(value) == routeWithoutFragment(liveCollectionData) ||
            routeWithoutFragment(value) == mainUrl.trimEnd('/')
    }

    private fun isSportCollectionRoute(value: String): Boolean =
        value == legacySportCollectionData ||
            routeWithoutFragment(value) == routeWithoutFragment(sportCollectionData)

    private suspend fun requestText(
        url: String,
        referer: String = url,
        timeoutMs: Long = 2200L
    ): String? = withTimeoutOrNull(timeoutMs) {
        try {
            app.get(url, headers = headers(referer)).text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        (mainUrl + "/filmapi.php") to "Filmler",
        (mainUrl + "/diziapi.php") to "Diziler",
        liveCollectionData to "Canlı Kanallar",
        sportCollectionData to "Spor"
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

    private data class LiveChannel(
        val title: String,
        val urls: List<String>,
        val poster: String?,
        val category: String,
        val type: String
    )

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
                else -> URL(URL(base), value).toString()
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

    private fun parseJson(text: String): Any? {
        // Some endpoints prepend a UTF-8 BOM or an anti-XSSI marker before JSON.
        val cleaned = text.trim()
            .removePrefix("\uFEFF")
            .replace(Regex("""^\)\]\}',?\s*"""), "")
            .trim()
        val first = runCatching { JSONTokener(cleaned).nextValue() }.getOrNull()
        if (first is String) {
            val nested = first.trim().removePrefix("\uFEFF")
            if (nested.startsWith("{") || nested.startsWith("[")) {
                return runCatching { JSONTokener(nested).nextValue() }.getOrNull() ?: first
            }
        }
        return first
    }

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
            is String -> {
                // Some versions encode the channel array as a JSON string inside a config object.
                val nestedText = value.trim()
                if (nestedText.startsWith("{") || nestedText.startsWith("[")) {
                    val nested = parseJson(nestedText)
                    if (nested != null && nested !is String) collectObjects(nested, out, depth + 1)
                }
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
                if (kind == Kind.MOVIE) "filmName" else "diziName",
                if (kind == Kind.MOVIE) "film_name" else "dizi_name",
                "title",
                "name",
                "film_title",
                "dizi_title",
                "isim",
                "film",
                "dizi",
                "baslik",
                "başlık"
            ) ?: return@mapNotNull null

            val detail = obj.string(
                "detailUrl", "detail_url", "detail", "detailLink",
                if (kind == Kind.MOVIE) "filmUrl" else "diziUrl",
                if (kind == Kind.MOVIE) "film_url" else "dizi_url",
                if (kind == Kind.MOVIE) "filmLink" else "diziLink",
                if (kind == Kind.MOVIE) "film_link" else "dizi_link",
                "pageUrl", "page_url", "detailPage", "detail_page", "permalink",
                "url", "link", "href"
            ) ?: return@mapNotNull null
            val detailUrl = absolute(detail, base) ?: return@mapNotNull null

            if (title.length > 180 || detailUrl.endsWith(".jpg", true) ||
                detailUrl.endsWith(".png", true) || detailUrl.endsWith(".webp", true)
            ) return@mapNotNull null

            val posterRaw = obj.string(
                "poster", "image", "imageUrl", "image_url", "posterUrl", "poster_url",
                "filmImg", "filmImage", "film_img", "film_image", "filmPoster", "film_poster",
                "diziImg", "diziPoster", "dizi_poster", "thumbnail", "thumbnailUrl",
                "thumbnail_url", "thumb_square", "thumbSquare", "cover", "coverUrl",
                "cover_url", "resim", "resimUrl", "image_path", "logo", "icon"
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
        val responseText = requestText(url, url, 4200L).orEmpty()
        var items = parseCatalogue(responseText, kind, url)
        var usedHtmlFallback = false

        // Some API revisions return HTML or alter field names. Try cards in the API
        // response, then the public homepage, rather than silently rendering an empty row.
        if (items.isEmpty()) {
            items = parseCatalogueHtml(responseText, kind, url)
            usedHtmlFallback = items.isNotEmpty()
        }
        if (items.isEmpty()) {
            val homepage = requestText(mainUrl, mainUrl, 1300L).orEmpty()
            items = parseCatalogueHtml(homepage, kind, mainUrl)
            usedHtmlFallback = items.isNotEmpty()
        }
        if (items.isEmpty()) return emptyList()

        // HTML cards already include their own poster markup. For JSON-only entries,
        // resolve a few missing covers concurrently within a strict budget: sequential
        // page fetches exceeded the hub's 6.5-second provider timeout and erased rows.
        if (usedHtmlFallback) return items
        val needsPoster = items.filter { it.poster.isNullOrBlank() }.take(4)
        if (needsPoster.isEmpty()) return items

        val resolved = withTimeoutOrNull(1800L) {
            supervisorScope {
                needsPoster.map { item ->
                    async {
                        val html = requestText(item.detailUrl, item.detailUrl, 1400L).orEmpty()
                        item.detailUrl to posterFromHtml(html, item.detailUrl)
                    }
                }.awaitAll().toMap()
            }
        } ?: emptyMap()

        return items.map { item ->
            val poster = item.poster ?: resolved[item.detailUrl]
            if (poster != item.poster) item.copy(poster = poster) else item
        }
    }

    private fun parseCatalogueHtml(html: String, kind: Kind, base: String): List<CatalogueItem> {
        if (html.isBlank()) return emptyList()
        val document = runCatching { org.jsoup.Jsoup.parse(html, base) }.getOrNull()
            ?: return emptyList()
        val siteHost = runCatching { URL(mainUrl).host.lowercase() }.getOrNull()

        return document.select("a[href]").mapNotNull { anchor ->
            val detailUrl = absolute(anchor.attr("href"), base) ?: return@mapNotNull null
            val target = runCatching { URL(detailUrl) }.getOrNull() ?: return@mapNotNull null
            val targetHost = target.host.lowercase().removePrefix("www.")
            if (siteHost == null || targetHost != siteHost.removePrefix("www.")) return@mapNotNull null

            val path = target.path.lowercase()
            if (path.isBlank() || path == "/" || path.contains("api.php") ||
                path.contains("wp-content") || path.contains("wp-admin") ||
                path.endsWith(".jpg") || path.endsWith(".png") ||
                path.endsWith(".webp") || path.endsWith(".svg") ||
                path.contains("search") || path.contains("login") ||
                path.contains("register") || path.contains("category") ||
                path.contains("kategor") || path.contains("privacy") ||
                path.contains("contact") || path.contains("favori")
            ) return@mapNotNull null

            val image = anchor.selectFirst("img")
            val parentClass = anchor.parent()?.className().orEmpty()
            val context = "${path} ${anchor.className()} ${parentClass}".lowercase()
            val movieLike = listOf("film", "filmler", "movie", "movies").any(context::contains)
            val seriesLike = listOf("dizi", "diziler", "series", "tv-show", "episode").any(context::contains)
            // The endpoint itself identifies movie vs series. Card markup often has
            // generic CSS classes, so reject only entries explicitly marked as the
            // opposite type instead of requiring a type-specific class on every card.
            if (kind == Kind.MOVIE && seriesLike && !movieLike) return@mapNotNull null
            if (kind == Kind.SERIES && movieLike && !seriesLike) return@mapNotNull null
            if (image == null && anchor.attr("title").isBlank() && anchor.text().isBlank()) {
                return@mapNotNull null
            }

            val title = sequenceOf(
                anchor.selectFirst("h1,h2,h3,h4,.title,.name,.film-title,.dizi-title,.movie-title,.series-title,.entry-title")?.text(),
                image?.attr("alt"),
                anchor.attr("title"),
                anchor.text()
            ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
                .firstOrNull()
                ?.takeIf { it.length <= 180 && !it.equals("film", true) &&
                    !it.equals("filmler", true) && !it.equals("dizi", true) &&
                    !it.equals("diziler", true) }
                ?: return@mapNotNull null

            val rawPoster = image?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("data-original").ifBlank {
                            img.attr("data-lazyload").ifBlank { img.attr("src") }
                        }
                    }
                }
            }
            CatalogueItem(
                kind = kind,
                title = title,
                detailUrl = detailUrl,
                poster = absolute(rawPoster, base),
                description = anchor.attr("data-description").takeIf { it.isNotBlank() }
            )
        }.distinctBy { it.detailUrl }
    }

    private fun posterFromHtml(html: String, pageUrl: String): String? {
        if (html.isBlank()) return null

        // Some detail endpoints return JSON instead of a rendered HTML document.
        val jsonPoster = allObjects(parseJson(html)).firstNotNullOfOrNull { obj ->
            obj.string(
                "poster", "image", "imageUrl", "image_url", "posterUrl", "poster_url",
                "filmImg", "filmImage", "diziImg", "diziPoster", "thumb_square", "thumbnail"
            )?.let { absolute(it, pageUrl) }
        }
        if (jsonPoster != null) return jsonPoster

        val doc = runCatching { org.jsoup.Jsoup.parse(html, pageUrl) }.getOrNull() ?: return null
        val metaImage = doc.selectFirst(
            "meta[property='og:image'], meta[name='twitter:image'], meta[property='og:image:secure_url']"
        )?.attr("content")?.takeIf { it.isNotBlank() }
        if (metaImage != null) return absolute(metaImage, pageUrl)

        val image = doc.select("img").firstOrNull { element ->
            val raw = element.attr("data-src").ifBlank {
                element.attr("data-lazy-src").ifBlank { element.attr("src") }
            }
            raw.isNotBlank() && !raw.contains("avatar", true) &&
                !raw.contains("sprite", true) && !raw.contains("placeholder", true)
        } ?: return null

        val raw = image.attr("data-src").ifBlank {
            image.attr("data-lazy-src").ifBlank {
                image.attr("data-original").ifBlank { image.attr("src") }
            }
        }
        return absolute(raw, pageUrl)
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
            }
        } else {
            newTvSeriesSearchResponse(item.title, data, TvType.TvSeries) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    private fun collectionSearchResult(isSport: Boolean): SearchResponse =
        newTvSeriesSearchResponse(
            if (isSport) "Tüm Spor Kanalları" else "Tüm Canlı Kanallar",
            if (isSport) sportCollectionData else liveCollectionData,
            TvType.TvSeries
        ) {
            posterUrl = fallbackPoster
        }

    private fun encodeLivePayload(channel: LiveChannel): String {
        val json = JSONObject()
            .put("title", channel.title)
            .put("sources", JSONArray(channel.urls))
            .put("poster", channel.poster ?: "")
            .put("category", channel.category)
            .put("type", channel.type)
            .toString()
        return Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun encodeLiveChannelRoute(channel: LiveChannel): String =
        liveChannelPrefix + encodeLivePayload(channel)

    private fun liveChannelSearchResult(channel: LiveChannel): SearchResponse =
        newTvSeriesSearchResponse(
            channel.title,
            encodeLiveChannelRoute(channel),
            TvType.TvSeries
        ) {
            posterUrl = channel.poster ?: fallbackPoster
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (isSportCollectionRoute(request.data)) {
            val all = liveChannels()
            val matchedSports = all.filter { isSportsChannel(it) }
            // If the feed has no category metadata, keep this row usable rather than empty.
            val sports = matchedSports.ifEmpty { all }
            val items = listOf(collectionSearchResult(true)) + sports.map { liveChannelSearchResult(it) }
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        if (isLiveCollectionRoute(request.data)) {
            val channels = liveChannels()
            val items = listOf(collectionSearchResult(false)) + channels.map { liveChannelSearchResult(it) }
            return newHomePageResponse(request.name, items, hasNext = false)
        }

        val kind = if (request.data.contains("diziapi.php", true)) Kind.SERIES else Kind.MOVIE
        val items = catalogue(kind).map { searchResponse(it) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val value = query.trim()
        if (value.isBlank()) return emptyList()
        if (value.contains("canlı", true) || value.contains("canli", true) ||
            value.contains("kanal", true) || value.contains("live", true)
        ) return listOf(collectionSearchResult(false))
        if (value.contains("spor", true) || value.contains("sport", true) ||
            value.contains("futbol", true) || value.contains("basket", true)
        ) return listOf(collectionSearchResult(true))

        val remoteMovies = catalogue(Kind.MOVIE, value)
        val remoteSeries = catalogue(Kind.SERIES, value)
        val combined = (remoteMovies + remoteSeries).distinctBy { it.detailUrl }

        if (combined.isNotEmpty()) return combined.map { searchResponse(it) }

        val allItems = catalogue(Kind.MOVIE) + catalogue(Kind.SERIES)
        return allItems.filter { it.title.contains(value, ignoreCase = true) }
            .distinctBy { it.detailUrl }
            .map { searchResponse(it) }
    }

    private fun collectSourceUrls(value: Any?, base: String, depth: Int = 0): List<String> {
        if (depth > 8 || value == null || value == JSONObject.NULL) return emptyList()
        val found = linkedSetOf<String>()

        when (value) {
            is String -> {
                val text = value.trim()
                if (text.startsWith("[") || text.startsWith("{")) {
                    val nested = parseJson(text)
                    if (nested != null && nested !is String) found.addAll(collectSourceUrls(nested, base, depth + 1))
                } else {
                    absolute(text, base)?.let { if (!isRestrictedControlEndpoint(it)) found.add(it) }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    found.addAll(collectSourceUrls(value.opt(i), base, depth + 1))
                }
            }
            is JSONObject -> {
                val directKeys = listOf(
                    "url", "streamUrl", "stream_url", "link", "src", "file",
                    "sourceUrl", "source_url", "playlistUrl", "playlist_url"
                )
                directKeys.forEach { key ->
                    val child = value.opt(key)
                    if (child != null && child != JSONObject.NULL) {
                        found.addAll(collectSourceUrls(child, base, depth + 1))
                    }
                }
                listOf("sources", "streams", "links", "urls", "items", "data").forEach { key ->
                    val child = value.opt(key)
                    if (child != null && child != JSONObject.NULL) {
                        found.addAll(collectSourceUrls(child, base, depth + 1))
                    }
                }
            }
        }
        return found.toList()
    }

    private suspend fun liveChannels(): List<LiveChannel> {
        val feed = requestText(liveChannelFeed, mainUrl, 1800L).orEmpty()
        val configRoot = parseJson(feed)

        // The APK's config response can contain jsonurl pointing to the public
        // channel-list JSON instead of containing channel records itself. Fetch
        // that declared data URL directly; nonce/verify anti-bot flows are not used.
        val configuredList = allObjects(configRoot)
            .firstNotNullOfOrNull { it.string("jsonurl", "jsonUrl", "json_url", "channelsUrl", "channels_url") }
            ?.let { absolute(it, liveChannelFeed) }
            ?.takeUnless { isRestrictedControlEndpoint(it) }

        val channelText = if (configuredList != null) {
            requestText(configuredList, liveChannelFeed, 1800L)
                ?.takeIf { it.isNotBlank() }
                ?: feed
        } else {
            feed
        }
        val channelBase = configuredList ?: liveChannelFeed
        val root = parseJson(channelText)
        val channels = allObjects(root).mapNotNull { obj ->
            // Actual fields used by the app's live-channel parser: title/image/sources/type/url.
            // The APK's live-player JSON also uses the Turkish keys "isim" and "link".
            val title = obj.string(
                "title", "name", "isim", "channelName", "channel_name",
                "channelTitle", "channel_title", "kanalAdi", "kanal_adi", "kanal"
            )?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val type = obj.string("type", "channelType", "channel_type", "tur", "yayinTipi").orEmpty()
            val category = obj.string(
                "category", "kategori", "group", "grup", "groupName", "group_name",
                "categoryName", "category_name", "kategoriAdi", "kategori_adi", "genre"
            ).orEmpty()

            val sources = linkedSetOf<String>()
            sources.addAll(collectSourceUrls(obj.opt("sources"), channelBase))
            // Channel data may wrap its playable URL in different fields or in a JSON string.
            listOf("url", "link", "streamUrl", "stream_url", "playlist_json", "playlistJson")
                .forEach { key ->
                    val child = obj.opt(key)
                    if (child != null && child != JSONObject.NULL) {
                        sources.addAll(collectSourceUrls(child, channelBase))
                    }
                }
            val direct = obj.string(
                "url", "streamUrl", "stream_url", "link", "stream", "sourceUrl", "source_url",
                "playlist", "playlistUrl", "playlist_url"
            )?.let { absolute(it, channelBase) }
            if (direct != null && !isRestrictedControlEndpoint(direct)) sources.add(direct)

            val usable = sources.filter { it.startsWith("http", true) && !isRestrictedControlEndpoint(it) }
            if (usable.isEmpty()) return@mapNotNull null

            val rawPoster = obj.string(
                "image", "imageUrl", "image_url", "poster", "posterUrl", "poster_url",
                "logo", "logoUrl", "channelLogo", "channel_logo", "icon", "iconUrl",
                "img", "thumbnailUrl", "thumbnail_url", "resim", "resimUrl",
                "logo_path", "channelImage", "channel_image", "thumb_square"
            )
            LiveChannel(
                title = title,
                urls = usable.distinct(),
                poster = absolute(rawPoster, channelBase) ?: fallbackPoster,
                category = category,
                type = type
            )
        }.distinctBy { it.title.lowercase() + "|" + it.urls.firstOrNull().orEmpty() }

        if (channels.isNotEmpty()) return channels

        // Fallback to visible channel cards, if a site revision moves the list to HTML.
        val html = requestText(mainUrl, mainUrl, 1500L).orEmpty()
        if (html.isBlank()) return emptyList()
        val doc = runCatching { org.jsoup.Jsoup.parse(html, mainUrl) }.getOrNull() ?: return emptyList()
        return doc.select("a[href]").mapNotNull { anchor ->
            val target = absolute(anchor.attr("href"), mainUrl) ?: return@mapNotNull null
            val linkClass = "${anchor.className()} ${anchor.parent()?.className().orEmpty()}".lowercase()
            if (!target.contains("/channel/", true) && !target.contains("live", true) &&
                !target.contains("kanal", true) && !linkClass.contains("channel") &&
                !linkClass.contains("kanal")
            ) return@mapNotNull null
            val image = anchor.selectFirst("img")
            val title = anchor.attr("title").ifBlank {
                image?.attr("alt").orEmpty().ifBlank { anchor.text() }
            }.trim().ifBlank { return@mapNotNull null }
            LiveChannel(
                title = title,
                urls = listOf(target),
                poster = absolute(
                    image?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                        ?: image?.attr("src"),
                    mainUrl
                ),
                category = anchor.attr("data-category"),
                type = anchor.attr("data-type")
            )
        }.distinctBy { it.title.lowercase() }
    }

    private fun isSportsChannel(channel: LiveChannel): Boolean {
        val value = "${channel.title} ${channel.category} ${channel.type} ${channel.urls.joinToString(" ")}".lowercase()
        return listOf(
            "spor", "sport", "futbol", "football", "basket", "tenis", "motor",
            "formula", "eurosport", "beinsport", "bein sports", "s sport",
            "aspor", "a spor", "trt spor", "maç", "mac ", "league", "liga"
        ).any { value.contains(it) }
    }

    private fun encodeLiveEpisode(channel: LiveChannel): String =
        liveEpisodePrefix + encodeLivePayload(channel)

    private fun decodeLiveChannelRoute(value: String): LiveChannel? {
        if (!value.startsWith(liveChannelPrefix)) return null
        val decoded = runCatching {
            String(
                Base64.decode(
                    value.removePrefix(liveChannelPrefix),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val urls = collectSourceUrls(json.opt("sources"), liveChannelFeed)
            .filterNot { isRestrictedControlEndpoint(it) }
            .distinct()
        if (urls.isEmpty()) return null
        return LiveChannel(
            title = json.optString("title", "Canlı Kanal"),
            urls = urls,
            poster = absolute(json.optString("poster").takeIf { it.isNotBlank() }, liveChannelFeed) ?: fallbackPoster,
            category = json.optString("category"),
            type = json.optString("type")
        )
    }

    private fun decodeLiveEpisode(value: String): List<String>? {
        if (!value.startsWith(liveEpisodePrefix)) return null
        val decoded = runCatching {
            String(
                Base64.decode(
                    value.removePrefix(liveEpisodePrefix),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return emptyList()
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        return collectSourceUrls(json.opt("sources"), liveChannelFeed)
            .filterNot { isRestrictedControlEndpoint(it) }
            .distinct()
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
        val singleChannel = decodeLiveChannelRoute(url)
        if (singleChannel != null) {
            return newTvSeriesLoadResponse(
                singleChannel.title,
                encodeLiveChannelRoute(singleChannel),
                TvType.TvSeries,
                listOf(
                    newEpisode(encodeLiveEpisode(singleChannel)) {
                        name = singleChannel.title
                        season = 1
                        this.episode = 1
                        posterUrl = singleChannel.poster ?: fallbackPoster
                    }
                )
            ) {
                posterUrl = singleChannel.poster
                plot = listOf(singleChannel.category, singleChannel.type)
                    .filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Canlı yayın" }
            }
        }

        if (isSportCollectionRoute(url) || isLiveCollectionRoute(url)) {
            val all = liveChannels()
            val isSport = isSportCollectionRoute(url)
            val matchedSports = if (isSport) all.filter { isSportsChannel(it) } else emptyList()
            val channels = when {
                !isSport -> all
                matchedSports.isNotEmpty() -> matchedSports
                else -> all
            }
            val episodes = channels.mapIndexed { index, channel ->
                newEpisode(encodeLiveEpisode(channel)) {
                    name = channel.title
                    season = 1
                    this.episode = index + 1
                    posterUrl = channel.poster
                }
            }
            return newTvSeriesLoadResponse(
                if (isSport) "GölgeTV Spor" else "GölgeTV Canlı",
                if (isSport) sportCollectionData else liveCollectionData,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = channels.firstOrNull()?.poster ?: fallbackPoster
                plot = if (isSport) "Spor kategorisindeki yayınlar." else "Tüm canlı yayınlar tek bölüm listesinde."
            }
        }

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
                posterUrl = absolute(detail.string("filmImg", "poster", "image", "imageUrl", "posterUrl", "thumb_square"), item.detailUrl) ?: item.poster
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
                    this.episode = index + 1
                    posterUrl = absolute(detail.string("diziPoster", "poster", "image", "imageUrl", "posterUrl", "thumb_square"), item.detailUrl) ?: item.poster
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

    // Do not judge a channel by its display name or the domain serving it.
    // Only reject URLs that are clearly authentication or licence-control calls.
    private fun isRestrictedControlEndpoint(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return true
        val path = (parsed.path + "?" + (parsed.query ?: "")).lowercase()
        return path.contains("/api/attest/") ||
            path.contains("/api/auth/token") ||
            path.contains("/licenseraw") ||
            path.contains("/widevine/license") ||
            path.contains("/channels/ticket") ||
            path.contains("/epg/json/authenticate") ||
            path.contains("/epg/json/play")
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
        if (!isDirectMedia(url) || isRestrictedControlEndpoint(url)) return false
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
        val starts = decodeLiveEpisode(data)
            ?: listOfNotNull(absolute(data, mainUrl))
        if (starts.isEmpty()) return false

        var emitted = false
        val visited = linkedSetOf<String>()

        suspend fun walk(url: String, referer: String, depth: Int) {
            if (depth > 2 || url in visited || isRestrictedControlEndpoint(url)) return
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
                if (candidate == url || isRestrictedControlEndpoint(candidate)) continue
                if (emitPublicLink(candidate, url, callback)) {
                    emitted = true
                } else if (!isDirectMedia(candidate) && depth < 2) {
                    walk(candidate, url, depth + 1)
                }
            }
        }

        starts.forEach { start ->
            if (!isRestrictedControlEndpoint(start)) {
                walk(start, mainUrl, 0)
            }
        }
        return emitted
    }
}
