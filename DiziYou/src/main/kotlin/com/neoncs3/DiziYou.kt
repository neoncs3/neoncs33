package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziYou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "Diziyou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    private val storageUrl = "https://storage.diziyou.one"

    private data class ArchiveSection(
        val title: String,
        val query: String
    )

    /**
     * Diziyou arşivinin gerçek sırası.
     * Son Eklenen Bölümler özellikle eklenmiyor.
     */
    private val archiveSections = listOf(
        ArchiveSection(
            "Yeni Eklenen Diziler",
            "filtrele=tarih&sirala=DESC"
        ),
        ArchiveSection(
            "IMDb 7+ Diziler",
            "filtrele=imdb&sirala=DESC&yil=&imdb=7"
        ),
        ArchiveSection(
            "Aksiyon Dizileri",
            "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Aksiyon"
        ),
        ArchiveSection(
            "Bilim Kurgu Dizileri",
            "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Bilim+Kurgu"
        ),
        ArchiveSection(
            "Gerilim Dizileri",
            "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Gerilim"
        ),
        ArchiveSection(
            "Korku Dizileri",
            "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=Korku"
        ),
        ArchiveSection(
            "Suç Dizileri",
            "filtrele=tarih&sirala=DESC&yil=&imdb=7&kelime=&tur=${URLEncoder.encode("Suç", "UTF-8")}"
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = ArrayList<HomePageList>()

        for (section in archiveSections) {
            val url = archiveUrl(section.query, page)
            val items = runCatching {
                app.get(url).document
                    .findSeriesCards()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }
            }.getOrElse {
                Log.e("DIZIYOU", "Arşiv yüklenemedi: ${section.title} / $url", it)
                emptyList()
            }

            if (items.isNotEmpty()) {
                home.add(HomePageList(section.title, items))
            }
        }

        return newHomePageResponse(home)
    }

    private fun archiveUrl(query: String, page: Int): String {
        return if (page <= 1) {
            "$mainUrl/dizi-arsivi/?$query"
        } else {
            "$mainUrl/dizi-arsivi/page/$page/?$query"
        }
    }

    /**
     * Sitede kartların HTML sınıfı değişebildiği için birkaç yapı destekleniyor.
     * Önemli nokta: alfabetik menüdeki binlerce dizi linkini kart diye almamak.
     */
    private fun Document.findSeriesCards(): List<Element> {
        val primary = select(
            "div#list-series-main, div#list-series, " +
                "div.category-item, div.cat-item, article"
        ).filter { element ->
            element.selectFirst("a[href]") != null &&
                element.selectFirst("img") != null
        }

        if (primary.isNotEmpty()) return primary

        return select("a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            val img = anchor.selectFirst("img")
            if (img == null || !isSeriesUrl(href)) return@mapNotNull null
            anchor.closest("article, div")
        }.filter { it.selectFirst("a[href]") != null }
            .distinctBy { it.selectFirst("a[href]")?.attr("href") }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst(
            "a[href]:has(img), div#categorytitle a[href], div.cat-title-main a[href]"
        ) ?: selectFirst("a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!isSeriesUrl(href)) return null

        val title = firstNonBlank(
            anchor.attr("title"),
            anchor.text(),
            selectFirst("#categorytitle, .cat-title-main, .title, h2, h3")?.text()
        ) ?: return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(
            firstNonBlank(
                image?.attr("data-src"),
                image?.attr("data-lazy-src"),
                image?.attr("data-original"),
                image?.attr("src")
            )
        )

        val imdbScore = extractImdbScore(text())

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            if (imdbScore != null) {
                score = Score.from10(imdbScore)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document

        val results = document.findSeriesCards()
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        if (results.isNotEmpty()) return results

        // Fallback: yalnızca dizi URL'si + poster içeren bağlantıları kabul et.
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!isSeriesUrl(href) || anchor.selectFirst("img") == null) return@mapNotNull null

                val title = firstNonBlank(anchor.attr("title"), anchor.text()) ?: return@mapNotNull null
                val image = anchor.selectFirst("img")
                val poster = fixUrlNull(
                    firstNonBlank(
                        image.attr("data-src"),
                        image.attr("data-lazy-src"),
                        image.attr("src")
                    )
                )

                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    extractImdbScore(anchor.parent()?.text().orEmpty())?.let {
                        score = Score.from10(it)
                    }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content")
        ) ?: return null

        val poster = fixUrlNull(
            firstNonBlank(
                document.selectFirst("div.category_image img")?.attr("data-src"),
                document.selectFirst("div.category_image img")?.attr("data-lazy-src"),
                document.selectFirst("div.category_image img")?.attr("src"),
                document.selectFirst("meta[property='og:image']")?.attr("content")
            )
        )

        val pageText = document.text()

        val description = firstNonBlank(
            document.selectFirst("div.diziyou_desc")?.text(),
            document.selectFirst("div.entry-content p")?.text(),
            document.selectFirst("meta[name='description']")?.attr("content")
        )

        val year = Regex(
            "Yapım\\s+Yılı\\s*:?\\s*(\\d{4})",
            RegexOption.IGNORE_CASE
        ).find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val genres = document.select("a[href]")
            .map { it.text().trim() }
            .filter { genre ->
                genre in setOf(
                    "Aile", "Aksiyon", "Animasyon", "Belgesel", "Bilim Kurgu",
                    "Dram", "Fantazi", "Gerilim", "Gizem", "Komedi", "Korku",
                    "Macera", "Politik", "Savaş", "Suç", "Vahşi Batı"
                )
            }
            .distinct()

        val actors = extractActors(document, pageText)
        val imdbScore = extractImdbScore(pageText)
        val trailer = findTrailerUrl(document)
        val episodes = extractEpisodes(document)

        return newTvSeriesLoadResponse(
            title = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = genres
            addActors(actors)
            if (imdbScore != null) {
                score = Score.from10(imdbScore)
            }
            if (trailer != null) {
                addTrailer(trailer)
            }
        }
    }

    /**
     * Oyuncular sayfada link değil, düz metin olarak veriliyor.
     * Örn: "Oyuncular: Rebecca Ferguson, Tim Robbins, Common ..."
     */
    private fun extractActors(document: Document, pageText: String): List<Actor> {
        val actorTextPattern = Regex(
            "Oyuncular\\s*[:：]?\\s*(.*?)(?=\\s+(?:Aile|Aksiyon|Animasyon|Belgesel|Bilim\\s+Kurgu|Dram|Fantazi|Gerilim|Gizem|Komedi|Korku|Macera|Politik|Savaş|Suç|Vahşi\\s+Batı|Yapım\\s+Yılı|Tür|Tur|Görünüm|Bölümler)\\b|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        // Önce Oyuncular bilgisini taşıyan metadata çevresinde arıyoruz.
        val actorMetaText = document.select("span.dizimeta, div.dizimeta, .dizimeta")
            .firstOrNull { it.text().contains("Oyuncular", ignoreCase = true) }
            ?.parent()
            ?.text()
            .orEmpty()

        val rawActors = actorTextPattern.find(actorMetaText)?.groupValues?.getOrNull(1)
            ?: actorTextPattern.find(pageText)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val actorText = rawActors
            .replace(Regex("\\s+"), " ")
            .trim(' ', ':', '-')

        if (actorText.isBlank()) return emptyList()

        return actorText.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals("Oyuncular", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .map { Actor(it) }
    }

    /**
     * Bölüm kartlarının class'ına bağımlı kalmadan gerçek bölüm URL'lerini yakalar.
     * Örn: /silo-3-sezon-8-bolum/
     */
    private fun extractEpisodes(document: Document): List<Episode> {
        val episodes = document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!isEpisodeUrl(href)) return@mapNotNull null

                val text = anchor.text().trim()
                val combined = "$text ${anchor.attr("title")}".trim()

                val season = Regex(
                    "(\\d+)\\s*\\.?\\s*Sezon",
                    RegexOption.IGNORE_CASE
                ).find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1

                val episode = Regex(
                    "(\\d+)\\s*\\.?\\s*Bölüm",
                    RegexOption.IGNORE_CASE
                ).find(combined)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-[0-9]+-sezon-(\\d+)-bolum(?:/|$)", RegexOption.IGNORE_CASE)
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                val episodeName = extractEpisodeName(combined, season, episode)

                newEpisode(href) {
                    name = episodeName
                    this.season = season
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season }.thenBy { it.episode })

        return episodes
    }

    private fun extractEpisodeName(text: String, season: Int, episode: Int): String {
        val clean = text
            .replace(Regex("\\s+"), " ")
            .trim()

        val parentheses = Regex("\\(([^)]*)\\)")
            .find(clean)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.matches(Regex("\\d+\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)) }

        if (parentheses != null) return parentheses

        val afterEpisode = Regex(
            "\\d+\\s*\\.?\\s*Bölüm\\s*[-:]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).find(clean)?.groupValues?.getOrNull(1)?.trim()

        return afterEpisode?.takeIf { it.isNotEmpty() }
            ?: "$season. Sezon $episode. Bölüm"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DIZIYOU", "episode url = $data")

        val document = runCatching { app.get(data).document }.getOrElse {
            Log.e("DIZIYOU", "Bölüm sayfası açılamadı: $data", it)
            return false
        }

        val playerSrc = findPlayerUrl(document)

        if (playerSrc.isNullOrBlank()) {
            Log.d("DIZIYOU", "player iframe bulunamadı: $data")
            return false
        }

        val itemId = extractItemId(playerSrc)
        if (itemId.isNullOrBlank()) {
            Log.d("DIZIYOU", "player itemId bulunamadı: $playerSrc")
            return false
        }

        Log.d("DIZIYOU", "itemId = $itemId")

        val pageText = document.text()
        val hasTrSub = pageText.contains("Türkçe Altyazılı", ignoreCase = true)
        val hasEnSub = pageText.contains("İngilizce Altyazılı", ignoreCase = true)
        val hasDub = pageText.contains("Türkçe Dublaj", ignoreCase = true)

        val originalStream = "$storageUrl/episodes/$itemId/play.m3u8"
        val dubStream = "$storageUrl/episodes/${itemId}_tr/play.m3u8"

        if (hasTrSub) {
            subtitleCallback(
                newSubtitleFile(
                    lang = "Turkish",
                    url = "$storageUrl/subtitles/$itemId/tr.vtt"
                )
            )
        }

        if (hasEnSub) {
            subtitleCallback(
                newSubtitleFile(
                    lang = "English",
                    url = "$storageUrl/subtitles/$itemId/en.vtt"
                )
            )
        }

        // Sayfada dil seçeneği görünmese bile mevcut ana HLS'i dene.
        callback(
            newExtractorLink(
                source = name,
                name = "Orijinal Dil 1080p",
                url = originalStream,
                type = INFER_TYPE
            ) {
                referer = "$mainUrl/"
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl
                )
                quality = Qualities.P1080.value
            }
        )

        if (hasDub) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Türkçe Dublaj 1080p",
                    url = dubStream,
                    type = INFER_TYPE
                ) {
                    referer = "$mainUrl/"
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                    quality = Qualities.P1080.value
                }
            )
        }

        return true
    }

    private fun findPlayerUrl(document: Document): String? {
        val candidates = document.select("iframe[src], iframe[data-src], iframe[data-url], iframe[data-embed]")
            .flatMap { iframe ->
                listOf(
                    iframe.attr("src"),
                    iframe.attr("data-src"),
                    iframe.attr("data-url"),
                    iframe.attr("data-embed")
                )
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull(::fixUrlNull)

        return candidates.firstOrNull { url ->
            url.contains("diziyou", ignoreCase = true) ||
                url.contains("player", ignoreCase = true) ||
                url.contains("storage", ignoreCase = true) ||
                url.contains("embed", ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    private fun extractItemId(playerSrc: String): String? {
        val clean = playerSrc.substringBefore('#').substringBefore('?').trimEnd('/')

        Regex("/([^/]+)\\.html$", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        clean.substringAfterLast('/')
            .takeIf { it.isNotBlank() && !it.contains('.') }
            ?.let { return it }

        Regex("(?:id|episode|video)[=/]([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(playerSrc)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    private fun extractImdbScore(text: String): Float? {
        val explicit = Regex(
            "(?:IMDb|IMDB)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        if (explicit != null && explicit in 0f..10f) return explicit

        return Regex("\\(([0-9]+(?:[.,][0-9]+)?)\\)")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() }
            .firstOrNull { it in 0f..10f }
    }

    private fun findTrailerUrl(document: Document): String? {
        val direct = document.select(
            "iframe[src], iframe[data-src], iframe[data-url], iframe[data-embed], a[href]"
        ).asSequence()
            .mapNotNull { element ->
                val values = if (element.tagName() == "a") {
                    listOf(element.attr("href"))
                } else {
                    listOf(
                        element.attr("src"),
                        element.attr("data-src"),
                        element.attr("data-url"),
                        element.attr("data-embed")
                    )
                }
                values.firstOrNull { it.isNotBlank() }
            }
            .mapNotNull(::fixUrlNull)
            .firstOrNull { url -> isTrailerHost(url) }

        return direct
    }

    private fun isTrailerHost(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) ||
            url.contains("youtube-nocookie.com", ignoreCase = true) ||
            url.contains("vimeo.com", ignoreCase = true)
    }

    private fun isEpisodeUrl(url: String): Boolean {
        if (!url.contains("diziyou.one", ignoreCase = true)) return false

        // Diziyou bölüm URL formatı: /dizi-adi-1-sezon-1-bolum/
        return Regex(
            "-[0-9]+-sezon-[0-9]+-bolum(?:/|$)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(url)
    }

    private fun isSeriesUrl(url: String): Boolean {
        if (!url.contains("diziyou.one", ignoreCase = true)) return false
        if (isEpisodeUrl(url)) return false
        return url != mainUrl &&
            !url.contains("/dizi-arsivi", ignoreCase = true) &&
            !url.contains("/dizi/") &&
            !url.contains("/film/") &&
            !url.contains("/iletisim", ignoreCase = true) &&
            !url.contains("/gizlilik", ignoreCase = true)
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}
