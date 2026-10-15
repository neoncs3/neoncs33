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
        "$mainUrl/anime-izle/" to "Animeler",
        "$mainUrl/kore-dizileri-izle-hd/" to "Kore Dizileri",
        "$mainUrl/full-hd-hint-dizileri-izle/" to "Hint Dizileri",
        "$mainUrl/pakistan-dizileri-izle/" to "Pakistan Dizileri",
        "$mainUrl/tv-programlari-izle/" to "Tv Programları",
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

    /** HTML içine gömülmüş WordPress afiş URL'lerini doğrudan yakalar. */
    private fun posterFromMarkup(markup: String): String? {
        val normalized = markup
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val uploadPoster = Regex(
            """(?i)(?:https?:)?//[^"'<>\s]+?/wp-content/uploads/[^"'<>\s]+?\.(?:jpe?g|png|webp)|/wp-content/uploads/[^"'<>\s]+?\.(?:jpe?g|png|webp)"""
        )
        return uploadPoster.findAll(normalized)
            .map { absUrl(it.value.trim().trim('"', '\'')) }
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** Karttaki gerçek afişi bulur (yer tutucu ve tema ikonlarını atlar; dublaj ikonu rozeti gösterilmez). */
    private fun Element.posterUrl(): String? {
        // DiziDAD kartları hem img/src lazy-load hem de CSS background-image/data-bg kullanabiliyor.
        val invalidPoster = Regex(
            "(?i)(/themes/|placeholder|no[-_]?image|blank\\.(gif|png|jpe?g|webp)|logo|avatar|spacer|transparent|pixel|default[-_]?poster)"
        )
        val nodes = listOf(this) + select("*")
        // Alan türüne göre tara: afişin data-* / background alanları, rozetlerin src alanından önce gelir.
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

    private fun makeCard(title: String, url: String, poster: String?): SearchResponse =
        // Afişte rozet/buton (dublaj, kalite, IMDb vb.) gösterilmiyor; sadece başlık ve görsel.
        if (isSeries(url) || isEpisode(url)) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        }

    private fun Document.cards(): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (a in select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript")) continue
            val url = absUrl(href)
            if (!url.startsWith(mainUrl)) continue
            val path = url.removePrefix(mainUrl).trim('/')
            if (path.isEmpty() || path.startsWith("wp-") || path.contains("page/")) continue
            if (path.contains("yorum") || path.contains("comment")) continue

            // Önce bağlantının kendisindeki, sonra yalnızca kartı çevreleyen kapsayıcılardaki afişi ara.
            // Afiş kapsayıcıda background/data-bg ise a:has(img) filtresi bu bölümü kaçırırdı.
            val poster = a.posterUrl()
                ?: a.parent()?.posterUrl()
                ?: a.parent()?.parent()?.posterUrl()
                ?: a.parent()?.parent()?.parent()?.posterUrl()

            val hasImage = poster != null ||
                a.selectFirst(
                    "img, [data-poster], [data-poster-url], [data-image], [data-bg], [data-background], [data-background-image], [data-src], [data-original], [data-lazy-src], [style*=background]"
                ) != null ||
                listOf(
                    "data-poster", "data-poster-url", "data-image", "data-bg",
                    "data-background", "data-background-image", "data-src", "data-original",
                    "data-lazy-src", "srcset", "style"
                ).any { a.hasAttr(it) }
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
        val items = doc.cards()
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
        val out = ArrayList<SearchResponse>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url")
            if (!url.startsWith(mainUrl) || isEpisode(url)) continue
            val title = cleanTitle(Jsoup.parse(o.optString("title")).text())
            if (title.isBlank()) continue
            out.add(makeCard(title, url, null))
        }
        return out.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val fromRest = runCatching { restSearch(query) }.getOrDefault(emptyList())
        if (fromRest.isNotEmpty()) return fromRest
        val q = URLEncoder.encode(query, "UTF-8")
        val nq = norm(query)
        return runCatching { app.get("$mainUrl/?s=$q").document.cards() }
            .getOrDefault(emptyList())
            .filter { norm(it.name).contains(nq) }
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

        var poster = doc.selectFirst(
            "meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]"
        )?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.contains("logo", true) }
            ?.let { absUrl(it) }
        if (poster.isNullOrBlank()) {
            // Örneğin /wp-content/uploads/2026/08/our-sticky-love.jpg biçimindeki afiş,
            // bazen img özniteliğinde değil HTML/script verisinin içinde yer alır.
            poster = posterFromMarkup(doc.html()) ?: doc.posterUrl()
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
                this.plot = plot
                this.year = year
                this.tags = tags
                addTrailer(trailer)
            }
        }

        // Bölümler: dizi sayfasındaki "1.Sezon 3.Bölüm" bağlantıları.
        val seen = HashSet<String>()
        val episodePosters = HashMap<String, String?>()
        val raw = ArrayList<Triple<Int, Int, Pair<String, String>>>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || !isEpisode(href) || isSeries(href)) continue
            val epUrl = absUrl(href)
            if (!epUrl.startsWith(mainUrl)) continue
            val text = a.text().trim()
            if (!text.contains("Bölüm", ignoreCase = true)) continue
            if (!seen.add(epUrl)) continue
            episodePosters[epUrl] = a.posterUrl()
                ?: a.parent()?.posterUrl()
                ?: a.parent()?.parent()?.posterUrl()
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
        val episodes = raw
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (season, ep, pair) ->
                newEpisode(pair.first) {
                    this.name = pair.second
                    this.season = season
                    this.episode = ep
                    this.posterUrl = episodePosters[pair.first]?.let { fixUrlNull(it) }
                        ?: fixUrlNull(poster ?: posterFromMarkup(doc.html()))
                }
            }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(poster)
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

        // Altyazılar ve (varsa) sayfaya gömülü akışlar için gömme sayfasını da oku.
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
