package com.neoncs3

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Tizam sağlayıcısı.
 *
 * Site yapısı (amu.tizam.org / abv.tizam.org):
 *  - Liste sayfaları  : a.item__cover[href] kartları, sayfalama ?p=N
 *  - Arama            : /search-results/?search_string=...   (&p=N)
 *  - Detay sayfası    : h1 başlık, video doğrudan .mp4 (ör. video4.tizam.cc/films/xxx.mp4)
 *
 * NOT: Site alan adını sık değiştirir (amu / abv / tizam.video ...).
 * Çalışmazsa sadece aşağıdaki mainUrl satırını güncelle.
 */
class Tizam : MainAPI() {
    override var mainUrl = "https://amu.tizam.org"
    override var name = "Tizam"
    override var lang = "ru"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Safari/537.36"
        private const val TITLE_SELECTOR = ".item__title, .item__name, h2, h3, h4"
        private val POSTER_ATTRS = listOf("data-original", "data-lazy-src", "data-src", "src")
        private val MEDIA_REGEX =
            Regex("""https?:\\?/\\?/[^"'\s<>()\\]+(?:\\?/[^"'\s<>()\\]+)*\.(?:mp4|m3u8)[^"'\s<>()\\]*""")
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val DURATION_REGEX = Regex("""Продолжительность:\s*(\d+):(\d+)(?::(\d+))?""")
    }

    private val reqHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "/poslednie_postupleniya/" to "Последние поступления",
        "/populyarnye_porno_filmy/" to "Популярные",
        "/fil_my_dlya_vzroslyh/s_russkim_perevodom/" to "С русским переводом",
        "/fil_my_dlya_vzroslyh/polnometrazhnye/" to "Полнометражные с сюжетом",
        "/fil_my_dlya_vzroslyh/all_sex/" to "Фильмы xxx",
        "/fil_my_dlya_vzroslyh/russkoe_porno/" to "Русские",
        "/fil_my_dlya_vzroslyh/nemeckie_pornofil_my/" to "Немецкие",
        "/fil_my_dlya_vzroslyh/italyan_porn/" to "Итальянские",
        "/fil_my_dlya_vzroslyh/aziatki/" to "Азиатские",
        "/fil_my_dlya_vzroslyh/temnokozhie/" to "Темнокожие",
        "/fil_my_dlya_vzroslyh/bol_shaya_grud/" to "Большие сиськи",
        "/fil_my_dlya_vzroslyh/zhenskaya_masturbaciya/" to "Соло девушки",
        "/cinema/erotika/" to "Эротика",
        "/korotkometrazhnye/" to "Ролики"
    )

    // ---------------------------------------------------------------- Ana sayfa

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = withPage(mainUrl + request.data, page)
        val doc = app.get(url, headers = reqHeaders).document
        val items = parseCards(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---------------------------------------------------------------- Arama

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val results = ArrayList<SearchResponse>()
        for (page in 1..2) {
            val url = withPage("$mainUrl/search-results/?search_string=$q", page)
            val items = runCatching {
                parseCards(app.get(url, headers = reqHeaders).document)
            }.getOrDefault(emptyList())
            if (items.isEmpty()) break
            results.addAll(items)
        }
        return results.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- Detay

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = reqHeaders).document

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.isNotBlank() }
        )

        val plot = (doc.selectFirst("meta[property=og:description]")
            ?: doc.selectFirst("meta[name=description]"))
            ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

        val actors = doc.select("a[href*=/aktrisy/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = doc.selectFirst("a[href*=filter_year=]")?.text()?.trim()?.toIntOrNull()

        val duration = DURATION_REGEX.find(doc.text())?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val c = m.groupValues[3].toIntOrNull()
            if (c != null) a * 60 + b else a // h:mm:ss -> dakika, mm:ss -> dakika
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.duration = duration
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    // ---------------------------------------------------------------- Linkler

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = reqHeaders, referer = "$mainUrl/")
        val doc = res.document

        // Altyazılar
        doc.select("video track[src], track[src]").forEach { t ->
            val src = fixUrlNull(t.attr("src")) ?: return@forEach
            val label = t.attr("label").ifBlank { t.attr("srclang") }.ifBlank { "Subtitle" }
            subtitleCallback(SubtitleFile(label, src))
        }

        // Video adayları: DOM + ham HTML
        val candidates = LinkedHashSet<Pair<String, Int?>>()
        doc.select("[data-video-src]").forEach {
            addCandidate(candidates, it.attr("data-video-src"), it.attr("data-res"))
        }
        doc.select("video source[src], video[src]").forEach {
            addCandidate(candidates, it.attr("src"), it.attr("data-res").ifBlank { it.attr("res") })
        }
        doc.select("a[href\$=.mp4], a[href\$=.m3u8]").forEach {
            addCandidate(candidates, it.attr("href"), null)
        }
        MEDIA_REGEX.findAll(res.text).forEach {
            addCandidate(candidates, it.value.replace("\\/", "/"), null)
        }

        val seen = HashSet<String>()
        var found = false
        candidates.forEach { (url, q) ->
            if (!seen.add(url)) return@forEach
            val isM3u8 = url.contains(".m3u8")
            val quality = q ?: QUALITY_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            callback(
                newExtractorLink(
                    source = name,
                    name = if (isM3u8) "$name HLS" else name,
                    url = url,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            found = true
        }
        return found
    }

    // ---------------------------------------------------------------- Yardımcılar

    private fun addCandidate(into: MutableSet<Pair<String, Int?>>, raw: String, res: String?) {
        val u = raw.trim().takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return
        into.add(fixUrl(u) to res?.filter { it.isDigit() }?.toIntOrNull())
    }

    private fun withPage(base: String, page: Int): String {
        if (page <= 1) return base
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}p=$page"
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("a.item__cover[href]")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }

    private fun parseCard(a: Element): SearchResponse? {
        val rawHref = a.attr("href").trim()
        if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return null
        val href = fixUrl(rawHref)

        val card = findCard(a)
        val img = card.selectFirst("img.item__img, img")

        val title = card.selectFirst(TITLE_SELECTOR)?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = img?.let { posterFrom(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // jsoup'un eski sürümlerinde closest() olmadığı için elle üst elemana çıkıyoruz
    private fun findCard(a: Element): Element {
        var el: Element? = a
        while (el != null) {
            if (el.tagName() == "article" || el.tagName() == "li" || el.hasClass("item")) return el
            el = el.parent()
        }
        return a.parent() ?: a
    }

    private fun posterFrom(img: Element): String? {
        for (attr in POSTER_ATTRS) {
            val v = img.attr(attr).trim()
            if (v.isNotBlank() && !v.startsWith("data:")) return fixUrl(v)
        }
        return null
    }
}
