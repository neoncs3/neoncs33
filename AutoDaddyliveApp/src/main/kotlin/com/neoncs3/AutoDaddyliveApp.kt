package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AutoDaddyliveApp : NeonMainAPI() {
    override var mainUrl = "https://daddylive.app"
    override var name = "Daddylive"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    // Kanalları çektiğimiz JSON API'si
    private val channelApiUrl = "https://daddylive.app/player/player14.json"

    private val playerUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    private val liveReferer = "https://daddylive.app/"
    private val liveOrigin = "https://daddylive.app"

    override val mainPage = mainPageOf(
        "https://daddylive.app/" to "Ana Sayfa"
    )

    private fun headers(referer: String = mainUrl + "/"): Map<String, String> =
        neonHeaders + mapOf(
            "Referer" to referer,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )

    private fun siteHost(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    private fun isValidHttpUrl(raw: String): Boolean =
        runCatching {
            val uri = URI(raw)
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)

    // ------------------------------------------------------------------
    // JSON API'sinden kanalları çeken fonksiyon
    // ------------------------------------------------------------------
    private suspend fun loadChannelsFromApi(): List<SearchResponse> {
        val body = runCatching {
            app.get(
                channelApiUrl,
                headers = headers(channelApiUrl) + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 20_000L,
                allowRedirects = true,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (body.isBlank()) {
            Log.w(name.uppercase().take(23), "Channel API returned empty response")
            return emptyList()
        }

        val items = ArrayList<SearchResponse>()
        runCatching {
            val jsonArray = JSONArray(body)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val channelName = obj.optString("name").trim()
                val streamUrl = obj.optString("url").trim()
                if (channelName.isBlank() || streamUrl.isBlank()) continue
                if (!isValidHttpUrl(streamUrl)) continue

                items += newLiveSearchResponse(channelName, streamUrl, TvType.Live) {
                    posterUrl = mainUrl.trimEnd('/') + "/favicon.ico"
                }
            }
        }.onFailure {
            Log.e(name.uppercase().take(23), "Error parsing channel JSON", it)
        }

        Log.d(name.uppercase().take(23), "Channel API parsed items=${items.size}")
        return items
    }

    // ------------------------------------------------------------------
    // Oynatma linkini doğrudan döndüren yardımcı
    // ------------------------------------------------------------------
    private fun emitDirectLiveLink(
        streamUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val outHeaders = mapOf(
            "Referer" to liveReferer,
            "Origin" to liveOrigin,
            "User-Agent" to playerUserAgent,
        )
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                referer = liveReferer,
                quality = Qualities.Unknown.value,
                headers = outHeaders,
                type = ExtractorLinkType.M3U8,
            )
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page <= 1) {
            val liveItems = loadChannelsFromApi()
            if (liveItems.isNotEmpty()) {
                val pages = liveItems.chunked(300).mapIndexed { index, chunk ->
                    val label = if (index == 0) "Canlı Kanallar" else "Canlı Kanallar ${index + 1}"
                    HomePageList(label, chunk)
                }
                return newHomePageResponse(pages, hasNext = false)
            }
        }

        // API başarısız olursa eski davranışa düş (boş ana sayfa yerine).
        val base = request.data
        val document = runCatching {
            app.get(base, headers = headers(base), timeout = 12_000L).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = resultsFrom(document, base)
        return newHomePageResponse(request.name, results, hasNext = false)
    }

    // ---- Aşağıdaki fonksiyonlar önceki sürümden aynen korunmuştur ----

    private fun isSiteUrl(url: String): Boolean =
        (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            siteHost(url) == siteHost(mainUrl)

    private fun isBrokenSiteMediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (siteHost(raw) != siteHost(mainUrl)) return false
        val path = uri.path.orEmpty().trimEnd('/')
        return path.equals("/manifest.webm", ignoreCase = true) ||
            Regex("""(?i)^/cdn/[^/]+/s\d{1,2}e\d{1,3}/master\.m3u8$""").matches(path)
    }

    private fun normalize(raw: String, base: String = mainUrl): String =
        neonNormalizeUrl(raw, base)

    private fun isLikelyPlayerUrl(url: String): Boolean {
        if (neonIsMediaUrl(url)) return true
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val hint = (uri.path.orEmpty() + "?" + uri.rawQuery.orEmpty()).lowercase()
        return Regex("""(?i)(?:^|[/_.?=&-])(?:player|embed|iframe|video|stream|play|watch|manifest|media|playlist|source)(?:[/_.?=&-]|$)""")
            .containsMatchIn(hint)
    }

    private fun isYouTubePageUrl(raw: String): Boolean {
        val host = runCatching {
            URI(raw).host.orEmpty().lowercase().removePrefix("www.")
        }.getOrDefault("")
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
    }

    private fun isYouTubeMediaLink(link: ExtractorLink): Boolean {
        val host = runCatching {
            URI(link.url).host.orEmpty().lowercase().removePrefix("www.")
        }.getOrDefault("")
        return isYouTubePageUrl(link.url) || host == "googlevideo.com" ||
            host.endsWith(".googlevideo.com")
    }

    private fun playerUrlsFromText(raw: String, baseUrl: String): List<String> {
        val text = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")
        val values = LinkedHashSet<String>()
        neonExtractMediaUrls(text).forEach(values::add)

        val namedValuePattern = Regex(
            """(?is)["']?(?:src|file|file_url|source|source_url|url|embed|embed_url|iframe|iframe_url|video_url|video_src|videoUrl|play_url|player_url|playerUrl|hls|hls_url|playlist|stream|stream_url|media_url|manifest)["']?\s*[:=]\s*["']([^"'<>\s]+)["']"""
        )
        namedValuePattern.findAll(text).forEach { values.add(it.groupValues[1]) }

        val relativePlayerPattern = Regex(
            """(?i)["'](/[^"'<>\s]*(?:[/_.?=&-](?:player|embed|iframe|watch|video|stream|play|manifest|media|playlist|source)(?:[/_.?=&-]|$))[^"'<>\s]*)["']"""
        )
        relativePlayerPattern.findAll(text).forEach { values.add(it.groupValues[1]) }

        return values.mapNotNull { rawValue ->
            val cleaned = rawValue.trim().trimEnd('"', '\'', ')', ']', '}', ',', ';')
            val url = normalize(cleaned, baseUrl)
            url.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.takeIf(::isLikelyPlayerUrl)
        }
            .filterNot(::isYouTubePageUrl)
            .distinct()
    }

    private fun isEpisodeLike(value: String): Boolean =
        Regex("""(?i)(?:b[öo]l[üu]m|episodes?|ep[\s._-]*\d|s\d{1,2}e\d{1,3}|\d{1,2}\s*[x×]\s*\d{1,3})""")
            .containsMatchIn(value)

    private fun normalizedFilterText(value: String): String =
        value.lowercase()
            .replace("ı", "i")
            .replace("ş", "s")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

    private val navigationOnlyLabels = setOf(
        "yorum", "yorumlar", "comment", "comments", "replies",
        "kesfet", "kesif", "explore", "discover",
        "takvim", "takvimi", "calendar", "schedule",
        "dizi takvimi", "film takvimi", "tv schedule",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "login", "register", "giris", "kayit", "hesabim", "profil", "profilim",
        "profile", "profiles", "user", "users", "member", "members",
        "author", "authors", "account", "accounts", "uye", "uyeler",
        "kullanici", "kullanicilar", "ayarlar", "iletisim", "hakkimizda", "yardim",
        "privacy policy", "terms", "about us", "contact us",
        "ana sayfa", "home", "menu", "menü",
        "dizi", "diziler", "film", "filmler", "movies", "series",
        "izle", "tum diziler", "tum filmler", "tum bolumler",
        "en son bolumler", "son bolumler", "populer", "populer filmler"
    )

    private val utilityLabelPrefixes = setOf(
        "yorum", "yorumlar", "comment", "comments", "reply", "replies",
        "kesfet", "kesif", "explore", "discover", "takvim", "takvimi",
        "calendar", "schedule", "dizi takvimi", "film takvimi", "tv schedule",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "profil", "profile", "user profile", "member profile",
        "kullanici profili", "uye profili", "my profile"
    )

    private val navigationOnlyPaths = setOf(
        "yorum", "yorumlar", "comment", "comments", "replies",
        "kesfet", "kesif", "explore", "discover", "takvim", "takvimi",
        "calendar", "schedule", "dizi takvimi", "film takvimi",
        "tartisma", "tartismalar", "discussion", "discussions", "discuss",
        "baslik", "basliklar", "topic", "topics", "forum", "forums",
        "thread", "threads", "konu", "konular", "sohbet", "sohbetler",
        "community", "topluluk", "gundem", "duyurular",
        "profile", "profiles", "profil", "profiller", "profilim",
        "user", "users", "member", "members", "author", "authors",
        "account", "accounts", "u", "uye", "uyeler", "kullanici",
        "kullanicilar", "avatar", "avatars", "activity", "notifications",
        "messages", "inbox", "groups", "group", "settings", "dashboard"
    )

    private fun isNavigationChrome(anchor: Element): Boolean {
        var node: Element? = anchor
        repeat(7) {
            val current = node ?: return false
            val tag = current.tagName().lowercase()
            if (tag == "nav" || tag == "footer" ||
                current.attr("role").equals("navigation", ignoreCase = true)
            ) {
                return true
            }

            val hint = normalizedFilterText(current.id() + " " + current.className())
            val nonCatalogMarkers = listOf(
                "site header", "site nav", "navbar", "main nav", "primary nav",
                "main menu", "primary menu", "top menu", "menu item", "sidebar",
                "breadcrumb", "comments nav", "site footer", "footer menu",
                "profile", "user profile", "user card", "member card", "avatar",
                "author box", "author link", "discussion", "thread card",
                "forum topic", "topic card", "comment list", "reply list",
                "user meta", "member meta", "profile meta", "user info",
                "member info", "profile info", "user summary", "member summary"
            )
            if (nonCatalogMarkers.any { hint.contains(it) }) return true

            val image = current.selectFirst("img")
            if (image != null) {
                val imageHint = normalizedFilterText(
                    image.attr("class") + " " + image.id() + " " +
                        image.attr("alt") + " " + image.attr("title") + " " +
                        image.attr("src") + " " + image.attr("data-src")
                )
                if (listOf(
                        "avatar", "user photo", "profile photo", "profile picture",
                        "member photo", "author photo", "account photo"
                    ).any { imageHint.contains(it) }
                ) return true
            }
            node = current.parent()
        }
        return false
    }

    private fun isNavigationItem(title: String, url: String): Boolean {
        val label = normalizedFilterText(title)
        if (label in navigationOnlyLabels ||
            utilityLabelPrefixes.any { label == it || label.startsWith("$it ") }
        ) {
            return true
        }

        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        val rawSegments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }
        if (rawSegments.any { it.startsWith("@") }) return true
        val segments = rawSegments.map {
            normalizedFilterText(it.replace('-', ' ').replace('_', ' '))
        }
        if (segments.any { it in navigationOnlyPaths }) return true

        val query = uri.rawQuery.orEmpty()
        if (Regex(
                "(?i)(?:^|&)(?:user|userid|user_id|profile|profile_id|member|member_id|author|author_id|thread|thread_id|topic|topic_id|discussion|comment|reply|post_id)="
            ).containsMatchIn(query)
        ) return true

        if (Regex("""(?i)^.{1,40}\s+\d{1,2}\s*(?:level|lvl)\b.*$""")
                .matches(title.trim())
        ) return true
        if (Regex("""(?i)(?:\+\s*\d[\d,.]*\s*(?:puan|points?)|(?:puan|points?)\s*\+?\s*\d[\d,.]*)""")
                .containsMatchIn(title)
        ) return true

        if (segments.size == 1 && segments.first() in setOf(
                "dizi", "diziler", "film", "filmler", "movies", "series",
                "home", "ana sayfa", "kesfet", "takvim", "yorumlar"
            )
        ) return true

        return false
    }

    private fun isFlagOrIconAsset(value: String): Boolean {
        val hint = normalizedFilterText(value)
        if (listOf(
                "flag icon", "country flag", "language flag", "flag sprite"
            ).any { hint.contains(it) }
        ) return true
        if (Regex("""(?i)(?:^|/)(?:flags?|flag-icons|country-flags?)/""")
                .containsMatchIn(value)
        ) return true
        val file = value.substringBefore('?').substringBefore('#')
            .substringAfterLast('/').lowercase()
        return Regex(
            """(?i)^(?:flags?|flag[-_]icon|icon[-_]flag|country[-_]?flag|flag[-_]sprite|language[-_]?flag)(?:[-_][a-z]{2})?\.(?:svg|png|jpe?g|webp|gif|ico)$"""
        ).matches(file)
    }

    private fun isValidPosterUrl(url: String): Boolean {
        if (isFlagOrIconAsset(url)) return false
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            return false
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val file = path.substringAfterLast('/')
        if (listOf(
                "/avatar/", "/avatars/", "/user/", "/users/", "/profile/",
                "/profiles/", "/member/", "/members/", "/author/", "/authors/",
                "/account/", "/accounts/", "/community/", "/forum/", "/forums/"
            ).any { path.contains(it) }
        ) return false
        val placeholderMarkers = listOf(
            "placeholder", "no-image", "no_image", "noimage", "no-poster",
            "noposter", "blank.gif", "transparent", "avatar", "favicon",
            "spinner", "loading", "pixel", "missing", "not-found",
            "user-photo", "profile-photo", "member-photo", "author-photo"
        )
        if (placeholderMarkers.any { file.contains(it) }) return false
        if (Regex("""(?i)^logo(?:[-_].*)?\.(?:png|jpe?g|webp|gif|svg|ico)$""")
                .matches(file)
        ) return false
        return file.isNotBlank()
    }

    private fun isNonContentAnchor(anchor: Element, baseUrl: String): Boolean {
        if (anchor.classNames().any { it.equals("alphabetical-category-link", ignoreCase = true) }) {
            return true
        }
        val rawHref = anchor.attr("href").trim()
        if (rawHref.startsWith("#")) return true

        val target = runCatching { URI(normalize(rawHref, baseUrl)) }.getOrNull() ?: return false
        val base = runCatching { URI(normalize(baseUrl)) }.getOrNull() ?: return false
        return !target.rawFragment.isNullOrBlank() &&
            target.scheme.equals(base.scheme, ignoreCase = true) &&
            target.host.equals(base.host, ignoreCase = true) &&
            target.path.orEmpty().trimEnd('/') == base.path.orEmpty().trimEnd('/') &&
            target.rawQuery.orEmpty() == base.rawQuery.orEmpty()
    }

    private fun isDetailUrl(raw: String, base: String = mainUrl): Boolean {
        val url = normalize(raw, base)
        if (!url.startsWith("http", true) || siteHost(url) != siteHost(mainUrl)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        if (path.isBlank() || path == "/") return false
        if (listOf(
                "/wp-admin", "/wp-content", "/wp-json", "/tag/", "/category/", "/genre/",
                "/kategori/", "/tur/", "/oyuncu/", "/actor/", "/search/", "/page/",
                "/login", "/register", "/giris", "/kayit", "/iletisim", "/contact",
                "/privacy", "/terms", "/feed", "/yorum", "/comment", "/kesfet",
                "/discover", "/takvim", "/calendar", "/tartisma", "/discussion",
                "/baslik", "/topics", "/forum", "/profile", "/profil", "/user",
                "/member", "/author", "/account", "/uye", "/kullanici", "/threads",
                "/thread", "/konu", "/konular", "/community", "/sohbet", "/replies"
            ).any { path.contains(it) }
        ) return false
        if (listOf(
                ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
                ".css", ".js", ".m3u8", ".mpd", ".mp4", ".webm", ".m4v",
                ".mov", ".flv", ".avi", ".mkv", ".mpeg", ".mpg", ".3gp",
                ".ts", ".m4s", ".mp3", ".m4a", ".aac", ".ogg", ".wav",
                ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx", ".woff",
                ".woff2", ".ttf", ".eot", ".webmanifest"
            ).any { path.endsWith(it) }
        ) return false
        return path.trim('/').isNotBlank()
    }

    private fun containerFor(anchor: Element): Element {
        var node: Element? = anchor
        repeat(5) {
            if (node == null) return@repeat
            if (node!!.selectFirst("img") != null) return node!!
            node = node!!.parent()
        }
        return anchor
    }

    private fun imageUrl(image: Element?, baseUrl: String): String? {
        if (image == null) return null
        val imageHint = normalizedFilterText(
            image.attr("class") + " " + image.id() + " " +
                image.attr("alt") + " " + image.attr("title")
        )
        val assetHints = imageHint + " " + listOf(
            "src", "data-src", "data-lazy-src", "data-lazy", "data-original", "data-original-src",
            "data-poster", "data-image", "data-img", "data-thumb", "data-cover", "data-bg",
            "data-background", "srcset", "data-srcset", "style"
        ).joinToString(" ") { image.attr(it) }
        if (isFlagOrIconAsset(assetHints)) return null
        if (listOf(
                "avatar", "user photo", "profile photo", "profile picture",
                "member photo", "author photo", "account photo", "user profile"
            ).any { imageHint.contains(it) }
        ) return null

        val direct = sequenceOf(
            image.attr("data-poster"), image.attr("data-src"), image.attr("data-lazy-src"),
            image.attr("data-lazy"), image.attr("data-original"), image.attr("data-original-src"),
            image.attr("data-image"), image.attr("data-img"), image.attr("data-thumb"),
            image.attr("data-cover"), image.attr("data-bg"), image.attr("data-background"),
            image.attr("poster"), image.attr("src"),
        )
        val responsive = sequenceOf(image.attr("data-srcset"), image.attr("srcset"))
            .flatMap { value ->
                value.split(",").asSequence()
                    .map { it.trim().substringBefore(" ") }
                    .filter { it.isNotBlank() }
            }
        val cssBackgrounds = Regex("""(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)""")
            .findAll(image.attr("style"))
            .map { it.groupValues[1].trim() }

        return (direct + responsive + cssBackgrounds)
            .filter { it.isNotBlank() && !it.startsWith("data:", true) }
            .map { normalize(it, baseUrl) }
            .firstOrNull { isValidPosterUrl(it) }
    }

    private fun posterFor(anchor: Element, baseUrl: String): String? {
        var node: Element? = anchor
        for (depth in 0..4) {
            val current = node ?: break
            if (depth > 0 && current.select("a[href]").any { it !== anchor }) return null
            imageUrl(current, baseUrl)?.let { return it }
            val urls = LinkedHashSet<String>()
            current.select(
                "img, picture source, source[srcset], [data-poster], [data-bg], " +
                    "[data-background], [style*=background]"
            ).forEach { candidate ->
                imageUrl(candidate, baseUrl)?.let(urls::add)
            }
            if (urls.size == 1) return urls.first()
            if (urls.size > 1) return null
            node = current.parent()
        }
        return null
    }

    private fun candidateAnchors(document: Document): List<Element> {
        val cardSelector =
            "article:has(img) a[href], article:has([style*=background]) a[href], " +
                "article:has([data-poster]) a[href], .movie-card a[href], .film-card a[href], " +
                ".dizi-card a[href], .post:has(img) a[href], .post:has([style*=background]) a[href], " +
                ".post:has([data-poster]) a[href], .item:has(img) a[href], " +
                ".item:has([style*=background]) a[href], .item:has([data-poster]) a[href], " +
                ".item:has([data-src]) a[href], .card:has(img) a[href], " +
                ".card:has([style*=background]) a[href], .card:has([data-poster]) a[href], " +
                ".card:has([data-src]) a[href], .movie:has(img) a[href], " +
                ".movie:has([style*=background]) a[href], .film:has(img) a[href], " +
                ".film:has([style*=background]) a[href], .dizi:has(img) a[href], " +
                ".dizi:has([style*=background]) a[href], .poster a[href], " +
                ".videos:has(img) a[href], .videos:has([style*=background]) a[href]"
        val mediaSelector =
            "a[href]:has(img), a[href]:has(picture), a[href][data-poster], " +
                "a[href][data-image], a[href][data-src], a[href][data-lazy-src], " +
                "a[href][style*=background], a[href]:has([style*=background])"
        val anchors = ArrayList<Element>()
        val seen = LinkedHashSet<String>()
        fun collect(selector: String, limit: Int) {
            for (anchor in document.select(selector)) {
                if (anchors.size >= limit) break
                if (isNavigationChrome(anchor) || isNonContentAnchor(anchor, mainUrl)) continue
                if (posterFor(anchor, mainUrl) == null) continue
                val href = normalize(anchor.attr("href"), mainUrl)
                if (seen.add(href)) anchors.add(anchor)
            }
        }
        collect(mediaSelector, 200)
        if (anchors.size < 15) collect(cardSelector, 200)
        return anchors
    }

    private fun responseFromAnchor(anchor: Element, baseUrl: String): SearchResponse? {
        if (isNonContentAnchor(anchor, baseUrl)) return null
        val href = normalize(anchor.attr("href"), baseUrl)
        if (!isDetailUrl(href, baseUrl)) return null
        val container = containerFor(anchor)
        val image = anchor.selectFirst("img") ?: container.selectFirst("img")
        val title = sequenceOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            anchor.selectFirst("h1, h2, h3, h4, .title, .name, .movie-title, .film-title, .dizi-title")?.text(),
            image?.attr("alt"),
            anchor.text(),
            container.attr("title")
        ).mapNotNull { neonCleanText(it) }
            .firstOrNull { it.length in 2..160 } ?: return null
        if (title.equals(name, true) || title.equals("Ana Sayfa", true)) return null
        if (isEpisodeLike(title + " " + href)) return null
        if (isNavigationItem(title, href) || isNavigationChrome(anchor)) return null

        val poster = posterFor(anchor, baseUrl) ?: return null
        if (!isValidPosterUrl(poster)) return null
        val scoreValue = neonExtractRating(container.text())
        val series = Regex("""(?i)(dizi|series|season|sezon|tv-show|tvseries)""")
            .containsMatchIn(title + " " + href)
        val posterHeaders = headers(baseUrl)
        return if (series) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.posterHeaders = posterHeaders
                scoreValue?.let { score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.posterHeaders = posterHeaders
                scoreValue?.let { score = Score.from10(it) }
            }
        }
    }

    private fun resultsFrom(document: Document, baseUrl: String): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()
        for (anchor in candidateAnchors(document)) {
            if (results.size >= 120) break
            val item = responseFromAnchor(anchor, baseUrl) ?: continue
            if (!results.containsKey(item.url)) results[item.url] = item
        }
        return results.values.toList()
    }

    private fun searchUrls(encoded: String): List<String> {
        return listOf(
            mainUrl + "/?s=" + encoded,
            mainUrl + "/?search=" + encoded,
            mainUrl + "/?q=" + encoded,
            mainUrl + "/search/" + encoded + "/"
        ).distinct()
    }

    private fun matchesQuery(result: SearchResponse, query: String): Boolean {
        return neonRelevantSearchResults(listOf(result), query).isNotEmpty()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        for (searchUrl in searchUrls(encoded)) {
            val response = runCatching {
                app.get(searchUrl, headers = headers(searchUrl), timeout = 12_000L)
            }.getOrNull() ?: continue
            val matches = resultsFrom(response.document, searchUrl)
                .filter { matchesQuery(it, cleanQuery) }
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    private fun structuredObjects(document: Document): List<JSONObject> {
        val found = ArrayList<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    found.add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is JSONObject || child is JSONArray) visit(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.opt(i)
                        if (child is JSONObject || child is JSONArray) visit(child)
                    }
                }
            }
        }
        document.select("script[type*=ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }
            runCatching {
                val value = raw.trim().removeSuffix(";")
                if (value.startsWith("[")) visit(JSONArray(value)) else visit(JSONObject(value))
            }
        }
        return found
    }

    private fun jsonText(value: Any?): String? {
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is JSONObject -> sequenceOf(
                value.optString("url").takeIf { it.isNotBlank() && it != "null" },
                value.optString("name").takeIf { it.isNotBlank() && it != "null" },
                value.optString("@id").takeIf { it.isNotBlank() && it != "null" }
            ).firstOrNull { it != null }
            is JSONArray -> (0 until value.length()).asSequence()
                .mapNotNull { jsonText(value.opt(it)) }.firstOrNull()
            else -> null
        }
    }

    private fun schemaValue(document: Document, vararg keys: String): String? {
        val objects = structuredObjects(document)
        for (key in keys) {
            for (obj in objects) {
                jsonText(obj.opt(key))?.let { return it }
            }
        }
        return null
    }

    private fun schemaRating(document: Document): Double? {
        for (obj in structuredObjects(document)) {
            val aggregate = obj.optJSONObject("aggregateRating")
            val raw = aggregate?.optString("ratingValue")
                ?: obj.optString("ratingValue").takeIf { it.isNotBlank() && it != "null" }
            val value = raw?.replace(',', '.')?.toDoubleOrNull()
            if (value != null && value in 0.0..10.0) return value
        }
        return null
    }

    private fun schemaGenres(document: Document): List<String> {
        val values = ArrayList<String>()
        for (obj in structuredObjects(document)) {
            val raw = obj.opt("genre")
            when (raw) {
                is String -> values.add(raw)
                is JSONArray -> for (i in 0 until raw.length()) {
                    raw.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let(values::add)
                }
            }
        }
        return values.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(30)
    }

    private fun schemaTypes(document: Document): Set<String> {
        return structuredObjects(document).flatMap { obj ->
            when (val raw = obj.opt("@type")) {
                is String -> listOf(raw.lowercase())
                is JSONArray -> (0 until raw.length()).map { raw.optString(it).lowercase() }
                else -> emptyList()
            }
        }.toSet()
    }

    private fun seasonNumber(value: String): Int? {
        val before = Regex("""(?i)\b0?(\d{1,2})\s*\.?\s*(?:season|sezon)\b""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (before != null) return before
        return Regex("""(?i)(?:season|sezon|s)[\s._/-]*0?(\d{1,2})""")
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodeInfo(value: String, defaultSeason: Int?): Pair<Int, Int>? {
        val full = Regex(
            """(?i)(?:s|season|sezon)[\s._/-]*0?(\d{1,2})[\s._/-]*(?:e|ep|episodes?|b[öo]l[üu]m)[\s._/-]*0?(\d{1,3})"""
        ).find(value)
        if (full != null) {
            val season = full.groupValues[1].toIntOrNull() ?: defaultSeason ?: 1
            val episode = full.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        val compact = Regex("""(?i)\b(?:s)?0?(\d{1,2})\s*[x×]\s*0?(\d{1,3})\b""").find(value)
        if (compact != null) {
            return (compact.groupValues[1].toIntOrNull() ?: defaultSeason ?: 1) to
                (compact.groupValues[2].toIntOrNull() ?: return null)
        }
        val turkish = Regex(
            """(?i)(\d{1,2})\s*\.?\s*sezon.{0,24}?(\d{1,3})\s*\.?\s*b[öo]l[üu]m"""
        ).find(value)
        if (turkish != null) {
            return (turkish.groupValues[1].toIntOrNull() ?: 1) to
                (turkish.groupValues[2].toIntOrNull() ?: return null)
        }
        val onlyEpisode = Regex(
            """(?i)(?:b[öo]l[üu]m|episodes?|ep[\s._-]*)[\s._/#:-]*0?(\d{1,3})|\b(\d{1,3})\s*\.?\s*b[öo]l[üu]m"""
        ).find(value) ?: return null
        val number = (onlyEpisode.groupValues.getOrNull(1)?.toIntOrNull()
            ?: onlyEpisode.groupValues.getOrNull(2)?.toIntOrNull()) ?: return null
        return (seasonNumber(value) ?: defaultSeason ?: 1) to number
    }

    private data class EpisodeItem(
        val url: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val poster: String?
    )

    private fun seasonLinks(document: Document, baseUrl: String): List<Pair<String, Int?>> {
        return document.select("a[href]").mapNotNull { anchor ->
            val rawHref = anchor.attr("href")
            val text = anchor.text() + " " + anchor.attr("title") + " " + rawHref
            if (episodeInfo(text, null) != null) return@mapNotNull null
            val season = seasonNumber(text) ?: return@mapNotNull null
            if (isNonContentAnchor(anchor, baseUrl)) return@mapNotNull null
            val href = normalize(rawHref, baseUrl)
            if (!isDetailUrl(href, baseUrl) || href == baseUrl) return@mapNotNull null
            href to season
        }.distinctBy { it.first }.take(12)
    }

    private fun episodeItems(
        document: Document,
        baseUrl: String,
        defaultSeason: Int?
    ): List<EpisodeItem> {
        return document.select("a[href]").mapNotNull { anchor ->
            val rawHref = anchor.attr("href")
            val anchorIdentity = listOf(
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.text(),
                rawHref,
                anchor.classNames().joinToString(" ")
            ).joinToString(" ")
            val numbers = episodeInfo(anchorIdentity, defaultSeason) ?: return@mapNotNull null
            if (numbers.second !in 1..999 || numbers.first !in 1..99) return@mapNotNull null
            if (isNonContentAnchor(anchor, baseUrl)) return@mapNotNull null

            val href = normalize(rawHref, baseUrl)
            if (!isDetailUrl(href, baseUrl) || href == baseUrl) return@mapNotNull null
            val container = containerFor(anchor)
            val image = anchor.selectFirst("img") ?: container.selectFirst("img")
            val title = sequenceOf(
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.text(),
                image?.attr("alt"),
                href
            ).mapNotNull { neonCleanText(it) }
                .firstOrNull {
                    it.length in 1..180 &&
                        normalizedFilterText(it) !in setOf("izledim", "izlendi", "watched")
                } ?: return@mapNotNull null
            EpisodeItem(
                url = href,
                title = title,
                season = numbers.first,
                episode = numbers.second,
                poster = posterFor(anchor, baseUrl)
            )
        }.distinctBy { it.url }
    }

    private fun cleanSeriesTitle(value: String): String {
        return value.replace(
            Regex("""(?i)[\s\-:|]*(?:s\d{1,2}e\d{1,3}|\d{1,2}\s*\.?\s*sezon\s*\d*\s*\.?\s*b[öo]l[üu]m.*)$"""),
            ""
        ).trim().ifBlank { value }
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalized = normalize(url)
        if (!isValidHttpUrl(normalized) || !neonIsMediaUrl(normalized)) return null

        val title = runCatching {
            URI(normalized).path.orEmpty()
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace(Regex("""[-_]+"""), " ")
                .trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Canlı Yayın"

        val response = newLiveStreamLoadResponse(title, normalized, normalized)
        val document = Document(normalized)
        return neonEnrichResponse(response, document, normalized)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = data.trim()
        if (normalized.isBlank()) return false

        if (neonIsMediaUrl(normalized)) {
            emitDirectLiveLink(normalized, callback)
            return true
        }

        val document = neonCachedDocument(normalized, normalized)
            ?: runCatching {
                app.get(
                    normalized,
                    headers = headers(normalized),
                    timeout = 15_000L,
                ).document
            }.getOrNull()

        val candidates = LinkedHashSet<String>()

        val externalPlayerFrames = document?.let { page ->
            neonExtractIframeUrls(page, normalized)
                .filter { frame ->
                    !isSiteUrl(frame) && !isYouTubePageUrl(frame) && frame.startsWith("http", true)
                }
                .distinct()
        }.orEmpty()

        if (externalPlayerFrames.isNotEmpty()) {
            candidates.addAll(externalPlayerFrames)
        } else {
            candidates.add(normalized)
        }

        val emitted = LinkedHashSet<String>()
        val forwardingCallback: (ExtractorLink) -> Unit = { link ->
            if (emitted.add(link.url)) {
                callback(link)
            }
        }

        var resolverFound = false
        for (candidate in candidates.take(10)) {
            val candidateFound = neonResolveLinks(
                data = candidate,
                sourceName = name,
                subtitleCallback = subtitleCallback,
                callback = forwardingCallback,
                maxDepth = 3,
                referer = normalized,
            )
            resolverFound = resolverFound || candidateFound
            if (emitted.isNotEmpty()) break
        }

        return emitted.isNotEmpty()
    }
}
