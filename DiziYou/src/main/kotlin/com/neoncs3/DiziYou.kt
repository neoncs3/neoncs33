package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziYou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "DiziYou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val archiveSections = listOf(
        "Yeni Eklenen Diziler" to "filtrele=tarih&sirala=DESC",
        "IMDb 7+ Diziler" to "filtrele=imdb&sirala=DESC&yil=&imdb=7",
        "Aksiyon Dizileri" to "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Aksiyon",
        "Bilim Kurgu Dizileri" to "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Bilim+Kurgu",
        "Gerilim Dizileri" to "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Gerilim",
        "Korku Dizileri" to "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Korku",
        "Suç Dizileri" to "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Su%C3%A7",
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val home = mutableListOf<HomePageList>()

        for ((sectionName, query) in archiveSections) {
            val url = archiveUrl(query, page)
            val items = runCatching {
                val document = app.get(url, headers = requestHeaders).document
                document.selectSeriesAnchors()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }.getOrElse {
                Log.e("DIZIYOU", "Arşiv hatası: $sectionName -> $url", it)
                emptyList()
            }

            Log.d("DIZIYOU", "$sectionName -> ${items.size} sonuç")

            if (items.isNotEmpty()) {
                home.add(HomePageList(sectionName, items))
            }
        }

        // Arşiv filtreleri Cloudflare/tema nedeniyle boş dönerse ana sayfayı
        // tamamen siyah bırakmamak için güvenli bir fallback kullanılır.
        if (home.isEmpty()) {
            runCatching {
                val fallbackItems = app.get("$mainUrl/", headers = requestHeaders).document
                    .selectSeriesAnchors()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (fallbackItems.isNotEmpty()) {
                    home.add(HomePageList("Diziler", fallbackItems))
                    Log.d("DIZIYOU", "Fallback ana sayfa: ${fallbackItems.size} sonuç")
                }
            }.onFailure {
                Log.e("DIZIYOU", "Fallback ana sayfa da başarısız", it)
            }
        }

        // Sayfa 2, 3... yalnızca gerçekten içerik geldiyse devam eder.
        return newHomePageResponse(
            home,
            hasNext = home.isNotEmpty(),
        )
    }

    private fun archiveUrl(query: String, page: Int): String {
        return if (page <= 1) {
            "$mainUrl/dizi-arsivi/?$query"
        } else {
            "$mainUrl/dizi-arsivi/page/$page/?$query"
        }
    }

    /**
     * Diziyou'nun teması zaman zaman aynı ID'leri birden fazla kez kullanıyor.
     * Bu nedenle kartı bir CSS ID'sine bağlamak yerine gerçek dizi linklerini
     * buluyoruz. Böylece ana sayfa ve arşivlerde poster + başlık birlikte alınır.
     */
    private fun Document.selectSeriesAnchors(): List<Element> {
        val result = LinkedHashMap<String, Element>()

        select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            if (!isSeriesUrl(href)) return@forEach

            val hasPoster = anchor.selectFirst("img") != null ||
                ancestorHasImage(anchor)
            val text = anchor.text().trim()

            // Dizi kartı genellikle posterli anchor'dır. Poster yoksa ancak
            // anlamlı bir metin varsa kabul ediyoruz.
            if (!hasPoster && text.length < 2) return@forEach

            result.putIfAbsent(href.trimEnd('/'), anchor)
        }

        return result.values.toList()
    }

    private fun ancestorHasImage(element: Element): Boolean {
        var parent: Element? = element.parent()
        repeat(4) {
            val current = parent ?: return false
            if (current.selectFirst("img") != null) return true
            parent = current.parent()
        }
        return false
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName().equals("a", ignoreCase = true)) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!isSeriesUrl(href)) return null

        val image = anchor.selectFirst("img") ?: findInParents(anchor, "img")
        val poster = image?.let {
            firstNonBlank(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src"),
            )?.let(::fixUrlNull)
        }

        val title = firstNonBlank(
            anchor.attr("title"),
            anchor.text(),
            findInParents(anchor, "h1,h2,h3,h4,h5,.cat-title-main,.cat-title,.dizi-title,.series-title,.title,.entry-title")?.text(),
            image?.attr("alt"),
        )?.trim()?.takeIf { it.isNotBlank() }
            ?: slugToTitle(href)

        val sourceText = findInParents(anchor, "div#categorytitle,.categorytitle,.cat-title-main,.dizi-card,.series-card,article,li,div")?.text()
            ?: anchor.parent()?.text()
            ?: anchor.text()

        val score = extractImdbScore(sourceText)

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries,
        ) {
            posterUrl = poster
            score?.let { this.score = Score.from10(it) }
        }
    }

    private fun findInParents(element: Element, selector: String): Element? {
        var current: Element? = element
        repeat(6) {
            current = current?.parent()
            if (current == null) return null
            current?.selectFirst(selector)?.let { return it }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=${encoded.replace("+", "%20")}",
            "$mainUrl/dizi-arsivi/?kelime=$encoded",
        )

        for (url in urls) {
            val results = runCatching {
                app.get(url, headers = requestHeaders).document
                    .selectSeriesAnchors()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }.getOrElse {
                Log.e("DIZIYOU", "Arama hatası: $url", it)
                emptyList()
            }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching {
            app.get(url, headers = requestHeaders).document
        }.getOrElse {
            Log.e("DIZIYOU", "Dizi sayfası açılamadı: $url", it)
            return null
        }

        val pageText = document.text()
        val title = firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
        )?.replace(Regex("\\s+"), " ")?.trim()
            ?: return null

        val poster = firstNonBlank(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("div.category_image img")?.attr("data-src"),
            document.selectFirst("div.category_image img")?.attr("data-lazy-src"),
            document.selectFirst("div.category_image img")?.attr("src"),
            document.selectFirst("article img")?.attr("src"),
        )?.let(::fixUrlNull)

        val description = firstNonBlank(
            document.selectFirst("div.diziyou_desc")?.text(),
            document.selectFirst("div.categorytext")?.text(),
            document.selectFirst("div.aciklama")?.text(),
            document.selectFirst("meta[name='description']")?.attr("content"),
        )?.trim()

        val year = extractYear(pageText)

        val tags = document.select(
            ".genres a, .genre a, .genres span, a[href*='/tur/'], .dizitur a"
        )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val imdbScore = extractImdbScore(pageText)
        val actors = extractActors(document, pageText)
        val trailer = extractTrailer(document)
        val episodes = extractEpisodes(document)

        Log.d("DIZIYOU", "$title -> ${episodes.size} bölüm")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes,
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            addActors(actors)

            imdbScore?.let { this.score = Score.from10(it) }
            trailer?.let { addTrailer(it) }
        }
    }

    private fun extractYear(text: String): Int? {
        val match = Regex(
            "(?:Yapım Yılı|Yapim Yili|Yıl|Yil)\\s*[:.]?\\s*(19\\d{2}|20\\d{2})",
            RegexOption.IGNORE_CASE,
        ).find(text)

        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractActors(document: Document, pageText: String): List<Actor>? {
        val linkedActors = document.select(
            "a[href*='/oyuncu/'], .actors a, .cast a, .oyuncular a, .oyuncu a"
        )
            .mapNotNull { anchor ->
                val name = anchor.text().trim()
                name.takeIf { it.isNotBlank() }?.let { Actor(it) }
            }
            .distinctBy { it.name.lowercase() }

        if (linkedActors.isNotEmpty()) return linkedActors

        val match = Regex(
            "Oyuncular\\s*[:：]?\\s*(.*?)(?=\\s+(?:Tür|Tur|Yapım Yılı|Yapim Yili|IMDB|IMDb|Bölümler|Bolumler)\\b|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(pageText)

        val raw = match?.groupValues?.getOrNull(1)?.trim()
            ?: return null

        val actors = raw
            .replace(Regex("\\s+"), " ")
            .trim(' ', ':', '-', ',')
            .split(Regex("\\s*,\\s*|\\s+[-|/]\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { Actor(it) }

        return actors.takeIf { it.isNotEmpty() }
    }

    /** Diziyou dizi sayfasındaki gerçek sezon/bölüm URL'leri. */
    private fun extractEpisodes(document: Document): List<Episode> {
        val episodeRegex = Regex(
            "-(\\d+)-sezon-(\\d+)-bolum(?:/|\\?|$)",
            RegexOption.IGNORE_CASE,
        )

        val result = LinkedHashMap<String, Episode>()

        document.select("a[href]").forEach { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@forEach
            val match = episodeRegex.find(href) ?: return@forEach

            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach

            val blockText = firstNonBlank(
                anchor.selectFirst(".bolumismi")?.text(),
                anchor.selectFirst(".baslik")?.text(),
                anchor.text(),
                anchor.parent()?.text(),
            ) ?: "$episode. Bölüm"

            val episodeName = blockText
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "$episode. Bölüm" }

            result[href.trimEnd('/')] = newEpisode(href) {
                name = episodeName
                this.season = season
                this.episode = episode
                posterUrl = anchor.selectFirst("img")?.let {
                    firstNonBlank(
                        it.attr("data-src"),
                        it.attr("data-lazy-src"),
                        it.attr("src"),
                    )?.let(::fixUrlNull)
                }
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = runCatching {
            app.get(data, headers = requestHeaders).document
        }.getOrElse {
            Log.e("DIZIYOU", "Bölüm sayfası açılamadı: $data", it)
            return false
        }

        val playerSrc = firstNonBlank(
            document.selectFirst("iframe#diziyouPlayer")?.attr("src"),
            document.selectFirst("iframe#diziyouPlayer")?.attr("data-src"),
            document.selectFirst("iframe[src*='diziyou']")?.attr("src"),
            document.selectFirst("iframe[src*='player']")?.attr("src"),
            document.selectFirst("iframe[src]")?.attr("src"),
        )?.let(::fixUrlNull)

        if (playerSrc == null) {
            Log.e("DIZIYOU", "Player iframe bulunamadı: $data")
            return false
        }

        val itemId = extractItemId(playerSrc) ?: run {
            Log.e("DIZIYOU", "Player ID alınamadı: $playerSrc")
            return false
        }

        val storage = "$mainUrl".replace("www.", "storage.").trimEnd('/')
        val originalHls = "$storage/episodes/$itemId/play.m3u8"
        val dubbedHls = "$storage/episodes/${itemId}_tr/play.m3u8"
        val trVtt = "$storage/subtitles/$itemId/tr.vtt"
        val enVtt = "$storage/subtitles/$itemId/en.vtt"

        val optionTexts = document.select(".diziyouOption, [id*='Altyaz'], [id*='Dublaj']")
            .map { (it.text() + " " + it.attr("id") + " " + it.attr("data-id")).lowercase() }

        val hasDub = optionTexts.any { it.contains("dublaj") || it.contains("turkce") && !it.contains("altyaz") }
        val hasTrSub = optionTexts.any { it.contains("altyaz") && it.contains("turk") } || optionTexts.isEmpty()
        val hasEnSub = optionTexts.any { it.contains("altyaz") && it.contains("ingiliz") }

        if (hasTrSub) {
            subtitleCallback(
                SubtitleFile(
                    lang = "Turkish",
                    url = trVtt,
                )
            )
        }

        if (hasEnSub) {
            subtitleCallback(
                SubtitleFile(
                    lang = "English",
                    url = enVtt,
                )
            )
        }

        val streams = linkedMapOf<String, String>()
        streams["Orijinal Dil"] = originalHls
        if (hasDub) streams["Türkçe Dublaj"] = dubbedHls

        streams.forEach { (streamName, streamUrl) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = streamName,
                    url = streamUrl,
                    type = INFER_TYPE,
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.P1080.value
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
        }

        return streams.isNotEmpty()
    }

    private fun extractItemId(playerSrc: String): String? {
        val clean = playerSrc.substringBefore('?').trimEnd('/')

        val queryId = Regex(
            "(?:[?&](?:id|video|v|item)=)([A-Za-z0-9_-]+)",
            RegexOption.IGNORE_CASE,
        ).find(playerSrc)?.groupValues?.getOrNull(1)

        if (!queryId.isNullOrBlank()) return queryId

        return clean
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .takeIf { it.isNotBlank() }
    }

    private fun extractTrailer(document: Document): String? {
        val candidates = buildList {
            addAll(document.select("iframe[src]").mapNotNull { it.attr("src") })
            addAll(document.select("a[href*='youtube.com'], a[href*='youtu.be']").mapNotNull { it.attr("href") })
            document.selectFirst("meta[itemprop='embedUrl']")?.attr("content")?.let(::add)
        }

        return candidates.firstOrNull { value ->
            value.contains("youtube.com", true) || value.contains("youtu.be", true)
        }?.let(::fixUrlNull)
    }

    private fun extractImdbScore(text: String): Float? {
        val score = Regex(
            "(?:IMDb|IMDB)\\s*(?:Puanı|Puani)?\\s*[:★]?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        return score?.takeIf { it in 0f..10f }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return Regex(
            "-[0-9]+-sezon-[0-9]+-bolum(?:/|\\?|$)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(url)
    }

    private fun isSeriesUrl(url: String): Boolean {
        if (isEpisodeUrl(url)) return false

        // Site bazen www.diziyou.one -> diziyou.one canonical yönlendirmesi
        // yapabiliyor. İki alan adını aynı site kabul ediyoruz.
        val value = url.trimEnd('/')
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")

        val base = mainUrl.trimEnd('/')
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")

        if (!(value == base || value.startsWith("$base/"))) return false

        val excluded = listOf(
            "$base/dizi-arsivi",
            "$base/film-arsivi",
            "$base/kategori/",
            "$base/category/",
            "$base/etiket/",
            "$base/tag/",
            "$base/oyuncu/",
            "$base/yonetmen/",
            "$base/page/",
            "$base/wp-",
            "$base/iletisim",
            "$base/gizlilik",
            "$base/hakkimizda",
        )

        if (value == base) return false
        if (excluded.any { value.startsWith(it) }) return false
        if (value.endsWith(".xml") || value.endsWith(".jpg") || value.endsWith(".png") || value.endsWith(".css") || value.endsWith(".js")) return false

        return true
    }

    private fun slugToTitle(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast('/')
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/154.0 Safari/537.36"
    }
}
