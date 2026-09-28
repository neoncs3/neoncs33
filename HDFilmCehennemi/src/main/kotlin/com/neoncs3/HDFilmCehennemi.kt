// Source recovered from the public upstream repository pinned by TurkSinema NOTICE.
// Upstream: https://github.com/Saloo1575/SalooRepo
// Pinned commit: 184deca182486d85388cffa5caf9ed1f53f387f3
// This is the provider source corresponding to the HDFilmCehennemi module.

package com.neoncs3
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }
    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }
    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        val home: List<SearchResponse>?
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc.toString())
            val document = Jsoup.parse(aa.html)
            home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)
    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()
        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags = document.select("div.post-info-genres a").map { it.text() }
        val year = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val actors = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }
        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) { this.posterUrl = recPosterUrl }
        }
        return if (tvType == TvType.TvSeries) {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            Log.d("HDCH", "Trailer: $trailer")
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.recommendations = recommendations
                addActors(actors); addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            Log.d("HDCH", "Trailer: $trailer")
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.recommendations = recommendations
                addActors(actors); addTrailer(trailer)
            }
        }
    }

    private data class DecodeStep(
        val op: String,
        val a: Int = 0,
        val b: Int = 0,
    )

    private data class ParsedDecoder(
        val steps: List<DecodeStep>,
        val parts: List<String>,
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
    )

    private fun decodeText(value: String): String {
        return value
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
    }

    /**
     * Current HDFilmCehennemi embed format uses an inline dc_xxx decoder.
     * The operation order and values rotate, so do not hard-code one variant.
     */
    private fun parseInlineDecoders(html: String): List<ParsedDecoder> {
        val decoders = mutableListOf<ParsedDecoder>()

        val functionRegex = Regex(
            """function\s+(dc_\w+)\s*\(\s*[\w${'$'}]+\s*\)\s*\{([\s\S]*?)\n\}""",
        )

        for (match in functionRegex.findAll(html)) {
            val functionName = match.groupValues[1]
            val body = match.groupValues[2]

            val callRegex = Regex(
                """${Regex.escape(functionName)}\s*\(\s*\[([^\]]+)\]\s*\)""",
            )
            val call = callRegex.find(html) ?: continue

            val parts = Regex("\\\"([^\\\"]*)\\\"")
                .findAll(call.groupValues[1])
                .map { decodeText(it.groupValues[1]) }
                .toList()

            if (parts.isEmpty()) continue

            val operations = mutableListOf<Pair<Int, DecodeStep>>()

            Regex("""=\s*atob\(\s*result\s*\)""")
                .findAll(body)
                .forEach { operations += it.range.first to DecodeStep("base64") }

            Regex("""result\.split\(''\)\.reverse\(\)\.join\(''\)""")
                .findAll(body)
                .forEach { operations += it.range.first to DecodeStep("reverse") }

            Regex("""\(o\s*-\s*base\s*\+\s*(\d+)\)\s*%\s*26""")
                .findAll(body)
                .forEach { operations += it.range.first to DecodeStep("rot", it.groupValues[1].toIntOrNull() ?: 0) }

            Regex("""charCodeAt\(0\)\s*\+\s*(\d+)""")
                .findAll(body)
                .forEach { operations += it.range.first to DecodeStep("rot", it.groupValues[1].toIntOrNull() ?: 0) }

            Regex("""charCode\s*-\s*\((\d+)\s*%\s*\(i\s*\+\s*(\d+)\)\)""")
                .findAll(body)
                .forEach {
                    operations += it.range.first to DecodeStep(
                        "unmix",
                        it.groupValues[1].toIntOrNull() ?: 0,
                        it.groupValues[2].toIntOrNull() ?: 0,
                    )
                }

            Regex("""(?:var|let|const)\s+acc\s*=\s*(\d+)[\s\S]{0,260}?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256""")
                .findAll(body)
                .forEach {
                    operations += it.range.first to DecodeStep(
                        "xor",
                        it.groupValues[1].toIntOrNull() ?: 0,
                        it.groupValues[2].toIntOrNull() ?: 0,
                    )
                }

            if (operations.isEmpty()) continue

            decoders += ParsedDecoder(
                operations.sortedBy { it.first }.map { it.second },
                parts,
            )
        }

        return decoders
    }

    private fun applyDecodeSteps(parts: List<String>, steps: List<DecodeStep>): String {
        var result = parts.joinToString("")

        for (step in steps) {
            when (step.op) {
                "base64" -> {
                    var padded = result
                    while (padded.length % 4 != 0) padded += "="
                    result = runCatching {
                        String(
                            android.util.Base64.decode(padded, android.util.Base64.NO_WRAP),
                            Charsets.ISO_8859_1,
                        )
                    }.getOrElse { return "" }
                }

                "reverse" -> result = result.reversed()

                "rot" -> {
                    val shift = ((step.a % 26) + 26) % 26
                    result = buildString(result.length) {
                        result.forEach { c ->
                            when {
                                c in 'a'..'z' -> {
                                    val n = (c.code - 'a'.code + shift) % 26
                                    append(('a'.code + n).toChar())
                                }

                                c in 'A'..'Z' -> {
                                    val n = (c.code - 'A'.code + shift) % 26
                                    append(('A'.code + n).toChar())
                                }

                                else -> append(c)
                            }
                        }
                    }
                }

                "unmix" -> {
                    val out = StringBuilder(result.length)
                    for (i in result.indices) {
                        val code = result[i].code.toLong()
                        val decrypted = (code - (step.a.toLong() % (i + step.b)) + 256L) % 256L
                        out.append(decrypted.toInt().toChar())
                    }
                    result = out.toString()
                }

                "xor" -> {
                    var acc = step.a
                    val out = StringBuilder(result.length)
                    for (c in result) {
                        val byte = c.code and 0xFF
                        acc = (acc + step.b) % 256
                        out.append((byte xor acc).toChar())
                        acc = (acc + byte) % 256
                    }
                    result = out.toString()
                }
            }
        }

        return result
    }

    private fun isValidVideoUrl(url: String): Boolean {
        val value = url.trim()
        if (!value.startsWith("https://") && !value.startsWith("http://")) return false
        val lower = value.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.endsWith(".mp4") ||
            lower.contains(".mp4?")
    }

    private fun originOf(url: String): String {
        return runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(mainUrl)
    }

    private fun playerReferer(playerUrl: String, pageUrl: String): String {
        val lower = playerUrl.lowercase()
        return if (lower.contains("rplayer") || lower.contains("playerr") || lower.contains("rapidrame")) {
            "$mainUrl/"
        } else {
            "${originOf(playerUrl)}/"
        }.ifBlank { pageUrl }
    }

    private fun extractCandidateUrls(html: String): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        val normalized = decodeText(html)

        Regex(
            """https?://[^\s\"'<>]+(?:\.m3u8|\.mp4)(?:\?[^\s\"'<>]+)?""",
            RegexOption.IGNORE_CASE,
        ).findAll(normalized).forEach {
            result += it.value.trimEnd(')', ']', '}', ';', ',')
        }

        return result
    }

    private suspend fun emitVideoLink(
        source: String,
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("HDFilmCehennemi", "VIDEO URL: $mediaUrl")

        callback(
            newExtractorLink(
                source = source,
                name = source,
                url = mediaUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.headers = mapOf(
                    "User-Agent" to browserHeaders["User-Agent"].orEmpty(),
                    "Accept" to "*/*",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                )
                quality = Qualities.Unknown.value
            },
        )
    }

    private suspend fun addPlayerSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        Regex(
            """(?:tracks|captions)\s*[:=]\s*\[([\s\S]*?)\]""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let { block ->
            val trackRegex = Regex(
                """(?:file|src)\s*[:=]\s*[\"']([^\"']+)[\"'][\s\S]{0,180}?(?:label|language)\s*[:=]\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE,
            )

            for (match in trackRegex.findAll(block)) {
                val subtitleUrl = runCatching { fixUrlNull(decodeText(match.groupValues[1])) }.getOrNull()
                    ?: continue
                subtitleCallback(
                    newSubtitleFile(
                        match.groupValues.getOrNull(2).orEmpty().ifBlank { "Türkçe" },
                        subtitleUrl,
                    ),
                )
            }
        }
    }

    private suspend fun extractFromPlayer(
        source: String,
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("HDFilmCehennemi", "Player URL: $playerUrl")

        val referer = playerReferer(playerUrl, pageUrl)
        val response = runCatching {
            app.get(
                playerUrl,
                headers = browserHeaders,
                referer = referer,
                allowRedirects = true,
                interceptor = interceptor,
            )
        }.getOrNull() ?: run {
            Log.e("HDFilmCehennemi", "Player isteği başarısız: $playerUrl")
            return false
        }

        val html = response.text
        if (html.isBlank()) {
            Log.e("HDFilmCehennemi", "Player HTML boş: $playerUrl")
            return false
        }

        Log.d("HDFilmCehennemi", "Player HTML length=${html.length}")

        addPlayerSubtitles(html, subtitleCallback)

        // 1) Current inline decoder format.
        val decoders = parseInlineDecoders(html)
        Log.d("HDFilmCehennemi", "Inline decoder count=${decoders.size}")

        for ((index, decoder) in decoders.withIndex()) {
            val decoded = runCatching {
                applyDecodeSteps(decoder.parts, decoder.steps)
            }.getOrDefault("")

            Log.d(
                "HDFilmCehennemi",
                "Decoder #$index result=${decoded.take(180)}",
            )

            if (isValidVideoUrl(decoded)) {
                emitVideoLink(source, decoded.trim(), referer, callback)
                return true
            }
        }

        // 2) Packed JavaScript fallback.
        val unpacked = runCatching { getAndUnpack(html) }.getOrNull()
        if (!unpacked.isNullOrBlank() && unpacked != html) {
            val packedDecoders = parseInlineDecoders(unpacked)
            Log.d("HDFilmCehennemi", "Unpacked decoder count=${packedDecoders.size}")

            for ((index, decoder) in packedDecoders.withIndex()) {
                val decoded = runCatching {
                    applyDecodeSteps(decoder.parts, decoder.steps)
                }.getOrDefault("")

                Log.d(
                    "HDFilmCehennemi",
                    "Unpacked decoder #$index result=${decoded.take(180)}",
                )

                if (isValidVideoUrl(decoded)) {
                    emitVideoLink(source, decoded.trim(), referer, callback)
                    return true
                }
            }
        }

        // 3) Direct media URLs already present in the HTML.
        for (candidate in extractCandidateUrls(html)) {
            emitVideoLink(source, candidate, referer, callback)
            return true
        }

        // 4) JSON-LD is only a final fallback. The site frequently returns a stale
        //    playmix/master.txt URL here, so accept it only when it actually contains HLS.
        val jsonLdContentUrl = Regex(
            """[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)?.let(::decodeText)

        if (!jsonLdContentUrl.isNullOrBlank()) {
            Log.d("HDFilmCehennemi", "JSON-LD contentUrl=$jsonLdContentUrl")

            val isPlaylistLike = jsonLdContentUrl.contains(".m3u8", ignoreCase = true)
            if (isPlaylistLike) {
                emitVideoLink(source, jsonLdContentUrl, referer, callback)
                return true
            }

            // Some old pages used master.txt for an HLS playlist. Verify it before emitting.
            val probe = runCatching {
                app.get(
                    jsonLdContentUrl,
                    headers = browserHeaders,
                    referer = referer,
                    allowRedirects = true,
                    interceptor = interceptor,
                ).text
            }.getOrDefault("")

            if (probe.contains("#EXTM3U")) {
                emitVideoLink(source, jsonLdContentUrl, referer, callback)
                return true
            }
        }

        Log.e("HDFilmCehennemi", "Video URL çıkarılamadı: $playerUrl")
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageDocument = runCatching {
            app.get(
                data,
                headers = browserHeaders,
                referer = "$mainUrl/",
                interceptor = interceptor,
            ).document
        }.getOrNull() ?: return false

        var found = false

        pageDocument.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase().ifBlank { "TR" }

            element.select("button.alternative-link").forEach { button ->
                val source = "${button.text().replace("(HDrip Xbet)", "").trim()} $langCode".trim()
                val videoId = button.attr("data-video").trim()
                if (videoId.isBlank()) return@forEach

                val apiGet = runCatching {
                    app.get(
                        "${mainUrl}/video/$videoId/",
                        interceptor = interceptor,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch",
                        ),
                        referer = data,
                    ).text
                }.getOrNull() ?: return@forEach

                val iframe = Regex(
                    """data-src\s*=\s*\\?[\"']([^\"']+)""",
                    RegexOption.IGNORE_CASE,
                ).find(apiGet)?.groupValues?.getOrNull(1)?.let(::decodeText)
                    ?: runCatching {
                        Jsoup.parse(apiGet)
                            .selectFirst("iframe")
                            ?.attr("data-src")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                    ?: return@forEach

                val playerCandidates = LinkedHashSet<String>()

                val normalizedIframe = fixUrlNull(iframe) ?: iframe
                playerCandidates += normalizedIframe

                val rapidrameId = Regex(
                    """rapidrame_id=([^&\"']+)""",
                    RegexOption.IGNORE_CASE,
                ).find(iframe)?.groupValues?.getOrNull(1)

                if (!rapidrameId.isNullOrBlank()) {
                    playerCandidates += "${mainUrl}/rplayer/$rapidrameId/"
                    playerCandidates += "${mainUrl}/playerr/$rapidrameId"
                }

                val rplayerMatch = Regex(
                    """/(?:rplayer|playerr)/([^/?#]+)""",
                    RegexOption.IGNORE_CASE,
                ).find(iframe)

                if (rplayerMatch != null) {
                    val id = rplayerMatch.groupValues[1]
                    playerCandidates += "${mainUrl}/rplayer/$id/"
                    playerCandidates += "${mainUrl}/playerr/$id"
                }

                if (iframe.contains("mobi", ignoreCase = true)) {
                    Jsoup.parse(apiGet)
                        .select("iframe[src], iframe[data-src]")
                        .mapNotNull { frame ->
                            frame.attr("data-src").ifBlank { frame.attr("src") }
                                .takeIf { it.isNotBlank() }
                        }
                        .mapNotNull { fixUrlNull(it) }
                        .forEach(playerCandidates::add)
                }

                for (playerUrl in playerCandidates) {
                    if (extractFromPlayer(source, playerUrl, data, subtitleCallback, callback)) {
                        found = true
                        break
                    }
                }
            }
        }

        Log.d("HDFilmCehennemi", "loadLinks result=$found")
        return found
    }

    private data class SubSource(@JsonProperty("file") val file: String?=null, @JsonProperty("label") val label: String?=null, @JsonProperty("language") val language: String?=null, @JsonProperty("kind") val kind: String?=null)
    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
    data class HDFC(@JsonProperty("html") val html: String, @JsonProperty("meta") val meta: Meta)
    data class Meta(@JsonProperty("title") val title: String, @JsonProperty("canonical") val canonical: String, @JsonProperty("keywords") val keywords: Boolean)
}
