package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziYou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "Diziyou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Cloudflare / yavaş ana sayfa yüklemelerinde yardımcı olur.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    private val storageUrl = "https://storage.diziyou.one"

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    /**
     * Ana sayfa bölümleri. Tür filtrelerinde IMDb 7 filtresi özellikle
     * kullanılmaz; böylece türün bütün dizileri gelir.
     */
    private val archiveSections = listOf(
        "Yeni Eklenen Diziler" to "filtrele=tarih&sirala=DESC",
        "IMDb 7+ Diziler" to "filtrele=imdb&sirala=DESC&yil=&imdb=7",
        "Aksiyon Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Aksiyon",
        "Bilim Kurgu Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Bilim+Kurgu",
        "Gerilim Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Gerilim",
        "Korku Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Korku",
        "Suç Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Su%C3%A7",
        "Komedi Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Komedi",
        "Dram Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Dram",
        "Gizem Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Gizem",
        "Macera Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Macera",
        "Fantazi Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Fantazi",
        "Animasyon Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Animasyon",
        "Aile Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Aile",
        "Belgesel Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Belgesel",
        "Politik Diziler" to "filtrele=tarih&sirala=DESC&kelime=&tur=Politik",
        "Savaş Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Sava%C5%9F",
        "Vahşi Batı Dizileri" to "filtrele=tarih&sirala=DESC&kelime=&tur=Vah%C5%9Fi+Bat%C4%B1",
        "Romantik Diziler" to "filtrele=tarih&sirala=DESC&kelime=&tur=Romantik"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val home = ArrayList<HomePageList>()

        for ((sectionName, query) in archiveSections) {
            val url = archiveUrl(query, page)

            val items = runCatching {
                val pageUrls = if (page <= 1) {
                    listOf(archiveUrl(query, 1), archiveUrl(query, 2))
                } else {
                    listOf(url)
                }

                val collected = LinkedHashMap<String, SearchResponse>()

                for (pageUrl in pageUrls) {
                    val document = app.get(pageUrl, headers = requestHeaders).document

                    // Container'ın kendisini değil, bütün gerçek dizi kartlarını/anchor'larını al.
                    document.selectSeriesAnchors()
                        .mapNotNull { it.toSearchResponse() }
                        .forEach { response ->
                            collected.putIfAbsent(response.url.trimEnd('/'), response)
                        }
                }

                collected.values.toList()
            }.onFailure {
                Log.e("DIZIYOU", "Ana sayfa bölümü yüklenemedi: $sectionName -> $url", it)
            }.getOrDefault(emptyList())

            Log.d("DIZIYOU", "$sectionName / sayfa $page -> ${items.size} dizi")

            if (items.isNotEmpty()) {
                home.add(HomePageList(sectionName, items))
            }
        }

        // Site ana sayfasında ayrıca bulunan vitrinleri de ekle. Bunlar
        // arşiv türlerinden bağımsızdır.
        if (page == 1) {
            runCatching {
                val document = app.get(mainUrl, headers = requestHeaders).document

                val latest = document.selectSeriesAnchors()
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                if (latest.isNotEmpty()) {
                    home.add(0, HomePageList("Ana Sayfa - Son Eklenenler", latest))
                }
            }.onFailure {
                Log.e("DIZIYOU", "Ana sayfa vitrini okunamadı", it)
            }
        }

        // Tarayıcıdaki sayfa geçişleri CloudStream'de de çalışsın.
        // Son sayfada boş sonuç dönerse hasNext zaten false olur.
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
     * DiziYou'da aynı kart yapısı farklı bölümlerde kullanılabiliyor.
     * Bu yüzden yalnızca tek bir #list-series elemanı seçmek yerine bütün
     * posterli ve gerçek dizi bağlantılarını topluyoruz.
     */
    private fun Document.selectSeriesAnchors(): List<Element> {
        val result = LinkedHashMap<String, Element>()

        val candidates = select(
            "div#list-series-main a[href], " +
                "div#list-series a[href], " +
                "div.incontent a[href], " +
                "article a[href], " +
                "a[href]"
        )

        for (anchor in candidates) {
            val href = fixUrlNull(anchor.attr("href")) ?: continue
            if (!isSeriesUrl(href)) continue

            val hasPoster = anchor.selectFirst("img, picture img") != null ||
                findInParents(anchor, "img, picture img") != null
            val inSeriesCard = anchor.closest(
                "div#list-series-main, div#list-series, article, div.category-item, div.cat-item"
            ) != null

            val text = anchor.text().trim()
            val title = anchor.attr("title").trim()

            // Menü ve alfabetik navigasyon linklerini alma. Gerçek kartta
            // poster veya bilinen kart container'ı bulunmalı.
            if (!hasPoster && !inSeriesCard) continue
            if (text.length < 2 && title.length < 2) continue

            result.putIfAbsent(href.trimEnd('/'), anchor)
        }

        return result.values.toList()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = if (tagName().equals("a", ignoreCase = true)) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!isSeriesUrl(href)) return null

        val image = anchor.selectFirst("img, picture img")
            ?: findInParents(anchor, "img, picture img")

        val poster = image?.let { img ->
            firstNonBlank(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("src"),
            )?.let(::fixUrlNull)
        }

        val card = findInParents(
            anchor,
            "div#list-series-main, div#list-series, div#categorytitle, div.cat-title-main, article, li, div"
        )

        val title = firstNonBlank(
            anchor.attr("title"),
            anchor.text(),
            card?.selectFirst("div#categorytitle, div.cat-title-main, h1, h2, h3, h4, .title")?.text(),
            image?.attr("alt"),
        )?.replace(Regex("\\s+"), " ")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: slugToTitle(href)

        val scoreText = buildString {
            append(anchor.text())
            append(' ')
            append(anchor.attr("title"))
            append(' ')
            append(anchor.attr("aria-label"))
            append(' ')
            append(anchor.attr("data-imdb"))
            append(' ')
            append(anchor.attr("data-score"))
            append(' ')
            append(anchor.attr("data-rating"))
            append(' ')
            append(card?.text().orEmpty())
        }

        val imdbScore = extractImdbScore(scoreText)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            imdbScore?.let { this.score = Score.from10(it) }
        }
    }

    private fun findInParents(element: Element, selector: String): Element? {
        var current: Element? = element
        repeat(8) {
            current = current?.parent()
            val parent = current ?: return null
            if (parent.selectFirst(selector) != null) return parent
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun slugToTitle(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast('/')
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document

        val results = document.select("div.incontent div#list-series")
            .mapNotNull { it.toSearchResponse() }

        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // Tema yapısı değişirse daha genel fallback.
        return document.select("a[href]")
            .filter { anchor ->
                anchor.selectFirst("img") != null &&
                    anchor.text().trim().isNotEmpty() &&
                    anchor.attr("href").contains("$mainUrl/")
            }
            .mapNotNull { anchor ->
                val title = anchor.attr("title").trim().ifEmpty { anchor.text().trim() }
                if (title.isEmpty()) return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val poster = fixUrlNull(
                    anchor.selectFirst("img")?.attr("data-src")
                        ?: anchor.selectFirst("img")?.attr("src")
                )

                val imdbScore = extractImdbScore(anchor.parent() ?: anchor)

                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                    this.score = Score.from10(imdbScore)
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.category_image img")?.attr("data-src")
                ?: document.selectFirst("div.category_image img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val year = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")
            ?.nextSibling()
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("Yapım Yılı\\s*:?\\s*(\\d{4})")
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val tags = document.select("div.genres a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val actors = document.selectFirst("span.dizimeta:contains(Oyuncular)")
            ?.nextSibling()
            ?.toString()
            ?.trim()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { Actor(it) }

        // IMDb puanı: Diziyou detay sayfasında "IMDb: 7.8" veya
        // bazı eski sayfalarda "IMDB : 6.8" biçiminde bulunabiliyor.
        val imdbScore = extractImdbScore(document.text())

        // Fragman için tema değişikliklerine karşı birkaç farklı selector kullan.
        val trailer = findTrailerUrl(document)

        val episodes = document.select("div.bolumust").mapNotNull { element ->
            val rawName = element.selectFirst("div.baslik")?.ownText()?.trim()
                ?: element.selectFirst("div.baslik")?.text()?.trim()
                ?: return@mapNotNull null

            val episodeHref = element.closest("a")?.attr("href")
                ?.let(::fixUrlNull)
                ?: element.selectFirst("a[href]")?.attr("href")?.let(::fixUrlNull)
                ?: return@mapNotNull null

            val season = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE)
                .find(rawName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val episode = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE)
                .find(rawName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val displayName = element.selectFirst("div.bolumismi")?.text()?.trim()
                ?.replace(Regex("[()]"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: rawName

            newEpisode(episodeHref) {
                name = displayName
                this.season = season
                this.episode = episode
            }
        }

        // Fallback: tema yapısı değişirse bölüm URL'lerini doğrudan href üzerinden bul.
        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes.distinctBy { it.data }
        } else {
            document.select("a[href*='-sezon-'][href*='-bolum-']")
                .mapNotNull { anchor ->
                    val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                    val text = anchor.text().trim()
                    val season = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val episode = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    newEpisode(href) {
                        name = text.ifEmpty { "${season}. Sezon ${episode ?: 0}. Bölüm" }
                        this.season = season
                        this.episode = episode
                    }
                }
                .distinctBy { it.data }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            addActors(actors)
            this.score = Score.from10(imdbScore)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DIZIYOU", "episode url = $data")

        val document = app.get(data).document

        // Player iframe: mevcut sitede #diziyouPlayer kullanılıyor.
        val playerSrc = document.selectFirst("iframe#diziyouPlayer")?.attr("src")?.trim()
            ?: document.select("iframe[src]")
                .mapNotNull { it.attr("src").trim().takeIf(String::isNotEmpty) }
                .firstOrNull { it.contains("diziyou", ignoreCase = true) || it.contains("player", ignoreCase = true) }

        if (playerSrc.isNullOrEmpty()) {
            Log.d("DIZIYOU", "player iframe bulunamadı")
            return false
        }

        val itemId = extractItemId(playerSrc)
        if (itemId.isNullOrEmpty()) {
            Log.d("DIZIYOU", "itemId bulunamadı: $playerSrc")
            return false
        }

        Log.d("DIZIYOU", "itemId = $itemId")

        val optionIds = document.select(".diziyouOption, [id^=turkce], [id^=ingilizce]")
            .map { it.id() }
            .toSet()

        val hasTrSub = optionIds.contains("turkceAltyazili") ||
            document.text().contains("Türkçe Altyazılı", ignoreCase = true)
        val hasEnSub = optionIds.contains("ingilizceAltyazili") ||
            document.text().contains("İngilizce Altyazılı", ignoreCase = true)

        // Dublaj için yalnızca player seçeneğini kullan; sitenin alt bölümündeki
        // genel "Türkçe dublaj" metni yanlış pozitif üretmesin.
        val hasDub = optionIds.contains("turkceDublaj") ||
            document.select("#turkceDublaj, [data-id='turkceDublaj']").isNotEmpty()

        val originalStream = "$storageUrl/episodes/$itemId/play.m3u8"
        val dubStream = "$storageUrl/episodes/${itemId}_tr/play.m3u8"

        // Türkçe altyazı
        if (hasTrSub) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "Turkish",
                    url = "$storageUrl/subtitles/$itemId/tr.vtt"
                )
            )
        }

        // İngilizce altyazı
        if (hasEnSub) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "English",
                    url = "$storageUrl/subtitles/$itemId/en.vtt"
                )
            )
        }

        // Orijinal dil HLS
        if (hasTrSub || hasEnSub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
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
        }

        // Türkçe dublaj HLS
        if (hasDub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
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

        // Seçenek sınıfları değiştiyse en azından orijinal akışı dene.
        if (!hasTrSub && !hasEnSub && !hasDub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
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
        }

        return true
    }


    private fun extractImdbScore(element: Element): Float? {
        val candidates = ArrayList<String>()

        // IMDb puanı kartın kendi metninde olabilir.
        candidates += element.text()

        // Bazı tema sürümlerinde puan ayrı bir .imdb/.rating alanında tutuluyor.
        element.select("[class*=imdb], [id*=imdb], [class*=rating], [class*=puan], [data-imdb], [data-score], [data-rating], [aria-label]")
            .forEach { node ->
                candidates += node.text()
                candidates += node.attr("data-imdb")
                candidates += node.attr("data-score")
                candidates += node.attr("data-rating")
                candidates += node.attr("aria-label")
                candidates += node.attr("title")
            }

        // Puan bazen kartın bir üst kapsayıcısında, IMDb etiketiyle birlikte bulunuyor.
        element.parent()?.let { parent ->
            val parentText = parent.text()
            if (parentText.contains("IMDb", true) || parentText.contains("IMDB", true)) {
                candidates += parentText
            }
        }

        candidates.asSequence().mapNotNull { extractImdbScore(it) }.firstOrNull()?.let {
            return it
        }

        return null
    }

    private fun extractImdbScore(text: String): Float? {
        if (text.isBlank()) return null

        // IMDb: 8.2 / IMDB 8,2 / IMDb Puanı: 8.2
        val explicit = Regex(
            "(?:IMDb|IMDB)\\s*(?:Puanı|Puani|Rating|Score)?\\s*[:：\\-]?[\\s★]*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        if (explicit != null && explicit in 0f..10f) return explicit

        // Canlı sitedeki kartlarda puan başlığın yanında doğrudan 8.2 şeklinde geliyor.
        val standalone = Regex("(?<![0-9])([0-9](?:[.,][0-9])?|10(?:[.,]0)?)(?![0-9])")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() }
            .firstOrNull { it in 0f..10f }

        if (standalone != null && (text.contains("IMDb", true) || text.contains("IMDB", true))) {
            return standalone
        }

        // Eski kartlarda puan (8.3) şeklinde gösterilebiliyor.
        return Regex("\\(([0-9]+(?:[.,][0-9]+)?)\\)")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() }
            .firstOrNull { it in 0f..10f }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return Regex(
            "-[0-9]+-sezon-[0-9]+-bolum(?:/|\\?|$)",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(url)
    }

    private fun isSeriesUrl(url: String): Boolean {
        if (isEpisodeUrl(url)) return false

        val clean = url.trimEnd('/')
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")

        val base = mainUrl.trimEnd('/')
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")

        if (!(clean == base || clean.startsWith("$base/"))) return false
        if (clean == base) return false

        val excludedPrefixes = listOf(
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

        if (excludedPrefixes.any { clean.startsWith(it) }) return false
        if (clean.endsWith(".xml") || clean.endsWith(".jpg") || clean.endsWith(".png") ||
            clean.endsWith(".css") || clean.endsWith(".js")) return false

        return true
    }

    private fun findTrailerUrl(document: org.jsoup.nodes.Document): String? {
        // En doğrudan Diziyou trailer iframe'i.
        val directIframe = document.select(
            "iframe.trailer-video, iframe#trailer, .trailer iframe, .fragman iframe, " +
                "[class*=trailer] iframe, [class*=fragman] iframe"
        ).asSequence()
            .mapNotNull { iframe ->
                sequenceOf(
                    iframe.attr("src"),
                    iframe.attr("data-src"),
                    iframe.attr("data-lazy-src"),
                    iframe.attr("data-url"),
                    iframe.attr("data-embed")
                ).firstOrNull { it.isNotBlank() }
            }
            .mapNotNull(::fixUrlNull)
            .firstOrNull { isTrailerHost(it) }

        if (directIframe != null) return directIframe

        // "Fragmanı izle" bağlantısı iframe dışında bir anchor olarak gelirse.
        val trailerLink = document.select("a[href]").asSequence()
            .filter { anchor ->
                val text = anchor.text().trim()
                text.contains("Fragman", ignoreCase = true) ||
                    anchor.attr("class").contains("trailer", ignoreCase = true) ||
                    anchor.attr("id").contains("trailer", ignoreCase = true)
            }
            .mapNotNull { anchor -> fixUrlNull(anchor.attr("href")) }
            .firstOrNull { isTrailerHost(it) || it.contains("embed", ignoreCase = true) }

        if (trailerLink != null) return trailerLink

        // Son fallback: sayfadaki YouTube/Vimeo iframe bağlantılarından ilkini kullan.
        return document.select("iframe[src], iframe[data-src], iframe[data-url]").asSequence()
            .mapNotNull { iframe ->
                sequenceOf(
                    iframe.attr("src"),
                    iframe.attr("data-src"),
                    iframe.attr("data-url")
                ).firstOrNull { it.isNotBlank() }
            }
            .mapNotNull(::fixUrlNull)
            .firstOrNull { isTrailerHost(it) }
    }

    private fun isTrailerHost(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true) ||
            url.contains("youtube-nocookie.com", ignoreCase = true) ||
            url.contains("vimeo.com", ignoreCase = true)
    }

    private fun extractItemId(playerSrc: String): String? {
        val clean = playerSrc.substringBefore('#').substringBefore('?').trimEnd('/')

        // Örn: .../ABC123.html
        Regex("/([^/]+)\\.html$")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Örn: .../ABC123/
        clean.substringAfterLast('/')
            .takeIf { it.isNotBlank() && !it.contains('.') }
            ?.let { return it }

        return null
    }
}
