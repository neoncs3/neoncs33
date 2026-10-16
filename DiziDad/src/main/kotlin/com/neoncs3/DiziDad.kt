package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class DiziDad : MainAPI() {

    override var mainUrl = "https://www.dizidad.com"
    override var name = "DiziDAD"

    // DiziDAD medyası hotlink kontrolü yaparsa CloudStream görsel isteğine site referer'ını ver.
    private val posterRequestHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    /**
     * Episode sınıfında posterHeaders yok; bu yüzden bölüm afişleri Referer'sız istenir ve
     * site hotlink engeli uygularsa görünmez. Görseli wsrv.nl proxy'si üzerinden vererek
     * Referer gereksinimini aşıyoruz.
     */
    private fun episodePosterUrl(url: String?): String? {
        val original = url?.trim()?.takeIf { isUsablePoster(it) } ?: return null
        val abs = absUrl(original)
        return "https://wsrv.nl/?url=" + URLEncoder.encode(abs, "UTF-8") + "&w=400&output=jpg"
    }

    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/" to "Son Eklenen Bölümler",
        "$mainUrl/yerli-dizi-izle/" to "Yerli Diziler",
        "$mainUrl/yabanci-dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/turkce-dublaj-diziler-hd/" to "Dublajlı Diziler",
        "$mainUrl/netflix-dizileri-izle/" to "Netflix Dizileri",
        "$mainUrl/tum-filmler/" to "Filmler",
        "$mainUrl/yabanci-filmler-izle/" to "Yabancı Filmler",
        "$mainUrl/yerli-filmler/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj-filmler/" to "Dublajlı Filmler",
        "$mainUrl/turkce-altyazili-filmler/" to "Altyazılı Filmler"
    )

    // ------------------------------------------------------------------ yardımcılar

    private val episodeRegex = Regex("-\\d+-bolum")

    private fun isEpisode(u: String) = episodeRegex.containsMatchIn(u)

    private fun isSeries(u: String) = u.contains("/diziler/")

    private fun norm(s: String): String = s.lowercase(Locale("tr"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanTitle(s: String): String = s
        .replace(Regex("\\s*\\|\\s*DiziDAD.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+izle(\\s+izle)*\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun absUrl(v: String): String = when {
        v.startsWith("//") -> "https:$v"
        v.startsWith("http") -> v
        else -> fixUrl(v)
    }

    private fun posterSlug(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var slug = url.substringBefore("?").substringBefore("#").trimEnd('/')
            .substringAfterLast('/').lowercase(Locale.ROOT)
        slug = slug
            .replace(Regex("(?i)-\\d+-sezon-.*$"), "")
            .replace(Regex("(?i)-\\d+-bolum.*$"), "")
            .replace(Regex("(?i)-(?:turkce-)?(?:dublaj|altyazili)-izle.*$"), "")
            .replace(Regex("(?i)-izle.*$"), "")
        return slug
    }

    private fun isUsablePoster(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http", true)) return false
        return !Regex(
            "(?i)(/themes/|placeholder|no[-_]?image|blank\\.(gif|png|jpe?g|webp)|logo|avatar|spacer|transparent|pixel|default[-_]?poster)"
        ).containsMatchIn(url)
    }

    /** HTML/script içinde geçen WordPress afiş URL'lerini bulur ve başlık slug'ını tercih eder. */
    private fun posterFromMarkup(markup: String, targetUrl: String? = null): String? {
        val normalized = markup.replace("\\/", "/").replace("&amp;", "&")
        val uploadPoster = Regex(
            """(?i)(?:https?:)?//[^"'<>\s]+?/wp-content/uploads/[^"'<>\s]+?\.(?:jpe?g|png|webp)|/wp-content/uploads/[^"'<>\s]+?\.(?:jpe?g|png|webp)"""
        )
        val slug = posterSlug(targetUrl)
        val candidates = uploadPoster.findAll(normalized)
            .map { absUrl(it.value.trim().trim('"', '\'')) }
            .filter { isUsablePoster(it) }
            .distinct()
            .toList()
        return candidates.firstOrNull {
            slug.isNotBlank() && it.substringBefore("?").contains(slug, ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    /** WordPress temalarının lazy-load görsellerini aynı öncelikle çözer. */
    private fun Element.posterOfElement(): String? {
        val raw = attr("data-src")
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("data-image") }
            .ifBlank { attr("data-poster") }
            .ifBlank { attr("data-lazy-srcset") }
            .ifBlank { attr("data-srcset") }
            .ifBlank { attr("srcset") }
            .ifBlank { attr("src") }
        val candidate = Regex("""https?://[^\s,]+""", RegexOption.IGNORE_CASE)
            .find(raw)?.value?.trimEnd(',')
            ?: raw.split(',').firstOrNull()?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
            ?: return null
        return absUrl(candidate).takeIf { isUsablePoster(it) }
    }

    /** Karttaki gerçek afişi bulur (yer tutucu ve tema ikonlarını atlar). */
    private fun Element.posterUrl(): String? {
        val invalidPoster = Regex(
            "(?i)(/themes/|placeholder|no[-_]?image|blank\\.(gif|png|jpe?g|webp)|logo|avatar|spacer|transparent|pixel|default[-_]?poster)"
        )
        val nodes = listOf(this) + select("*")
        val attributes = listOf(
            "data-poster", "data-poster-url", "data-bg", "data-background-image",
            "data-background", "data-original", "data-lazy-src", "data-src",
            "data-url", "data-image", "data-lazy-background", "data-srcset",
            "data-lazy-srcset", "srcset", "style", "poster", "src"
        )
        for (attr in attributes) {
            for (node in nodes) {
                val raw = node.attr(attr).trim()
                if (raw.isEmpty()) continue
                val values = when {
                    attr == "style" -> Regex(
                        """(?i)url\(\s*['"]?([^)'"]+)"""
                    ).findAll(raw).map { it.groupValues[1].trim() }.toList()
                    attr.contains("srcset") -> listOf(
                        raw.substringBefore(",").trim().substringBefore(" ").trim()
                    )
                    else -> listOf(raw)
                }
                for (candidate in values) {
                    val value = candidate.trim().removeSurrounding("\"").removeSurrounding("'")
                    if (value.isEmpty() || value.startsWith("data:") ||
                        value.startsWith("javascript:", true) || invalidPoster.containsMatchIn(value)
                    ) continue
                    val resolved = fixUrlNull(value)
                    if (!resolved.isNullOrBlank() &&
                        (resolved.startsWith("https://") || resolved.startsWith("http://")) &&
                        !invalidPoster.containsMatchIn(resolved)
                    ) {
                        return resolved
                    }
                }
            }
        }
        return null
    }

    /** Açık kart seçicilerini önceliklendirir. */
    private fun Element.diziMomPoster(targetUrl: String? = null): String? {
        val selectors = listOf(
            "div.cat-img img",
            "div.category_image img",
            "div.episode-box a img",
            "div.episode-name img",
            "div.single-item a img",
            "a img",
            "img"
        )
        val candidates = LinkedHashSet<String>()
        for (selector in selectors) {
            val images = if (selector == "img" && tagName().equals("img", ignoreCase = true)) {
                listOf(this)
            } else {
                select(selector)
            }
            images.forEach { image -> image.posterOfElement()?.let { candidates.add(it) } }
        }
        val slug = posterSlug(targetUrl)
        return candidates.firstOrNull {
            slug.isNotBlank() && posterSlug(it).contains(slug, ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    /** Afiş, metin bağlantısının içinde değilse kart kapsayıcısına kadar çıkıp görseli oradan alır. */
    private fun Element.posterFromCardAncestors(targetUrl: String? = null): String? {
        diziMomPoster(targetUrl)?.let { return it }
        select("img[data-src], img[data-lazy-src], img[data-original], img[data-lazyloaded], img[src]")
            .firstNotNullOfOrNull { it.posterOfElement() }
            ?.let { return it }
        if (tagName().equals("img", ignoreCase = true)) {
            posterOfElement()?.let { return it }
        }
        val slug = posterSlug(targetUrl)
        var current: Element? = parent()
        var depth = 0
        while (current != null && depth < 9) {
            val tag = current.tagName().lowercase(Locale.ROOT)
            if (tag in setOf("header", "nav", "footer", "aside", "body", "html")) break
            val marker = (current.id() + " " + current.classNames().joinToString(" ")).lowercase(Locale.ROOT)
            if (Regex("\\b(site[-_]?header|site[-_]?footer|navbar|navigation|main[-_]?menu|mobile[-_]?menu|side[-_]?bar|sidebar|widget|login|register|alphabet|social|user[-_]?menu)\\b")
                    .containsMatchIn(marker)
            ) break
            val looksLikeCard = Regex("\\b(card|item|post|entry|movie|film|series|dizi|content|thumbnail|list|archive|episode|bolum|single-item|cat-img|episode-box)\\b")
                .containsMatchIn(marker)
            val images = current.select("img[data-src], img[data-lazy-src], img[data-original], img[data-lazyloaded], img[src]")
            if (images.isNotEmpty() && images.size <= 12 && (images.size <= 3 || looksLikeCard)) {
                val usable = images.mapNotNull { image ->
                    image.posterOfElement()?.let { poster -> image to poster }
                }
                val preferred = usable.firstOrNull { (image, poster) ->
                    (slug.isNotBlank() && poster.contains(slug, ignoreCase = true)) ||
                        norm(image.attr("alt")).let { alt -> slug.isNotBlank() && alt.contains(norm(slug)) }
                } ?: usable.firstOrNull { (_, poster) ->
                    poster.contains("/wp-content/uploads/", ignoreCase = true)
                } ?: usable.firstOrNull()
                preferred?.second?.let { return it }
                val background = current.posterUrl()
                if (isUsablePoster(background)) return background
            }
            current = current.parent()
            depth++
        }
        return null
    }

    /** Sayfadaki img'leri alt metni ve URL slug'ı ile eşleştirerek ilgili kartın afişini bulur. */
    private fun Document.posterForTarget(targetUrl: String, titleHint: String? = null): String? {
        val targetSlug = posterSlug(targetUrl).substringBeforeLast('.', posterSlug(targetUrl))
        val targetTitle = norm(cleanTitle(titleHint.orEmpty()))
        val targetTokens = (
            targetTitle.split(" ") +
                norm(targetSlug.replace('-', ' ')).split(" ") +
                norm(targetUrl.substringBefore("?").substringBefore("#").substringAfterLast('/').replace('-', ' ')).split(" ")
            )
            .filter { it.length >= 3 && it !in setOf("izle", "sezon", "bolum", "dizi", "film", "turkce", "dublaj", "altyazili") }
            .distinct()
        val candidates = select(
            "img[data-src], img[data-lazy-src], img[data-original], img[data-image], img[data-poster], img[data-lazyloaded], img[src]"
        ).mapNotNull { image ->
            val poster = image.posterOfElement() ?: return@mapNotNull null
            val alt = norm(cleanTitle(image.attr("alt")))
            val file = norm(poster.substringBefore("?").substringBefore("#").substringAfterLast('/').substringBeforeLast('.').replace('-', ' '))
            var score = 0
            targetTokens.forEach { token ->
                if (alt.split(" ").contains(token)) score += 6
                if (file.split(" ").contains(token)) score += 4
                if (alt.contains(token)) score += 2
                if (file.contains(token)) score += 1
            }
            Triple(poster, score, image)
        }.distinctBy { it.first }
        return candidates
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun isContentCardUrl(url: String): Boolean {
        val path = url.removePrefix(mainUrl).lowercase(Locale.ROOT)
        return isEpisode(url) || isSeries(url) ||
            path.contains("izle") || path.contains("filmler") || path.contains("film-")
    }

    private fun contentUrlKey(url: String): String =
        url.substringBefore("?").substringBefore("#").trimEnd('/').lowercase(Locale.ROOT)

    private val posterBearingSelector =
        "img, [data-poster], [data-poster-url], [data-image], [data-bg], [data-background], [data-background-image], [data-src], [data-original], [data-lazy-src], [data-lazy-background], [data-srcset], [data-lazy-srcset], [srcset], [style*=background]"

    // GEÇİCİ TANI: true iken kart adının başına afiş dosya adı (yoksa NULL) yazılır.
    // Sorun çözülünce false yap.
    private val debugPoster = true

    private fun makeCard(rawTitle: String, url: String, poster: String?): SearchResponse {
        val title = if (debugPoster) {
            "[" + (poster?.substringBefore("?")?.substringAfterLast('/')?.take(22) ?: "NULL") + "] " + rawTitle
        } else rawTitle
        return makeCardInternal(title, url, poster)
    }

    private fun makeCardInternal(title: String, url: String, poster: String?): SearchResponse =
        if (isSeries(url) || isEpisode(url)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = posterRequestHeaders
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = posterRequestHeaders
            }
        }

    /** Gerçek img öğesinden başlayıp aynı karttaki içerik bağlantısını bulur. */
    private fun Element.contentAnchorForImage(imageAlt: String): Element? {
        val alt = norm(cleanTitle(imageAlt))
        val tokens = alt.split(" ")
            .filter { it.length >= 3 && it !in setOf("izle", "sezon", "bolum", "dizi", "film", "turkce", "dublaj", "altyazili") }
            .distinct()
        var current: Element? = parent()
        var depth = 0
        while (current != null && depth < 7) {
            val tag = current.tagName().lowercase(Locale.ROOT)
            if (tag in setOf("header", "nav", "footer", "aside", "body", "html")) break
            val marker = (current.id() + " " + current.classNames().joinToString(" ")).lowercase(Locale.ROOT)
            if (Regex("\\b(site[-_]?header|site[-_]?footer|navbar|navigation|main[-_]?menu|mobile[-_]?menu|side[-_]?bar|sidebar|widget|login|register|alphabet|social|user[-_]?menu)\\b").containsMatchIn(marker)) break
            val links = current.select("a[href]").filter { link ->
                val href = link.attr("href").trim()
                href.isNotBlank() && !href.startsWith("#") && !href.startsWith("javascript") &&
                    absUrl(href).startsWith(mainUrl) && isContentCardUrl(absUrl(href))
            }
            val scored = links.mapNotNull { link ->
                val href = absUrl(link.attr("href"))
                val linkText = norm(cleanTitle(link.text().ifBlank { link.attr("title") }))
                val slug = norm(posterSlug(href).replace('-', ' '))
                var score = 0
                tokens.forEach { token ->
                    if (linkText.split(" ").contains(token)) score += 8
                    else if (linkText.contains(token)) score += 4
                    if (slug.split(" ").contains(token)) score += 6
                    else if (slug.contains(token)) score += 3
                }
                if (score > 0) link to score else null
            }.sortedByDescending { it.second }
            if (scored.isNotEmpty()) return scored.first().first
            if (alt.isBlank() && links.size == 1) return links.first()
            current = current.parent()
            depth++
        }
        return null
    }

    /**
     * Öznitelik adı ne olursa olsun (data-src, data-litespeed-src, srcset...) kartın ham HTML'inde
     * geçen wp-content/uploads görsel adresini bulur. Yalnızca tek içerik bağlantısı barındıran
     * küçük kapsayıcılarda arar ki komşu kartın afişi alınmasın.
     */
    private fun posterNearAnchor(anchor: Element, targetUrl: String): String? {
        var current: Element? = anchor
        var depth = 0
        while (current != null && depth < 5) {
            val tag = current.tagName().lowercase(Locale.ROOT)
            if (tag == "body" || tag == "html") return null
            val keys = current.select("a[href]")
                .map { absUrl(it.attr("href").trim()) }
                .filter { it.startsWith(mainUrl) && isContentCardUrl(it) }
                .map { contentUrlKey(it) }
                .distinct()
            if (keys.size > 1) return null
            posterFromMarkup(current.outerHtml(), targetUrl)?.let { return it }
            current = current.parent()
            depth++
        }
        return null
    }

    private fun Document.cards(maxCards: Int? = null): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        val anchors = select("a[href]")

        // Önce her gerçek poster img'sini kendi başlık/izleme bağlantısına bağla.
        for (image in select("img[data-src], img[data-lazy-src], img[data-original], img[data-image], img[data-poster], img[data-lazyloaded], img[src]")) {
            if (maxCards != null && out.size >= maxCards) break
            val poster = image.posterOfElement() ?: continue
            val alt = image.attr("alt").ifBlank { image.attr("title") }
            val link = image.contentAnchorForImage(alt) ?: continue
            val href = link.attr("href").trim()
            val url = absUrl(href)
            if (!url.startsWith(mainUrl) || !isContentCardUrl(url)) continue
            val title = cleanTitle(link.text().ifBlank { link.attr("title").ifBlank { alt } })
            if (title.isBlank()) continue
            if (!out.containsKey(url)) out[url] = makeCard(title, url, poster)
        }

        if (maxCards != null && out.size >= maxCards) return out.values.take(maxCards)

        val posterByUrl = LinkedHashMap<String, String>()
        for (link in anchors) {
            if (maxCards != null && out.size >= maxCards) break
            val href = link.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
            val url = absUrl(href)
            if (!url.startsWith(mainUrl) || !isContentCardUrl(url)) continue
            val hasImageMarker = link.selectFirst(posterBearingSelector) != null ||
                listOf("data-poster", "data-poster-url", "data-image", "data-bg",
                    "data-background", "data-background-image", "data-src", "data-original",
                    "data-lazy-src", "data-srcset", "data-lazy-srcset", "srcset", "style")
                    .any { link.hasAttr(it) }
            if (!hasImageMarker) continue
            val poster = link.posterFromCardAncestors(url)
                ?: posterForTarget(url, link.selectFirst("img[alt]")?.attr("alt") ?: link.text())
                ?: posterNearAnchor(link, url)
            if (!poster.isNullOrBlank()) posterByUrl.putIfAbsent(contentUrlKey(url), poster)
        }

        for (a in anchors) {
            if (maxCards != null && out.size >= maxCards) break
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
            val url = absUrl(href)
            if (!url.startsWith(mainUrl) || !isContentCardUrl(url)) continue
            val path = url.removePrefix(mainUrl).trim('/')
            if (path.isEmpty() || path.startsWith("wp-") || path.contains("page/")) continue
            if (path.contains("yorum") || path.contains("comment")) continue
            val titleHint = a.selectFirst("img[alt]")?.attr("alt") ?: a.text()
            val poster = posterByUrl[contentUrlKey(url)]
                ?: a.posterFromCardAncestors(url)
                ?: posterForTarget(url, titleHint)
                ?: posterNearAnchor(a, url)
            val hasImage = !poster.isNullOrBlank() || a.selectFirst(posterBearingSelector) != null ||
                listOf("data-poster", "data-poster-url", "data-image", "data-bg",
                    "data-background", "data-background-image", "data-src", "data-original",
                    "data-lazy-src", "data-srcset", "data-lazy-srcset", "srcset", "style")
                    .any { a.hasAttr(it) }
            if (!hasImage) continue
            val title = cleanTitle(
                a.selectFirst("img[alt]")?.attr("alt")?.ifBlank { null }
                    ?: a.selectFirst("[title]")?.attr("title")?.ifBlank { null }
                    ?: a.text()
            )
            if (title.isBlank()) continue
            if (!out.containsKey(url)) out[url] = makeCard(title, url, poster)
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val cardLimit = if (request.data.contains("/tum-bolumler/")) 20 else 30
        val items = doc.cards(cardLimit)
        android.util.Log.d(
            "DiziDad",
            "MAIN ${request.name}: items=${items.size} withPoster=${items.count { !it.posterUrl.isNullOrBlank() }} first=${items.firstOrNull()?.posterUrl}"
        )
        val hasNext = doc.select("a[href*='/page/${page + 1}/']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext)
    }

    // ------------------------------------------------------------------ arama

    /** WordPress REST araması (siteye göre kapalı olabilir; o zaman ?s= denenir). */
    private suspend fun restSearch(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = app.get(
            "$mainUrl/wp-json/wp/v2/search?search=$q&per_page=30&_fields=title,url"
        ).text
        val arr = org.json.JSONArray(text)
        val found = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url")
            if (!url.startsWith(mainUrl) || isEpisode(url)) continue
            val title = cleanTitle(Jsoup.parse(o.optString("title")).text())
            if (title.isBlank()) continue
            found.add(title to url)
        }
        // REST sonucu afiş vermez; her sonucun sayfasından afişi çek (en fazla 12).
        return found.distinctBy { it.second }.take(12).amap { (title, url) ->
            val poster = runCatching { posterFromPage(url) }.getOrNull()
            makeCard(title, url, poster)
        }
    }

    private suspend fun posterFromPage(url: String): String? {
        val d = app.get(url).document
        return d.posterForTarget(url, d.selectFirst("h1")?.text())
            ?: d.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { isUsablePoster(it) }
                ?.let { absUrl(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val nq = norm(query)
        val htmlResults = runCatching { app.get("$mainUrl/?s=$q").document.cards() }
            .getOrDefault(emptyList())
            .filter { norm(it.name).contains(nq) }
        if (htmlResults.isNotEmpty()) return htmlResults
        return runCatching { restSearch(query) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ yük (dizi / film)

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = fixUrl(url)

        // Bölüm adresi verildiyse dizinin sayfasına geç.
        if (isEpisode(pageUrl) && !isSeries(pageUrl)) {
            val epDoc = app.get(pageUrl).document
            val link = epDoc.selectFirst("a[href*=/diziler/]")?.attr("href")
            if (link.isNullOrBlank()) return null
            pageUrl = absUrl(link)
        }

        val doc = app.get(pageUrl).document
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content") ?: doc.title()
        )
        val bodyText = doc.text()

        // Önce görseli kendi başlık/slug'ıyla eşleştir; OpenGraph son çare.
        var poster = doc.posterForTarget(pageUrl, title)
            ?: doc.selectFirst("div.category_image img")?.posterOfElement()
            ?: doc.selectFirst("div.cat-img img")?.posterOfElement()
            ?: doc.selectFirst("div.single-item img")?.posterOfElement()
            ?: doc.select("img[alt], img[data-src], img[data-lazy-src], img[data-original], img[src]")
                .firstNotNullOfOrNull { image ->
                    image.posterOfElement()?.takeIf {
                        val alt = norm(cleanTitle(image.attr("alt")))
                        val titleKey = norm(cleanTitle(title))
                        titleKey.isNotBlank() && alt.isNotBlank() &&
                            titleKey.split(" ").any { token ->
                                token.length >= 3 && alt.split(" ").contains(token)
                            }
                    }
                }
            ?: doc.selectFirst(
                "meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]"
            )?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.contains("logo", true) }
                ?.let { absUrl(it) }

        if (poster.isNullOrBlank() || !isUsablePoster(poster)) {
            poster = doc.select("img").firstNotNullOfOrNull { it.posterOfElement() }
                ?: posterFromMarkup(doc.html(), pageUrl)
                ?: doc.posterUrl()
        }

        val rawDesc = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val plot = when {
            rawDesc.contains("sizlerle, ") -> rawDesc.substringAfter("sizlerle, ")
            rawDesc.contains("sizlerl, ") -> rawDesc.substringAfter("sizlerl, ")
            else -> rawDesc
        }.trim().ifBlank { null }

        val year = Regex("Yapım Yılı\\s*:\\s*(\\d{4})").find(bodyText)
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = Regex("Oyuncular\\s*:\\s*(.+?)\\s*(?:IMDB|Tür\\s*:|$)").find(bodyText)
            ?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val tags = doc.select("a[href*=tur=]").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val trailer = Regex("https://www\\.youtube\\.com/embed/[A-Za-z0-9_-]+")
            .find(doc.html())?.value

        // Dizi sayfası değilse film.
        if (!isSeries(pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = posterRequestHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                addTrailer(trailer)
            }
        }

        // Bölümler: dizi sayfasındaki "1.Sezon 3.Bölüm" bağlantıları.
        val seen = HashSet<String>()
        val raw = ArrayList<Triple<Int, Int, Pair<String, String>>>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || !isEpisode(href) || isSeries(href)) continue
            val epUrl = absUrl(href)
            if (!epUrl.startsWith(mainUrl)) continue
            val text = a.text().trim()
            if (!text.contains("Bölüm", ignoreCase = true)) continue
            if (!seen.add(epUrl)) continue

            val season = Regex("(\\d+)\\s*\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-(\\d+)-sezon-").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val ep = Regex("(\\d+)\\s*\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-(\\d+)-bolum").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            raw.add(Triple(season, ep, epUrl to text))
        }

        // Dizi sayfasındaki bölüm satırlarında görsel yok; bütün bölümlere dizinin afişini ver.
        val seriesPosterFallback = poster?.takeIf { isUsablePoster(it) }
            ?: posterFromMarkup(doc.html(), pageUrl)
            ?: doc.select("img").firstNotNullOfOrNull { it.posterOfElement() }

        val fallbackEpisodePoster = episodePosterUrl(seriesPosterFallback)
        android.util.Log.d(
            "DiziDad",
            "LOAD poster=$poster episodePoster=$fallbackEpisodePoster eps=${raw.size}"
        )

        val episodes = raw
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (season, ep, pair) ->
                newEpisode(pair.first) {
                    this.name = pair.second
                    this.season = season
                    this.episode = ep
                    this.posterUrl = fallbackEpisodePoster
                }
            }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
            this.posterHeaders = posterRequestHeaders
            this.plot = plot
            this.year = year
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    // ------------------------------------------------------------------ video bağlantıları

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\)]+?\.m3u8[^\s"'<>\\)]*""")
    private val mp4Regex = Regex("""https?://[^\s"'<>\\)]+?\.mp4[^\s"'<>\\)]*""")
    private val hdPlayerBase = "https://hdplayersystem.com"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = fixUrl(data)
        val res = app.get(pageUrl, referer = "$mainUrl/")
        val html = res.text.replace("\\/", "/")
        val doc = res.document

        val candidates = LinkedHashSet<String>()
        for (f in doc.select("iframe")) {
            for (attr in listOf("src", "data-src", "data-lazy-src", "data-litespeed-src", "data-lazy")) {
                val v = f.attr(attr).trim()
                if (v.isNotEmpty() && !v.startsWith("about:") && !v.startsWith("data:")) {
                    candidates.add(absUrl(v))
                }
            }
        }
        Regex("""https?://(?:www\.)?hdplayersystem\.com/[^\s"'<>\\]+""")
            .findAll(html).forEach { candidates.add(it.value) }

        var found = false
        for (c in candidates) {
            val ok = if (c.contains("hdplayersystem.com")) {
                hdPlayer(c, pageUrl, subtitleCallback, callback)
            } else {
                runCatching { loadExtractor(c, pageUrl, subtitleCallback, callback) }
                    .getOrDefault(false)
            }
            if (ok) found = true
        }
        return found
    }

    /**
     * hdplayersystem.com/video/<id>: sayfa kendisi videoyu bir POST isteğiyle alır
     * (player/index.php?data=<id>&do=getVideo -> {"securedLink": "...m3u8"}).
     */
    private suspend fun hdPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = url.substringAfter("/video/", "")
            .ifBlank { url.substringAfter("data=", "") }
            .substringBefore("?").substringBefore("&").substringBefore("#").trim('/')
        if (id.isBlank()) return false
        var found = false

        val embedText = runCatching {
            app.get(url, referer = referer).text.replace("\\/", "/")
        }.getOrDefault("")

        Regex("""playerjsSubtitle\s*=\s*["']([^"']+)["']""").find(embedText)?.groupValues?.get(1)
            ?.split(",")?.forEach { part ->
                val lang = part.substringAfter("[", "").substringBefore("]", "").ifBlank { "Türkçe" }
                val link = part.substringAfter("]", part).trim()
                if (link.startsWith("http")) subtitleCallback(SubtitleFile(lang, link))
            }

        val body = runCatching {
            app.post(
                "$hdPlayerBase/player/index.php?data=$id&do=getVideo",
                data = mapOf("hash" to id, "r" to referer),
                referer = url,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to hdPlayerBase
                )
            ).text.replace("\\/", "/")
        }.getOrDefault("")

        val links = LinkedHashSet<String>()
        for (key in listOf("securedLink", "videoSource")) {
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }?.let { links.add(it) }
        }
        m3u8Regex.findAll(body).forEach { links.add(it.value) }
        if (links.isEmpty()) m3u8Regex.findAll(embedText).forEach { links.add(it.value) }

        for (l in links) {
            M3u8Helper.generateM3u8(name, l, url, null, mapOf("Origin" to hdPlayerBase))
                .forEach(callback)
            found = true
        }

        if (links.isEmpty()) {
            for (m in mp4Regex.findAll(body + "\n" + embedText).map { it.value }.distinct()) {
                callback(
                    newExtractorLink(name, name, m, ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
