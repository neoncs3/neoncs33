package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class FilmMakinesi : MainAPI() {

    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
    )

    /**
     * Site üzerinde doğrulanmış ana liste adresleri.
     * Daha az kullanılan tür/yıl/ülke/kanal sayfaları da getMainPage()
     * içerisindeki genel URL parser tarafından desteklenir.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa",
        "$mainUrl/filmler-1/" to "Son Filmler",
        "$mainUrl/yabanci-dizi-izle-1/" to "Son Diziler",
        "$mainUrl/kesfet/" to "Keşfet",

        // Sitede görünen film türleri.
        "$mainUrl/tur/aksiyon-fm1/film/" to "Aksiyon Filmleri",
        "$mainUrl/tur/aile-fm2/film/" to "Aile Filmleri",
        "$mainUrl/tur/animasyon-fm2/film/" to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel/film/" to "Belgesel Filmleri",
        "$mainUrl/tur/biyografi/film/" to "Biyografi Filmleri",
        "$mainUrl/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/dram-fm1/film/" to "Dram Filmleri",
        "$mainUrl/tur/fantastik-fm1/film/" to "Fantastik Filmleri",
        "$mainUrl/tur/gerilim-fm1/film/" to "Gerilim Filmleri",
        "$mainUrl/tur/gizem/film/" to "Gizem Filmleri",
        "$mainUrl/tur/komedi-fm1/film/" to "Komedi Filmleri",
        "$mainUrl/tur/korku-fm2/film/" to "Korku Filmleri",
        "$mainUrl/tur/macera-fm1/film/" to "Macera Filmleri",
        "$mainUrl/tur/muzik/film/" to "Müzik Filmleri",
        "$mainUrl/tur/polisiye/film/" to "Polisiye Filmleri",
        "$mainUrl/tur/romantik-fm1/film/" to "Romantik Filmleri",
        "$mainUrl/tur/savas-fm1/film/" to "Savaş Filmleri",
        "$mainUrl/tur/spor/film/" to "Spor Filmleri",
        "$mainUrl/tur/tarih-fm1/film/" to "Tarih Filmleri",
        "$mainUrl/tur/western-fm1/film/" to "Western Filmleri",

        // Dizi türlerinden sitede doğrulanabilenler.
        "$mainUrl/tur/aksiyon-fm1/dizi/" to "Aksiyon Dizileri",
        "$mainUrl/tur/animasyon-fm7/dizi/" to "Animasyon Dizileri",
        "$mainUrl/tur/bilim-kurgu-fm3/dizi/" to "Bilim Kurgu Dizileri",
        "$mainUrl/tur/dram-fm1/dizi/" to "Dram Dizileri",
        "$mainUrl/tur/korku-fm2/dizi/" to "Korku Dizileri",
        "$mainUrl/tur/macera-fm1/dizi/" to "Macera Dizileri",
        "$mainUrl/tur/polisiye/dizi/" to "Polisiye / Suç Dizileri",
        "$mainUrl/tur/romantik-fm1/dizi/" to "Romantik Dizileri",

        "$mainUrl/yil/2026-fmfbkb/film/" to "2026 Filmleri",
        "$mainUrl/yil/2026-fmfbkb/dizi/" to "2026 Dizileri",
        "$mainUrl/yil/2025-fm4/film/" to "2025 Filmleri",
        "$mainUrl/yil/2025-fm4/dizi/" to "2025 Dizileri",
        "$mainUrl/ulke/turkiye-fm4/" to "Yerli İçerikler",
        "$mainUrl/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "$mainUrl/seri-filmler-izle-1/" to "Seri Filmler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val baseUrl = request.data.ifBlank { "$mainUrl/filmler-1/" }
        val url = pageUrl(baseUrl, page)

        val response = runCatching {
            app.get(url, headers = requestHeaders)
        }.getOrElse {
            Log.e("FILMMAKINESI", "Liste açılamadı: $url", it)
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val document = response.document
        val results = parseListPage(document)

        // Bazı ana arşivlerde numaralı URL kullanılıyor: filmler-1, filmler-2...
        // /sayfa/2/ boş dönerse yalnızca ilgili arşiv için alternatif yolu dene.
        val finalResults = if (results.isEmpty() && page > 1) {
            val alternate = alternatePageUrl(baseUrl, page)
            if (alternate != null && alternate != url) {
                runCatching {
                    app.get(alternate, headers = requestHeaders).document
                }.getOrNull()?.let(::parseListPage).orEmpty()
            } else {
                emptyList()
            }
        } else {
            results
        }

        val hasNext = hasNextPage(document, page) ||
            (finalResults.isNotEmpty() && finalResults.size >= 15)

        Log.d(
            "FILMMAKINESI",
            "${request.name} / sayfa $page -> ${finalResults.size} sonuç",
        )

        return newHomePageResponse(
            request.name,
            finalResults,
            hasNext = hasNext,
        )
    }

    private fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        return "${baseUrl.trimEnd('/')}/sayfa/$page/"
    }

    private fun alternatePageUrl(baseUrl: String, page: Int): String? {
        val value = baseUrl.trimEnd('/')
        if (value.matches(Regex(".*/(?:filmler|yabanci-dizi-izle)-\\d+$"))) {
            return value.replace(
                Regex("-(\\d+)$"),
                "-$page",
            ) + "/"
        }
        return null
    }

    /**
     * FilmMakinesi listelerinde gerçek içerik linkleri /film/.../ ve /dizi/.../.
     * Böylece menü, footer, yıl, tür ve üyelik bağlantıları içerik olarak alınmaz.
     */
    private fun parseListPage(document: Document): List<SearchResponse> {
        return document.select("a[href]")
            .mapNotNull { anchor ->
                val href = fixUrlNull(
                    firstNonBlank(
                        anchor.attr("href"),
                        anchor.attr("data-href"),
                        anchor.attr("data-url"),
                    )
                ) ?: return@mapNotNull null

                if (!isContentDetailUrl(href)) return@mapNotNull null

                val card = findCard(anchor)
                val image = findPosterImage(anchor, card)
                val cardText = (anchor.text() + " " + card?.text().orEmpty())
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val title = firstNonBlank(
                    anchor.attr("title"),
                    image?.attr("alt"),
                    card?.selectFirst("h2,h3,h4,.title,.film-title,.dizi-title")?.text(),
                    cleanCardTitle(anchor.text()),
                    slugToTitle(href),
                ) ?: return@mapNotNull null

                val durationMinutes = extractDurationMinutes(cardText)

                // FilmMakinesi bazı listelerde fragmanları gerçek film linki gibi
                // /film/... altında gösterebiliyor. Fragmanları CloudStream'de
                // normal film olarak göstermemek için başlık/metin + süre kontrolü.
                if (isTrailerCandidate(href, title, cardText, durationMinutes)) {
                    return@mapNotNull null
                }

                val score = extractRating(cardText)

                if (href.contains("/dizi/", ignoreCase = true)) {
                    newTvSeriesSearchResponse(
                        title.trim(),
                        href,
                        TvType.TvSeries,
                    ) {
                        posterUrl = image?.let(::posterUrlOf)
                        score?.let { this.score = Score.from10(it) }
                    }
                } else {
                    newMovieSearchResponse(
                        title.trim(),
                        href,
                        TvType.Movie,
                    ) {
                        posterUrl = image?.let(::posterUrlOf)
                        score?.let { this.score = Score.from10(it) }
                    }
                }
            }
            .distinctBy { it.url }
    }

    /**
     * Kartı mümkün olduğunca yakın tutar. Sayfanın tamamından poster seçilmez;
     * aksi halde yanlış afişler başka filmlere taşınabilir.
     */
    private fun findCard(anchor: Element): Element? {
        if (anchor.selectFirst("img") != null) return anchor

        var current: Element? = anchor
        repeat(5) {
            current = current?.parent()
            val parent = current ?: return null
            if (
                parent.selectFirst("img") != null &&
                parent.selectFirst("a[href]") != null
            ) {
                return parent
            }
        }

        return null
    }

    private fun findPosterImage(anchor: Element, card: Element?): Element? {
        anchor.selectFirst("img")?.let { return it }
        card?.select("img")?.firstOrNull { image ->
            val src = firstNonBlank(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src"),
            ).orEmpty()
            !isBadImage(src)
        }?.let { return it }

        return null
    }

    private fun posterUrlOf(image: Element): String? {
        val src = firstNonBlank(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src"),
        ) ?: return null
        return fixUrlNull(src)
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("data:") ||
            value.contains("logo") ||
            value.contains("avatar") ||
            value.contains("placeholder") ||
            value.contains("default")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
        )

        for (url in urls) {
            val results = runCatching {
                app.get(url, headers = requestHeaders).document
            }.getOrNull()?.let(::parseListPage).orEmpty()

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val normalized = fixUrl(url)
        val document = runCatching {
            app.get(normalized, headers = requestHeaders)
        }.getOrNull()?.document ?: return null

        val pageText = document.text().replace(Regex("\\s+"), " ").trim()
        val isSeries = normalized.contains("/dizi/", ignoreCase = true)

        val title = firstNonBlank(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.title(),
        )?.cleanDetailTitle() ?: return null

        val poster = firstNonBlank(
            document.selectFirst("meta[property='og:image']")?.attr("content"),
            document.selectFirst("meta[name='twitter:image']")?.attr("content"),
            document.select("img[alt]")
                .firstOrNull { it.attr("alt").contains(title, ignoreCase = true) }
                ?.let { posterUrlOf(it) },
            document.selectFirst("img")?.let { posterUrlOf(it) },
        )?.let(::fixUrlNull)

        val originalTitle = document.selectFirst("h2,h3")?.text()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(title, true) }

        val description = firstNonBlank(
            document.selectFirst("meta[name='description']")?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".aciklama")?.text(),
            document.selectFirst(".plot")?.text(),
        )?.trim()

        val year = extractYear(pageText)
        val score = extractRating(pageText)

        val genres = extractGenres(document)
        val actors = extractActors(document)
        val trailer = findTrailer(document)

        if (isSeries) {
            val episodes = extractEpisodes(document, poster)
            return newTvSeriesLoadResponse(
                title,
                normalized,
                TvType.TvSeries,
                episodes,
            ) {
                posterUrl = poster
                plot = buildPlot(originalTitle, description)
                this.year = year
                this.tags = genres
                score?.let { this.score = Score.from10(it) }
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }

        return newMovieLoadResponse(
            title,
            normalized,
            TvType.Movie,
            normalized,
        ) {
            posterUrl = poster
            plot = buildPlot(originalTitle, description)
            this.year = year
            this.tags = genres
            score?.let { this.score = Score.from10(it) }
            addActors(actors)
            trailer?.let { addTrailer(it) }
        }
    }

    private fun buildPlot(originalTitle: String?, description: String?): String? {
        return if (!originalTitle.isNullOrBlank() && !description.isNullOrBlank()) {
            "$originalTitle\n\n$description"
        } else {
            originalTitle ?: description
        }
    }

    private fun extractGenres(document: Document): List<String> {
        val known = setOf(
            "Aksiyon", "Aile", "Animasyon", "Belgesel", "Biyografi",
            "Bilim Kurgu", "Dram", "Fantastik", "Gerilim", "Gizem",
            "Komedi", "Korku", "Macera", "Müzik", "Polisiye",
            "Romantik", "Savaş", "Spor", "Tarih", "Western",
        )

        return document.select("a[href]")
            .map { it.text().trim() }
            .filter { it in known }
            .distinct()
    }

    private fun extractActors(document: Document): List<Actor>? {
        val linked = document.select(
            "a[href*='/oyuncu/'], a[href*='/oyuncular/']"
        )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .map { Actor(it) }

        if (linked.isNotEmpty()) return linked

        // Ayrı actor linki yoksa "Oyuncular" başlığından sonraki yakın blokları tara.
        val heading = document.select("h2,h3,h4,strong,span,div")
            .firstOrNull { it.text().trim().equals("Oyuncular", ignoreCase = true) }
            ?: return null

        val parentText = heading.parent()?.text().orEmpty()
        if (parentText.isBlank()) return null

        val names = parentText
            .substringAfter("Oyuncular", "")
            .split(",", "•", "|")
            .map { it.trim() }
            .filter { it.length in 2..60 }
            .filterNot { it.contains("Tüm Kadroyu", true) }
            .distinctBy { it.lowercase() }

        return names.takeIf { it.isNotEmpty() }?.map { Actor(it) }
    }

    private fun extractEpisodes(document: Document, poster: String?): List<Episode> {
        val result = LinkedHashMap<String, Episode>()

        document.select("a[href], a[data-href], a[data-url]").forEach { anchor ->
            val href = fixUrlNull(
                firstNonBlank(
                    anchor.attr("href"),
                    anchor.attr("data-href"),
                    anchor.attr("data-url"),
                )
            ) ?: return@forEach

            val anchorText = anchor.text().replace(Regex("\\s+"), " ").trim()
            val dataText = listOf(
                anchorText,
                anchor.attr("title"),
                anchor.attr("aria-label"),
                anchor.parent()?.text().orEmpty(),
            ).joinToString(" ")

            val season = extractSeason(dataText, href) ?: return@forEach
            val episode = extractEpisodeNumber(dataText, href) ?: return@forEach

            // Aktör veya normal dizi bağlantısını yanlışlıkla bölüm yapma.
            if (!dataText.contains("Bölüm", true) &&
                !dataText.contains("Episode", true) &&
                !href.contains("bolum", true) &&
                !href.contains("episode", true)
            ) return@forEach

            if (!href.startsWith("$mainUrl/")) return@forEach
            if (isContentDetailUrl(href) && !href.contains("bolum", true) && !href.contains("episode", true)) {
                // Aynı dizi detay linki bölüm değildir.
                return@forEach
            }

            val name = cleanEpisodeName(dataText, season, episode)
            result[href.trimEnd('/')] = newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                posterUrl = poster
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun extractSeason(text: String, url: String): Int? {
        val patterns = listOf(
            Regex("(\\d+)\\s*[.]?\\s*Sezon", RegexOption.IGNORE_CASE),
            Regex("sezon[- ]?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("/(?:s|season)[-_]?(\\d+)", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            pattern.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        val patterns = listOf(
            Regex("(\\d+)\\s*[.]?\\s*Bölüm", RegexOption.IGNORE_CASE),
            Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("bolum[- ]?(\\d+)", RegexOption.IGNORE_CASE),
            Regex("/(?:e|episode)[-_]?(\\d+)", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
            pattern.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun cleanEpisodeName(text: String, season: Int, episode: Int): String {
        val cleaned = text
            .replace(Regex("\\b(?:Yabancı|Yerli)\\s+Dizi\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(?:İzle|Izle)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')

        return if (cleaned.length > 2) {
            cleaned.take(180)
        } else {
            "$season. Sezon $episode. Bölüm"
        }
    }

    /**
     * Genel player çözümleyici:
     * - iframe/embed adreslerini CloudStream extractors'a gönderir
     * - HTML içindeki doğrudan HLS/MP4 adreslerini yakalar
     * - JSON-LD/contentUrl/source/file/video değişkenlerini tarar
     * - VTT/SRT altyazı bağlantılarını toplar
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = runCatching {
            app.get(data, headers = requestHeaders)
        }.getOrNull() ?: return false

        val document = response.document
        val rawHtml = normalizeEmbeddedText(response.text)
        var found = false

        // Altyazıyı yalnızca gerçek player alanındaki <track> elemanlarından al.
        document.select("track[src], track[data-src]").forEach { track ->
            if (isTrailerElement(track)) return@forEach

            val raw = firstNonBlank(
                track.attr("src"),
                track.attr("data-src"),
            ) ?: return@forEach

            val subtitleUrl = fixUrlNull(raw) ?: return@forEach
            val lang = firstNonBlank(
                track.attr("label"),
                track.attr("srclang"),
            )?.let(::subtitleLanguage) ?: "Subtitle"

            subtitleCallback(
                SubtitleFile(
                    lang = lang,
                    url = subtitleUrl,
                )
            )
        }

        // Önce açıkça player/embed/watch olarak işaretlenmiş iframe'leri ara.
        val explicitPlayerCandidates = LinkedHashSet<String>()
        val playerSelectors = listOf(
            "iframe[id*=player i], iframe[class*=player i]",
            "iframe[id*=watch i], iframe[class*=watch i]",
            "iframe[id*=stream i], iframe[class*=stream i]",
            "iframe[id*=embed i], iframe[class*=embed i]",
            "iframe[data-player], iframe[data-video-player]",
            "div[class*=player i] iframe",
            "div[id*=player i] iframe",
            "div[class*=video-container i] iframe",
            "div[class*=video-player i] iframe",
            "div[class*=watch i] iframe",
        )

        for (selector in playerSelectors) {
            document.select(selector).forEach { element ->
                if (isTrailerElement(element)) return@forEach

                firstNonBlank(
                    element.attr("src"),
                    element.attr("data-src"),
                    element.attr("data-url"),
                    element.attr("data-embed"),
                    element.attr("data-player-src"),
                    element.attr("data-embed-url"),
                )?.let { raw ->
                    fixUrlNull(raw)?.let { explicitPlayerCandidates.add(it) }
                }
            }
        }

        // Player selector yoksa genel iframe taraması yapılır. Bu aşamada
        // YouTube/Vimeo gibi tipik fragman hostları kesinlikle elenir.
        val playerCandidates = LinkedHashSet<String>()
        if (explicitPlayerCandidates.isNotEmpty()) {
            playerCandidates.addAll(explicitPlayerCandidates)
        } else {
            document.select("iframe[src], iframe[data-src], iframe[data-url], embed[src]")
                .forEach { element ->
                    if (isTrailerElement(element)) return@forEach

                    val raw = firstNonBlank(
                        element.attr("src"),
                        element.attr("data-src"),
                        element.attr("data-url"),
                    ) ?: return@forEach

                    val candidate = fixUrlNull(raw) ?: return@forEach
                    if (isTrailerCandidateUrl(candidate)) return@forEach
                    playerCandidates.add(candidate)
                }
        }

        // JS içine gömülen provider/embed URL'leri.
        extractEmbeddedPageUrls(rawHtml)
            .filterNot { isTrailerCandidateUrl(it) }
            .forEach { playerCandidates.add(it) }

        // Provider URL'lerini ayrıca ve düşük öncelikle ekle.
        extractProviderUrls(rawHtml)
            .filterNot { isTrailerCandidateUrl(it) }
            .forEach { playerCandidates.add(it) }

        // Gerçek player'ı sırayla çöz. İlk başarılı extractor yeterlidir.
        for (candidate in playerCandidates) {
            if (candidate.isBlank()) continue
            if (sameUrl(candidate, data)) continue

            // Genel taramada tipik fragman sağlayıcılarını atla; explicit player
            // olarak işaretlenmiş gerçek player'a ise izin ver.
            if (
                explicitPlayerCandidates.isEmpty() &&
                isTrailerCandidateUrl(candidate)
            ) continue

            try {
                val extracted = loadExtractor(
                    candidate,
                    data,
                    subtitleCallback,
                    callback,
                )

                if (extracted) {
                    found = true
                    break
                }
            } catch (error: Throwable) {
                Log.w(
                    "FILMMAKINESI",
                    "Player extractor başarısız: $candidate",
                    error,
                )
            }
        }

        // Bilinçli olarak doğrudan <video src>, .mp4 veya .m3u8 fallback'i YOK.
        // Çünkü FilmMakinesi detay sayfasında fragman da medya olarak bulunabiliyor.
        Log.d(
            "FILMMAKINESI",
            "loadLinks data=$data explicitPlayers=${explicitPlayerCandidates.size} candidates=${playerCandidates.size} found=$found",
        )

        return found
    }

    /** Fragman/teaser kaynaklarının yanlışlıkla ana video olarak seçilmesini önler. */
    private fun isTrailerCandidateUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("fragman") ||
            value.contains("trailer") ||
            value.contains("teaser") ||
            value.contains("preview") ||
            value.contains("youtube.com/watch") ||
            value.contains("youtube.com/embed") ||
            value.contains("youtu.be/") ||
            value.contains("youtube-nocookie.com") ||
            value.contains("vimeo.com/")
    }

    /**
     * Yalnızca iframe'in kendisi ve yakın kapsayıcıların kimlik/class/title bilgileri
     * incelenir. Body'nin tamamındaki "Fragman" metnine bakılmaz; aksi halde gerçek
     * film player'ı da yanlışlıkla fragman sanılabilir.
     */
    private fun isTrailerElement(element: Element): Boolean {
        val parts = ArrayList<String>()
        var current: Element? = element
        repeat(5) {
            val item = current ?: return@repeat
            parts.add(item.id())
            parts.add(item.classNames().joinToString(" "))
            parts.add(item.attr("title"))
            parts.add(item.attr("aria-label"))
            parts.add(item.attr("data-name"))
            parts.add(item.attr("data-type"))
            parts.add(item.attr("data-player"))
            current = item.parent()
        }

        val marker = parts.joinToString(" ").lowercase()
        return Regex(
            "\\b(fragman|trailer|teaser|preview|tanıtım|tanitim)\\b",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(marker)
    }

    private fun sameUrl(a: String, b: String): Boolean {
        return a.trimEnd('/') == b.trimEnd('/')
    }

    private fun subtitleLanguage(value: String): String {
        val lower = value.lowercase()
        return when {
            lower.startsWith("tr") || lower.contains("turk") || lower.contains("türk") -> "Turkish"
            lower.startsWith("en") || lower.contains("english") -> "English"
            else -> value.replaceFirstChar { it.uppercase() }
        }
    }

    /** Doğrudan medya URL'si ile çevresindeki HTML metnini birlikte kontrol eder. */
    private fun extractDirectMediaWithContext(html: String): List<String> {
        val patterns = listOf(
            Regex("https?://[^\"'<>\\s]+\\.m3u8(?:\\?[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE),
            Regex("https?://[^\"'<>\\s]+\\.mp4(?:\\?[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE),
        )

        val foundUrls = LinkedHashSet<String>()
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val url = match.value.replace("\\/", "/")
                if (isTrailerCandidateUrl(url)) continue

                val start = maxOf(0, match.range.first - 700)
                val end = minOf(html.length, match.range.last + 700)
                val context = html.substring(start, end).lowercase()

                if (Regex("\\b(fragman|trailer|teaser|preview|tanıtım|tanitim)\\b", RegexOption.IGNORE_CASE).containsMatchIn(context)) {
                    continue
                }

                // Bir duration değeri açıkça varsa 15 dakikadan kısa videoları
                // doğrudan film akışı kabul etme.
                val shortDuration = Regex("(?:duration|length|seconds)\\D{0,15}(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                    .find(context)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?.let { seconds -> seconds in 1.0..900.0 } == true

                if (shortDuration) continue

                if (url.contains(".m3u8", true) || url.contains(".mp4", true)) {
                    foundUrls.add(url)
                }
            }
        }

        return foundUrls.toList()
    }

    private fun normalizeEmbeddedText(text: String): String {
        return text
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
    }

    private fun extractMediaUrls(html: String): List<String> {
        val patterns = listOf(
            Regex("""https?://[^"'<>\s]+\.m3u8(?:\?[^"'<>\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'<>\s]+\.mp4(?:\?[^"'<>\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:file|source|src|contentUrl)["']?\s*[:=]\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
        )

        return patterns
            .flatMap { it.findAll(html).map { match ->
                match.groupValues.last()
            }.toList() }
            .map { it.replace("\\/", "/") }
            .distinct()
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) }
    }

    private fun extractEmbeddedPageUrls(html: String): List<String> {
        val pattern = Regex(
            """https?://[^"'<>\s]+(?:/embed/|/player/|/video/)[^"'<>\s]+""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(html)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
    }

    private fun extractProviderUrls(html: String): List<String> {
        val providerPattern = Regex(
            """https?://[^"'<>\s]*(?:vidmoly|filemoon|streamwish|dood|doodstream|voe|vudeo|mixdrop|upstream|streamtape|vidhide|vidsrc|lulustream|ok\.ru)[^"'<>\s]*""",
            RegexOption.IGNORE_CASE,
        )
        return providerPattern.findAll(html)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()
    }

    private fun extractSubtitleUrls(html: String): List<Pair<String, String>> {
        val pattern = Regex(
            """https?://[^"'<>\s]+\.(?:vtt|srt)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(html)
            .map { match ->
                val url = match.value.replace("\\/", "/")
                val lang = when {
                    Regex("/(?:tr|tur|turk)[_./-]", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "Turkish"
                    Regex("/(?:en|eng)[_./-]", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "English"
                    else -> "Subtitle"
                }
                lang to url
            }
            .distinctBy { it.second }
            .toList()
    }

    private fun detectQuality(url: String): Int {
        val lower = url.lowercase()
        return when {
            "2160" in lower || "4k" in lower -> Qualities.P2160.value
            "1440" in lower -> Qualities.P1440.value
            "1080" in lower -> Qualities.P1080.value
            "720" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun findTrailer(document: Document): String? {
        val candidates = LinkedHashSet<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-url], a[href], source[src]"
        ).forEach { element ->
            firstNonBlank(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("href"),
            )?.let { value ->
                if (
                    element.text().contains("Fragman", true) ||
                    value.contains("youtube", true) ||
                    value.contains("youtu.be", true) ||
                    value.contains("vimeo", true)
                ) {
                    fixUrlNull(value)?.let(candidates::add)
                }
            }
        }

        return candidates.firstOrNull {
            it.contains("youtube", true) ||
                it.contains("youtu.be", true) ||
                it.contains("vimeo", true)
        }
    }

    /** Kart üzerindeki film süresini dakika cinsinden çıkarır. */
    private fun extractDurationMinutes(text: String): Int? {
        Regex(
            "(\\d+)\\s*Saat(?:\\s*(\\d+)\\s*Dakika)?",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { match ->
            val hours = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minutes = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return hours * 60 + minutes
        }

        Regex("(\\d{1,3})\\s*Dakika", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                return it
            }

        return null
    }

    /** Fragman olan liste kartını gerçek film/dizi kartından ayırır. */
    private fun isTrailerCandidate(
        url: String,
        title: String,
        cardText: String,
        durationMinutes: Int?,
    ): Boolean {
        val combined = "$url $title $cardText".lowercase()

        // Açıkça fragman/trailer olarak işaretlenmiş kartlar.
        if (combined.contains("fragman") || combined.contains("trailer")) {
            return true
        }

        // Dizi bölümleri bu kontrolden etkilenmesin.
        if (url.contains("/dizi/", ignoreCase = true)) return false

        // Sitedeki fragman kartları kısa video olarak gelebiliyor.
        // 15 dakikanın altındaki /film/ içerikleri bu listede film olarak
        // göstermiyoruz; normal uzun metraj filmler korunur.
        return durationMinutes != null && durationMinutes <= 15
    }

    private fun extractYear(text: String): Int? {
        val match = Regex("(?:19|20)\\d{2}").find(text) ?: return null
        return match.value.toIntOrNull()
    }

    private fun extractRating(text: String): Float? {
        val explicit = Regex(
            "(?:IMDb|IMDB|Puan|Rating)\\s*[:：]?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        if (explicit != null && explicit in 0f..10f) return explicit

        // Kartlarda puan genellikle HD/Dual etiketinden hemen sonra görünür.
        val cardRating = Regex(
            "(?:HD|CAM|Dual|Dublaj|Altyazılı|Altyazili|Yabancı Dizi|Yerli Dizi|Yerli Film)\\s+([0-9](?:[.,][0-9])?)(?:\\s|$)",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()

        if (cardRating != null && cardRating in 0f..10f) return cardRating

        return Regex("(?<!\\d)([0-9](?:[.,][0-9])?)(?!\\d)")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() }
            .firstOrNull { it in 0f..10f }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextPage = page + 1
        val nextPattern = Regex("(?:/sayfa/$nextPage/|-$nextPage(?:/|$)|[?&]page=$nextPage)")

        return document.select("a[href]").any { anchor ->
            val href = fixUrlNull(anchor.attr("href")).orEmpty()
            nextPattern.containsMatchIn(href) ||
                anchor.text().trim().equals("$nextPage", true)
        }
    }

    private fun isContentDetailUrl(url: String): Boolean {
        val clean = runCatching { URI(url) }.getOrNull() ?: return false
        if (!clean.host.orEmpty().contains("filmmakinesi.to", true)) return false

        val path = clean.path.trimEnd('/')
        return path.matches(Regex("/film/[^/]+")) ||
            path.matches(Regex("/dizi/[^/]+"))
    }

    private fun cleanCardTitle(text: String): String? {
        var value = text.replace(Regex("\\s+"), " ").trim()
        if (value.isBlank()) return null

        value = value
            .replace(
                Regex(
                    "^(?:HD|CAM|HD Dual|HD Altyazılı|HD Altyazili|HD Dublaj|Yabancı Dizi|Yerli Dizi|Yerli Film|Dual|Altyazılı|Altyazili|Dublaj)\\s*",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("^\\d+(?:[.,]\\d+)?\\s*"), "")
            .replace(Regex("^\\d+\\s+"), "")
            .replace(Regex("\\b(?:19|20)\\d{2}\\b"), "")
            .replace(Regex("\\b\\d+\\s+Dakika\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bİzle\\b|\\bIzle\\b", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '|', ':')

        return value.takeIf { it.isNotBlank() }
    }

    private fun slugToTitle(url: String): String? {
        val slug = runCatching { URI(url).path.trimEnd('/').substringAfterLast('/') }.getOrNull()
            ?: return null

        return slug
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\b(?:19|20)\\d{2}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .takeIf { it.isNotBlank() }
    }

    private fun String.cleanDetailTitle(): String {
        return this
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*[-|]\\s*Film Makinesi.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*1080p.*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "Chrome/154.0 Safari/537.36"
    }
}
