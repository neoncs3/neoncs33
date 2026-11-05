package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
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

    private val channelApiUrls = listOf(
        "https://daddylive.app/player/player5.json",
        "https://daddylive.app/player/player10.json",
    )

    private val channelsPerPage = 50

    private val playerUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    private suspend fun parseChannelsFromUrl(apiUrl: String): List<SearchResponse> {
        val body = runCatching {
            app.get(
                apiUrl,
                headers = headers(apiUrl) + mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 20_000L,
                allowRedirects = true,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (body.isBlank()) {
            Log.w(name.uppercase().take(23), "API empty: $apiUrl")
            return emptyList()
        }

        val items = ArrayList<SearchResponse>()
        runCatching {
            val trimmed = body.trim()
            val jsonArray: JSONArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("data")
                        ?: obj.optJSONArray("channels")
                        ?: obj.optJSONArray("list")
                        ?: obj.optJSONArray("items")
                        ?: JSONArray()
                }
                else -> JSONArray()
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val channelName = sequenceOf(
                    obj.optString("name"),
                    obj.optString("channel_name"),
                    obj.optString("title"),
                ).map { it.trim() }
                    .firstOrNull { it.isNotBlank() } ?: continue

                val streamUrl = sequenceOf(
                    obj.optString("url"),
                    obj.optString("stream_url"),
                    obj.optString("link"),
                ).map { it.trim() }
                    .firstOrNull { it.isNotBlank() } ?: continue

                if (!isValidHttpUrl(streamUrl)) continue

                items += newLiveSearchResponse(channelName, streamUrl, TvType.Live) {
                    posterUrl = mainUrl.trimEnd('/') + "/favicon.ico"
                }
            }
        }.onFailure {
            Log.e(name.uppercase().take(23), "Error parsing $apiUrl", it)
        }

        Log.d(name.uppercase().take(23), "API parsed from $apiUrl -> items=${items.size}")
        return items
    }

    private suspend fun loadChannelsFromApis(): List<SearchResponse> {
        val combined = ArrayList<SearchResponse>()
        val seenKeys = HashSet<String>()
        for (apiUrl in channelApiUrls) {
            for (item in parseChannelsFromUrl(apiUrl)) {
                val key = item.name.trim().lowercase() + "|" + item.url.trim()
                if (seenKeys.add(key)) {
                    combined += item
                }
            }
        }
        Log.d(name.uppercase().take(23), "Combined channels total=${combined.size}")
        return combined
    }

    private fun emitDirectLiveLink(
        streamUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val streamHost = runCatching {
            URI(streamUrl).host.orEmpty().lowercase()
        }.getOrDefault("").ifBlank { "daddylive.app" }

        val referer = "https://$streamHost/"

        val outHeaders = mapOf(
            "Referer" to referer,
            "User-Agent" to playerUserAgent,
        )
        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                referer = referer,
                quality = Qualities.Unknown.value,
                headers = outHeaders,
                type = ExtractorLinkType.M3U8,
            )
        )
    }

    // proxyPlayerUrl sayfasındaki HTML'den gerçek canlı akışı çıkarır
    private fun extractStreamFromPlayerHtml(html: String): String? {
        val text = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&#x2F;", "/")
            .replace("&amp;", "&")

        // HLS (.m3u8) adreslerini bul
        val m3u8Regex = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        val candidates = m3u8Regex.findAll(text).map { it.value }.toList()
        if (candidates.isNotEmpty()) {
            // Önce canlı (chunk/stream/live) tercih et, sonra ilk bulunan
            val preferred = candidates.firstOrNull {
                it.contains("chunk", true) ||
                    it.contains("stream", true) ||
                    it.contains("live", true) ||
                    it.contains("play", true)
            } ?: candidates.first()
            return preferred
        }

        // Bazı player'lar m3u8'i JS değişkeninde tutar
        val jsVarPatterns = listOf(
            Regex(
                """(?:src|file|source|stream|url|hls|playlist)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""["']([^"']+\.m3u8[^"']*)["']"""),
        )
        for (p in jsVarPatterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }

        return null
    }

    private suspend fun resolveTvNow247(
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name.uppercase().take(23), "tvnow247 çözümleniyor: $embedUrl")

        val embedResponse = runCatching {
            app.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                ),
                timeout = 15_000L,
            )
        }.getOrNull() ?: return false

        val pageHtml = embedResponse.text
        if (pageHtml.isBlank()) {
            Log.w(name.uppercase().take(23), "tvnow247 embed sayfası boş")
            return false
        }

        val channelId = sequenceOf(
            Regex("""channelId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""channel_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""streamId\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""stream_id\s*[:=]\s*["']?(\d+)["']?""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""/resolve-dlstream/(\d+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""data-(?:channel|stream)-id=["'](\d+)["']""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(\d+)$""").find(embedUrl)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull()

        if (channelId.isNullOrBlank()) {
            Log.w(name.uppercase().take(23), "tvnow247: channelId bulunamadı")
            return false
        }

        val src = sequenceOf(
            Regex("""src\s*[:=]\s*["'](plus|tv\d*|dl|main|alt)["']""").find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""[?&]src=(\w+)""").find(pageHtml)?.groupValues?.getOrNull(1),
            if (embedUrl.contains("-plus-", ignoreCase = true) ||
                embedUrl.contains("/plus-", ignoreCase = true)
            ) "plus" else null,
        ).filterNotNull().firstOrNull() ?: "plus"

        val apiHost = sequenceOf(
            Regex("""["'](https?://chat\.[a-z0-9.-]+)["']""")
                .find(pageHtml)?.groupValues?.getOrNull(1),
            Regex("""(https?://[a-z0-9.-]*cfbu[a-z0-9.-]*)""")
                .find(pageHtml)?.groupValues?.getOrNull(1),
        ).filterNotNull().firstOrNull() ?: "https://chat.cfbu247.sbs"

        val resolveUrl = "${apiHost.trimEnd('/')}/api/resolve-dlstream/$channelId?src=$src"
        val resolveBody = runCatching {
            app.get(
                resolveUrl,
                headers = mapOf(
                    "Referer" to "https://tvnow247.top/",
                    "User-Agent" to playerUserAgent,
                    "Accept" to "application/json, text/plain, */*",
                ),
                timeout = 15_000L,
            ).takeIf { it.isSuccessful }?.text
        }.getOrNull().orEmpty()

        if (resolveBody.isBlank()) {
            Log.w(name.uppercase().take(23), "tvnow247: resolve-dlstream boş yanıt")
            return false
        }

        val json = runCatching { JSONObject(resolveBody) }.getOrNull() ?: return false

        val proxyPlayerUrl = json.optString("proxyPlayerUrl").trim()
            .takeIf { it.isNotBlank() && it != "null" }
        val directM3u8 = json.optString("m3u8").trim()
            .takeIf { it.isNotBlank() && it != "null" }
        val proxyPlaylistUrl = json.optString("proxyPlaylistUrl").trim()
            .takeIf { it.isNotBlank() && it != "null" }
        val streamReferer = json.optString("referer").trim()
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "https://dembed.st/premiumtv/daddy.php?id=$channelId"

        // 1) Önce proxyPlayerUrl'den gerçek canlı m3u8'i çıkarmayı dene
        if (!proxyPlayerUrl.isNullOrBlank()) {
            val playerHtml = runCatching {
                app.get(
                    proxyPlayerUrl,
                    headers = mapOf(
                        "Referer" to "https://tvnow247.top/",
                        "User-Agent" to playerUserAgent,
                        "Accept" to "text/html,application/xhtml+xml,*/*",
                    ),
                    timeout = 15_000L,
                ).takeIf { it.isSuccessful }?.text
            }.getOrNull().orEmpty()

            if (playerHtml.isNotBlank()) {
                val extracted = extractStreamFromPlayerHtml(playerHtml)
                if (!extracted.isNullOrBlank()) {
                    Log.d(name.uppercase().take(23), "tvnow247: gerçek akış bulundu=$extracted")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = extracted,
                            referer = streamReferer,
                            quality = Qualities.Unknown.value,
                            headers = mapOf(
                                "Referer" to streamReferer,
                                "User-Agent" to playerUserAgent,
                                "Accept" to "*/*",
                            ),
                            type = ExtractorLinkType.M3U8,
                        )
                    )
                    return true
                }
            }
            Log.d(name.uppercase().take(23), "tvnow247: proxyPlayerUrl'den akış çıkarılamadı, m3u8'e düşülüyor")
        }

        // 2) Yedek: doğrudan m3u8 / proxyPlaylistUrl
        val fallback = directM3u8 ?: proxyPlaylistUrl
        if (fallback.isNullOrBlank()) {
            Log.w(name.uppercase().take(23), "tvnow247: m3u8 bulunamadı")
            return false
        }

        Log.d(name.uppercase().take(23), "tvnow247: fallback=$fallback referer=$streamReferer")

        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = fallback,
                referer = streamReferer,
                quality = Qualities.Unknown.value,
                headers = mapOf(
                    "Referer" to streamReferer,
                    "User-Agent" to playerUserAgent,
                    "Accept" to "*/*",
                ),
                type = ExtractorLinkType.M3U8,
            )
        )
        return true
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page <= 1) {
            val liveItems = loadChannelsFromApis()
            if (liveItems.isNotEmpty()) {
                val pages = liveItems.chunked(channelsPerPage).mapIndexed { index, chunk ->
                    HomePageList("Canlı Kanallar ${index + 1}", chunk)
                }
                return newHomePageResponse(pages, hasNext = false)
            }
        }

        val base = request.data
        val document = runCatching {
            app.get(base, headers = headers(base), timeout = 12_000L).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = resultsFrom(document, base)
        return newHomePageResponse(request.name, results, hasNext = false)
    }

    // ---- Yardımcı fonksiyonlar ----

    private fun isSiteUrl(url: String): Boolean =
        (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            siteHost(url) == siteHost(mainUrl)

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
            ) return true

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
        ) return true

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
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
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
        if (anchor.classNames().any { it.equals("alphabetical-category-link", ignoreCase = true) }) return true
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
        val urls = ArrayList<String>()
        urls += mainUrl + "/?s=" + encoded
        urls += mainUrl + "/?search=" + encoded
        urls += mainUrl + "/?q=" + encoded
        urls += mainUrl + "/search/" + encoded + "/"
        return urls.distinct()
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

    override suspend fun load(url: String): LoadResponse? {
        val normalized = normalize(url)
        if (!isValidHttpUrl(normalized)) return null

        val document = if (neonIsMediaUrl(normalized)) {
            Document("")
        } else {
            neonCachedDocument(normalized, normalized)
                ?: runCatching {
                    app.get(
                        normalized,
                        headers = headers(normalized),
                        timeout = 12_000L,
                    ).document
                }.getOrNull()
                ?: Document("")
        }

        val title = document.title().takeIf { it.isNotBlank() }
            ?: runCatching {
                URI(normalized).path.orEmpty()
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                    .replace(Regex("""[-_]+"""), " ")
                    .trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Canlı Yayın"

        val response = newLiveStreamLoadResponse(title, normalized, normalized)
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

        if (normalized.contains("tvnow247.top/embed/", ignoreCase = true)) {
            val resolved = resolveTvNow247(normalized, callback)
            if (resolved) return true
            Log.w(name.uppercase().take(23), "tvnow247 çözümlenemedi, genel çözümlemeye düşülüyor")
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

        for (candidate in candidates.take(10)) {
            neonResolveLinks(
                data = candidate,
                sourceName = name,
                subtitleCallback = subtitleCallback,
                callback = forwardingCallback,
                maxDepth = 3,
                referer = normalized,
            )
            if (emitted.isNotEmpty()) break
        }

        return emitted.isNotEmpty()
    }
}
