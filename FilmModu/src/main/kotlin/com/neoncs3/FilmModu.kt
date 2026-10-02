package com.neoncs3

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class FilmModu : MainAPI() {

    override var mainUrl = "https://www.filmmodu.one"
    override var name = "FilmModu"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val tag = "FilmModu"

    private fun headers(referer: String = mainUrl + "/"): Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
        )

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Son Eklenen Filmler",
        mainUrl + "/hd-populer-filmler" to "Popüler Filmler",
        mainUrl + "/boxset-seri-filmler" to "Seri Filmler",
        mainUrl + "/arsiv-filmler" to "Film Arşivi",
        mainUrl + "/hd-film-kategori/4k-film-izle" to "4K",
        mainUrl + "/hd-film-kategori/aile-filmleri" to "Aile",
        mainUrl + "/hd-film-kategori/aksiyon" to "Aksiyon",
        mainUrl + "/hd-film-kategori/animasyon" to "Animasyon",
        mainUrl + "/hd-film-kategori/belgeseller" to "Belgesel",
        mainUrl + "/hd-film-kategori/bilim-kurgu-filmleri" to "Bilim-Kurgu",
        mainUrl + "/hd-film-kategori/dram-filmleri" to "Dram",
        mainUrl + "/hd-film-kategori/fantastik-filmler" to "Fantastik",
        mainUrl + "/hd-film-kategori/gerilim" to "Gerilim",
        mainUrl + "/hd-film-kategori/gizem-filmleri" to "Gizem",
        mainUrl + "/hd-film-kategori/komedi-filmleri" to "Komedi",
        mainUrl + "/hd-film-kategori/korku-filmleri" to "Korku",
        mainUrl + "/hd-film-kategori/kult-filmler-izle" to "Kült Filmler",
        mainUrl + "/hd-film-kategori/macera-filmleri" to "Macera",
        mainUrl + "/hd-film-kategori/romantik-filmler" to "Romantik",
        mainUrl + "/hd-film-kategori/savas-filmleri" to "Savaş",
        mainUrl + "/hd-film-kategori/suc-filmleri" to "Suç",
        mainUrl + "/hd-film-kategori/tarih" to "Tarih",
        mainUrl + "/hd-film-kategori/tavsiye-filmler" to "Tavsiye Filmler",
        mainUrl + "/hd-film-kategori/vahsi-bati-filmleri" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) base else base + "/?page=" + page

        val document = runCatching {
            app.get(url, headers = headers()).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val results = document.select("div.movie-item, div.movie, .movie-item, .film, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty() && page < 100
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href]")
            ?: return null

        val href = normalizeUrl(anchor.attr("href"), mainUrl)
        if (!href.contains("/film/") && !href.contains("/film-")) return null

        val title = listOf(
            selectFirst("h3, .turkish-name, .original-name, .title, h2, .movie-title")?.text(),
            anchor.attr("title"),
            anchor.text(),
            selectFirst("img")?.attr("alt"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

        if (title.isBlank()) return null

        val poster = normalizeUrl(
            this.selectFirst("picture img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("picture img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: posterFrom(this)
                ?: posterFrom(anchor)
                ?: "",
            mainUrl
        ).takeIf { it.startsWith("http", true) }

        val rating = extractRating(
            anchor.text() + " " + selectFirst(".imdb-rating, .rating")?.text().orEmpty()
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl + "/",
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            rating?.let { score = Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = runCatching {
            app.get(
                mainUrl + "/film-ara?term=" + encoded,
                headers = headers()
            ).document
        }.getOrNull() ?: return emptyList()

        return document.select("div.movie-item, div.movie, .movie-item, .film, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching {
            app.get(url, headers = headers()).document
        }.getOrNull() ?: return null

        val originalTitle = document.selectFirst("div.titles h1, h1")?.text()?.trim()
            ?: return null

        val alternateTitle = document.selectFirst("div.titles h2")?.text()?.trim().orEmpty()
        val title = if (alternateTitle.isNotBlank()) {
            originalTitle + " - " + alternateTitle
        } else {
            originalTitle
        }

        val poster = normalizeUrl(
            listOf(
                document.selectFirst("meta[property='og:image']")?.attr("content"),
                document.selectFirst("img.img-responsive")?.attr("src"),
                document.selectFirst("div.poster img")?.attr("src"),
                document.selectFirst("img")?.attr("data-src"),
                document.selectFirst("img")?.attr("src"),
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            url
        )

        val plot = listOf(
            document.selectFirst("p[itemprop='description']")?.text(),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
            document.selectFirst("div.description p")?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val year = document.selectFirst("span[itemprop='dateCreated']")?.text()
            ?.trim()?.toIntOrNull()
            ?: Regex("""(?<!\d)(?:19|20)\d{2}(?!\d)""")
                .find(document.text())?.value?.toIntOrNull()

        val tags = document.select("div.description a[href*='-kategori/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = document.select("div.description a[href*='-oyuncu-']")
            .mapNotNull {
                val actor = it.selectFirst("span")?.text()?.trim() ?: it.text().trim()
                actor.takeIf { value -> value.isNotBlank() }?.let(::Actor)
            }

        val rating = extractRating(document.text())
        val trailer = document.selectFirst("div.container iframe, iframe[src*='youtube'], iframe[src*='youtu.be']")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it, url) }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            posterHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to mainUrl + "/",
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
            )
            this.plot = plot
            this.year = year
            this.tags = tags
            rating?.let { score = Score.from10(it) }
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageResponse = runCatching {
            app.get(data, headers = headers()).also {
                Log.d(tag, "Film sayfası HTTP=" + it.code)
            }
        }.getOrNull() ?: return false

        val pageHtml = pageResponse.text
        var processed = false

        // Yeni tema: video oynatıcı HTML'si Base64 ile gömülü olabilir.
        val base64Patterns = listOf(
            Regex("""(?:ilkpartkod|partkod|part[\w]*)\s*=\s*['"]([A-Za-z0-9+/=_-]{80,})['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:atob\(|base64_decode\()['"]?([A-Za-z0-9+/=_-]{80,})""", RegexOption.IGNORE_CASE)
        )

        val decodedBlocks = LinkedHashSet<String>()
        for (regex in base64Patterns) {
            regex.findAll(pageHtml).forEach { match ->
                val value = match.groupValues.getOrNull(1).orEmpty()
                decodeBase64Html(value)?.let(decodedBlocks::add)
            }
        }

        for (block in decodedBlocks) {
            val doc = Jsoup.parse(block)
            val iframes = doc.select("iframe[src], video[src], video source[src], source[src]")
            for (element in iframes) {
                val raw = element.attr("src").ifBlank { element.attr("data-src") }
                val mediaUrl = normalizeUrl(raw, data)
                if (mediaUrl.startsWith("http", true)) {
                    if (emitMedia(
                            mediaUrl,
                            data,
                            "FilmModu - Gömülü Oynatıcı",
                            element.attr("label").ifBlank { element.attr("title") },
                            subtitleCallback,
                            callback
                        )
                    ) {
                        processed = true
                    }
                }
            }
        }

        // Klasik FilmModu akışı: alternatif bağlantı -> videoId/videoType -> get-source.
        val alternates = pageResponse.document.select(
            "div.alternates a[href], .alternates a[href], a[href*='alternatif'], a[href*='player']"
        )

        for (alternate in alternates) {
            val altLink = normalizeUrl(alternate.attr("href"), data)
            val altName = alternate.text().trim()

            if (!altLink.startsWith("http", true) ||
                altName.contains("fragman", true)
            ) {
                continue
            }

            val altResponse = runCatching {
                app.get(
                    altLink,
                    headers = headers(data),
                    referer = data,
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            val altText = altResponse.text

            val videoId = Regex(
                """(?:var\s+)?videoId\s*=\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            ).find(altText)?.groupValues?.getOrNull(1)

            val videoType = Regex(
                """(?:var\s+)?videoType\s*=\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            ).find(altText)?.groupValues?.getOrNull(1)

            if (videoId.isNullOrBlank() || videoType.isNullOrBlank()) continue

            val sourceUrl = mainUrl +
                "/get-source?movie_id=" +
                URLEncoder.encode(videoId, "UTF-8") +
                "&type=" +
                URLEncoder.encode(videoType, "UTF-8")

            val sourceResponse = runCatching {
                app.get(
                    sourceUrl,
                    headers = headers(altLink),
                    referer = altLink,
                    allowRedirects = true
                )
            }.getOrNull() ?: continue

            if (!sourceResponse.isSuccessful || sourceResponse.text.isBlank()) continue

            val json = runCatching {
                JSONObject(sourceResponse.text)
            }.getOrNull() ?: continue

            val sourceArray = findSources(json)
            for (source in sourceArray) {
                val src = listOf(
                    source.optString("src"),
                    source.optString("url"),
                    source.optString("file")
                ).firstOrNull { it.isNotBlank() } ?: continue

                val label = listOf(
                    source.optString("label"),
                    source.optString("quality"),
                    source.optString("name")
                ).firstOrNull { it.isNotBlank() }.orEmpty()

                val mediaUrl = normalizeUrl(src, altLink)
                if (mediaUrl.startsWith("http", true)) {
                    if (emitMedia(
                            mediaUrl,
                            altLink,
                            "FilmModu - " + altName,
                            label,
                            subtitleCallback,
                            callback
                        )
                    ) {
                        processed = true
                    }
                }
            }

            emitSubtitlesFromJson(
                json = json,
                referer = altLink,
                defaultLabel = "Türkçe",
                subtitleCallback = subtitleCallback
            )
        }

        // Son yedek: sayfada düz m3u8/mp4/source URL'si varsa doğrudan kullan.
        if (!processed) {
            val directRegex = Regex(
                """https?://[^"'<>\s]+(?:\.m3u8|\.mp4)(?:\?[^"'<>\s]*)?""",
                RegexOption.IGNORE_CASE
            )

            directRegex.findAll(pageHtml)
                .map { it.value }
                .distinct()
                .take(8)
                .forEach { url ->
                    if (emitMedia(
                            url,
                            data,
                            "FilmModu - Doğrudan Kaynak",
                            "",
                            subtitleCallback,
                            callback
                        )
                    ) {
                        processed = true
                    }
                }
        }

        Log.d(tag, "loadLinks tamamlandı processed=" + processed)
        return processed
    }

    private fun findSources(json: JSONObject): List<JSONObject> {
        val result = ArrayList<JSONObject>()

        fun addArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (
                    item.has("src") ||
                    item.has("url") ||
                    item.has("file")
                ) {
                    result.add(item)
                }
            }
        }

        addArray(json.optJSONArray("sources"))

        val data = json.optJSONObject("data")
        addArray(data?.optJSONArray("sources"))

        val resultObject = json.optJSONObject("result")
        addArray(resultObject?.optJSONArray("sources"))

        return result.distinctBy {
            it.optString("src") + "|" +
                it.optString("url") + "|" +
                it.optString("file")
        }
    }

    private suspend fun emitSubtitlesFromJson(
        json: JSONObject,
        referer: String,
        defaultLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val arrays = listOfNotNull(
            json.optJSONArray("tracks"),
            json.optJSONArray("subtitles"),
            json.optJSONObject("data")?.optJSONArray("tracks"),
            json.optJSONObject("data")?.optJSONArray("subtitles")
        )

        for (array in arrays) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = listOf(
                    item.optString("file"),
                    item.optString("src"),
                    item.optString("url")
                ).firstOrNull { it.isNotBlank() } ?: continue

                val label = listOf(
                    item.optString("label"),
                    item.optString("language"),
                    item.optString("lang")
                ).firstOrNull { it.isNotBlank() } ?: defaultLabel

                val full = normalizeUrl(url, referer)
                if (!full.startsWith("http", true)) continue

                runCatching {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = label,
                            url = full
                        ) {
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        }
                    )
                }
            }
        }

        val scalar = listOf(
            json.optString("subtitle"),
            json.optString("subtitle_url"),
            json.optString("subtitleUrl")
        ).firstOrNull { it.isNotBlank() }

        if (!scalar.isNullOrBlank()) {
            val full = normalizeUrl(scalar, referer)
            if (full.startsWith("http", true)) {
                runCatching {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = defaultLabel,
                            url = full
                        ) {
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        }
                    )
                }
            }
        }
    }

    private suspend fun emitMedia(
        rawUrl: String,
        referer: String,
        sourceName: String,
        label: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val mediaUrl = normalizeUrl(rawUrl, referer)
        if (!mediaUrl.startsWith("http", true)) return false

        val lower = mediaUrl.lowercase()
        val type = if (lower.contains(".m3u8") || lower.contains("/hls/")) {
            ExtractorLinkType.M3U8
        } else {
            INFER_TYPE
        }

        val quality = getQualityFromName(label.orEmpty()).takeIf { it > 0 }
            ?: if (lower.contains("2160") || lower.contains("4k")) {
                Qualities.P2160.value
            } else if (lower.contains("1080")) {
                Qualities.P1080.value
            } else if (lower.contains("720")) {
                Qualities.P720.value
            } else {
                Qualities.Unknown.value
            }

        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = mediaUrl,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )

        Log.d(tag, "Video eklendi: " + mediaUrl)
        return true
    }

    private fun decodeBase64Html(value: String): String? {
        return runCatching {
            val cleaned = value
                .replace("\\/", "/")
                .replace("\\", "")
                .replace(Regex("""\s+"""), "")
                .replace("-", "+")
                .replace("_", "/")

            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
                .takeIf { it.contains("<iframe", true) || it.contains("<video", true) || it.contains("<source", true) }
        }.getOrNull()
    }

    private fun posterFrom(element: Element): String? {
        fun firstUsable(values: List<String>): String {
            return values
                .asSequence()
                .map { it.trim() }
                .map { it.substringBefore(" ").trim() }
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.equals("about:blank", true) &&
                        !it.startsWith("data:", true) &&
                        !it.startsWith("javascript:", true)
                }
                .orEmpty()
        }

        val image = element.selectFirst(
            "img[data-src], img[data-lazy-src], img[data-original], img[srcset], img[src]"
        )

        if (image != null) {
            val raw = firstUsable(
                listOf(
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("data-image"),
                    image.attr("data-poster"),
                    image.attr("data-original-src"),
                    image.attr("data-srcset"),
                    image.attr("srcset"),
                    image.attr("src"),
                )
            )

            if (raw.isNotBlank()) {
                return normalizeUrl(raw, mainUrl)
            }
        }

        val source = element.selectFirst("picture source[srcset], source[srcset]")
        if (source != null) {
            val raw = firstUsable(listOf(source.attr("srcset")))
            if (raw.isNotBlank()) {
                return normalizeUrl(raw, mainUrl)
            }
        }

        val dataImage = firstUsable(
            listOf(
                element.attr("data-poster"),
                element.attr("data-image"),
                element.attr("data-bg"),
                element.attr("data-background"),
                element.attr("data-thumb"),
                element.attr("data-src"),
            )
        )
        if (dataImage.isNotBlank()) {
            return normalizeUrl(dataImage, mainUrl)
        }

        val style = element.attr("style")
        val background = Regex(
            """(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)"""
        ).find(style)?.groupValues?.getOrNull(2).orEmpty()

        if (background.isNotBlank()) {
            return normalizeUrl(background, mainUrl)
        }

        val nestedStyle = element.selectFirst("[style*='background-image']")
            ?.attr("style")
            .orEmpty()

        val nestedBackground = Regex(
            """(?i)background-image\s*:\s*url\((['"]?)(.*?)\1\)"""
        ).find(nestedStyle)?.groupValues?.getOrNull(2).orEmpty()

        if (nestedBackground.isNotBlank()) {
            return normalizeUrl(nestedBackground, mainUrl)
        }

        return null
    }

    private fun extractRating(text: String): Double? {
        if (text.isBlank()) return null

        val value = Regex(
            """(?i)IMDb\s*[:/]?\s*(10(?:[.,]0)?|[0-9](?:[.,][0-9])?)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", ".")
            ?.toDoubleOrNull()

        return value?.takeIf { it in 0.0..10.0 }
            ?: Regex("""(?<!\d)(?:19|20)\d{2}\s+([0-9](?:[.,][0-9])?)(?!\d)""")
                .find(text)
                ?.groupValues?.getOrNull(1)
                ?.replace(",", ".")
                ?.toDoubleOrNull()
                ?.takeIf { it in 0.0..10.0 }
    }

    private fun normalizeUrl(raw: String, base: String): String {
        var value = raw
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .trim('"', '\'')

        if (value.isBlank()) return ""

        if (value.startsWith("//")) {
            value = "https:" + value
        }

        if (value.startsWith("/")) {
            val origin = runCatching {
                val uri = URI(base)
                uri.scheme + "://" + uri.host +
                    (uri.port.takeIf { it > 0 }?.let { ":" + it } ?: "")
            }.getOrNull()

            value = (origin ?: mainUrl) + value
        } else if (!value.startsWith("http", true)) {
            value = runCatching {
                URI(base).resolve(value).toString()
            }.getOrDefault(value)
        }

        return value.replace(" ", "%20")
    }
}
