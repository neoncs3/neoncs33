// Source recovered from the public upstream repository pinned by TurkSinema NOTICE.
// Upstream: https://github.com/Saloo1575/SalooRepo
// Pinned commit: 184deca182486d85388cffa5caf9ed1f53f387f3
//
// Local playback fixes:
// - Current inline dc_xxx decoder support.
// - Packed JS fallback.
// - JSON-LD contentUrl is provisional / last resort.
// - HLS playlist is actually inspected before emitting.
// - Short movie streams are rejected.
// - Alternative players are tried when the first one is short/dead.
// - Close player receives embed Referer + Origin.
// - Rapidrame uses the main site origin.
// - MP4 is not incorrectly marked as M3U8.
// - newSubtitleFile is only called inside suspend context.
// - No experimental break/continue in inline lambdas.

package com.neoncs3

import android.util.Base64
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

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy {
        CloudflareKiller()
    }

    private val interceptor by lazy {
        CloudflareInterceptor(cloudflareKiller)
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {

        override fun intercept(
            chain: Interceptor.Chain
        ): Response {

            val request = chain.request()
            val response = chain.proceed(request)

            val doc = Jsoup.parse(
                response.peekBody(1024 * 1024).string()
            )

            if (
                doc.html().contains(
                    "Just a moment"
                )
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/" to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/" to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/" to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/" to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/" to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val objectMapper = ObjectMapper()
            .registerModule(
                KotlinModule.Builder().build()
            )

        objectMapper.configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )

        val url =
            request.data.replace(
                "sayfano",
                page.toString()
            )

        val headers = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "user-agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*",
            "X-Requested-With" to "fetch"
        )

        val doc = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            interceptor = interceptor
        )

        if (
            !doc.toString().contains(
                "Sayfa Bulunamadı"
            )
        ) {

            val aa: HDFC =
                objectMapper.readValue(
                    doc.toString()
                )

            val document =
                Jsoup.parse(
                    aa.html
                )

            val home =
                document
                    .select("a")
                    .mapNotNull {
                        it.toSearchResult()
                    }

            return newHomePageResponse(
                request.name,
                home
            )
        }

        return newHomePageResponse(
            request.name,
            emptyList()
        )
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val title =
            attr("title")

        val href =
            fixUrlNull(
                attr("href")
            ) ?: return null

        val posterUrl =
            fixUrlNull(
                selectFirst("img")
                    ?.attr("data-src")
            )

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val response =
            app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf(
                    "X-Requested-With" to "fetch"
                )
            ).parsedSafe<Results>()
                ?: return emptyList()

        val searchResults =
            mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->

            val document =
                Jsoup.parse(
                    resultHtml
                )

            val title =
                document
                    .selectFirst("h4.title")
                    ?.text()
                    ?: return@forEach

            val href =
                fixUrlNull(
                    document
                        .selectFirst("a")
                        ?.attr("href")
                ) ?: return@forEach

            val posterUrl =
                fixUrlNull(
                    document
                        .selectFirst("img")
                        ?.attr("src")
                )
                    ?: fixUrlNull(
                        document
                            .selectFirst("img")
                            ?.attr("data-src")
                    )

            searchResults.add(
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl =
                        posterUrl?.replace(
                            "/thumb/",
                            "/list/"
                        )
                }
            )
        }

        return searchResults
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(
                url,
                interceptor = interceptor
            ).document

        val title =
            document
                .selectFirst("h1.section-title")
                ?.text()
                ?.substringBefore(" izle")
                ?: return null

        val poster =
            fixUrlNull(
                document
                    .select("aside.post-info-poster img.lazyload")
                    .lastOrNull()
                    ?.attr("data-src")
            )

        val tags =
            document
                .select("div.post-info-genres a")
                .map {
                    it.text()
                }

        val year =
            document
                .selectFirst("div.post-info-year-country a")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val tvType =
            if (
                document
                    .select("div.seasons")
                    .isEmpty()
            ) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

        val description =
            document
                .selectFirst(
                    "article.post-info-content > p"
                )
                ?.text()
                ?.trim()

        val actors =
            document
                .select(
                    "div.post-info-cast a"
                )
                .mapNotNull {

                    val actorName =
                        it
                            .selectFirst("strong")
                            ?.text()
                            ?: return@mapNotNull null

                    Actor(
                        actorName,
                        it
                            .select("img")
                            .attr("data-src")
                    )
                }

        val recommendations =
            document
                .select(
                    "div.section-slider-container div.slider-slide"
                )
                .mapNotNull {

                    val recName =
                        it
                            .selectFirst("a")
                            ?.attr("title")
                            ?: return@mapNotNull null

                    val recHref =
                        fixUrlNull(
                            it
                                .selectFirst("a")
                                ?.attr("href")
                        )
                            ?: return@mapNotNull null

                    val recPosterUrl =
                        fixUrlNull(
                            it
                                .selectFirst("img")
                                ?.attr("data-src")
                        )
                            ?: fixUrlNull(
                                it
                                    .selectFirst("img")
                                    ?.attr("src")
                            )

                    newTvSeriesSearchResponse(
                        recName,
                        recHref,
                        TvType.TvSeries
                    ) {
                        this.posterUrl =
                            recPosterUrl
                    }
                }

        val trailer =
            document
                .selectFirst(
                    "div.post-info-trailer button"
                )
                ?.attr("data-modal")
                ?.substringAfter(
                    "trailer/",
                    ""
                )
                ?.let {
                    if (
                        it.isNotEmpty()
                    ) {
                        "https://www.youtube.com/watch?v=$it"
                    } else {
                        null
                    }
                }

        Log.d(
            "HDCH",
            "Trailer: $trailer"
        )

        return if (
            tvType == TvType.TvSeries
        ) {

            val episodes =
                document
                    .select(
                        "div.seasons-tab-content a"
                    )
                    .mapNotNull {

                        val epName =
                            it
                                .selectFirst("h4")
                                ?.text()
                                ?.trim()
                                ?: return@mapNotNull null

                        val epHref =
                            fixUrlNull(
                                it.attr("href")
                            )
                                ?: return@mapNotNull null

                        val epEpisode =
                            Regex(
                                """(\d+)\. ?Bölüm"""
                            )
                                .find(epName)
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()

                        val epSeason =
                            Regex(
                                """(\d+)\. ?Sezon"""
                            )
                                .find(epName)
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()
                                ?: 1

                        newEpisode(
                            epHref
                        ) {

                            this.name =
                                epName

                            this.season =
                                epSeason

                            this.episode =
                                epEpisode
                        }
                    }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                this.posterUrl =
                    poster

                this.year =
                    year

                this.plot =
                    description

                this.tags =
                    tags

                this.recommendations =
                    recommendations

                addActors(
                    actors
                )

                addTrailer(
                    trailer
                )
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                this.posterUrl =
                    poster

                this.year =
                    year

                this.plot =
                    description

                this.tags =
                    tags

                this.recommendations =
                    recommendations

                addActors(
                    actors
                )

                addTrailer(
                    trailer
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // DECODER
    // -------------------------------------------------------------------------

    private data class DecodeStep(
        val op: String,
        val a: Int = 0,
        val b: Int = 0
    )

    private data class ParsedDecoder(
        val steps: List<DecodeStep>,
        val parts: List<String>
    )

    private val browserHeaders =
        mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to
                "tr-TR,tr;q=0.9,en;q=0.8"
        )

    private fun decodeText(
        value: String
    ): String {

        return value
            .replace("\\/", "/")
            .replace(
                "\\u002F",
                "/",
                ignoreCase = true
            )
            .replace(
                "\\u003A",
                ":",
                ignoreCase = true
            )
            .replace(
                "\\u0026",
                "&",
                ignoreCase = true
            )
            .replace(
                "&amp;",
                "&",
                ignoreCase = true
            )
            .replace(
                "&quot;",
                "\"",
                ignoreCase = true
            )
            .trim()
    }

    private fun parseInlineDecoders(
        html: String
    ): List<ParsedDecoder> {

        val decoders =
            mutableListOf<ParsedDecoder>()

        val functionRegex =
            Regex(
                """function\s+(dc_\w+)\s*\(\s*[\w${'$'}]+\s*\)\s*\{([\s\S]*?)\n\}"""
            )

        for (
            match in functionRegex.findAll(html)
        ) {

            val functionName =
                match.groupValues[1]

            val body =
                match.groupValues[2]

            val callRegex =
                Regex(
                    """${Regex.escape(functionName)}\s*\(\s*\[([^\]]+)\]\s*\)"""
                )

            val call =
                callRegex.find(html)
                    ?: continue

            val parts =
                Regex(
                    "\\\"([^\\\"]*)\\\""
                )
                    .findAll(
                        call.groupValues[1]
                    )
                    .map {
                        decodeText(
                            it.groupValues[1]
                        )
                    }
                    .toList()

            if (
                parts.isEmpty()
            ) {
                continue
            }

            val operations =
                mutableListOf<Pair<Int, DecodeStep>>()

            Regex(
                """=\s*atob\(\s*result\s*\)"""
            )
                .findAll(body)
                .forEach {
                    operations +=
                        it.range.first to
                            DecodeStep(
                                "base64"
                            )
                }

            Regex(
                """result\.split\(''\)\.reverse\(\)\.join\(''\)"""
            )
                .findAll(body)
                .forEach {
                    operations +=
                        it.range.first to
                            DecodeStep(
                                "reverse"
                            )
                }

            Regex(
                """\(o\s*-\s*base\s*\+\s*(\d+)\)\s*%\s*26"""
            )
                .findAll(body)
                .forEach {

                    operations +=
                        it.range.first to
                            DecodeStep(
                                "rot",
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0
                            )
                }

            Regex(
                """charCodeAt\(0\)\s*\+\s*(\d+)"""
            )
                .findAll(body)
                .forEach {

                    operations +=
                        it.range.first to
                            DecodeStep(
                                "rot",
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0
                            )
                }

            Regex(
                """charCode\s*-\s*\((\d+)\s*%\s*\(i\s*\+\s*(\d+)\)\)"""
            )
                .findAll(body)
                .forEach {

                    operations +=
                        it.range.first to
                            DecodeStep(
                                "unmix",
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0,
                                it.groupValues[2]
                                    .toIntOrNull()
                                    ?: 0
                            )
                }

            Regex(
                """(?:var|let|const)\s+acc\s*=\s*(\d+)[\s\S]{0,260}?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256"""
            )
                .findAll(body)
                .forEach {

                    operations +=
                        it.range.first to
                            DecodeStep(
                                "xor",
                                it.groupValues[1]
                                    .toIntOrNull()
                                    ?: 0,
                                it.groupValues[2]
                                    .toIntOrNull()
                                    ?: 0
                            )
                }

            if (
                operations.isEmpty()
            ) {
                continue
            }

            decoders +=
                ParsedDecoder(
                    operations
                        .sortedBy {
                            it.first
                        }
                        .map {
                            it.second
                        },
                    parts
                )
        }

        return decoders
    }

    private fun applyDecodeSteps(
        parts: List<String>,
        steps: List<DecodeStep>
    ): String {

        var result =
            parts.joinToString("")

        for (
            step in steps
        ) {

            when (
                step.op
            ) {

                "base64" -> {

                    var padded =
                        result

                    while (
                        padded.length % 4 != 0
                    ) {
                        padded += "="
                    }

                    result =
                        runCatching {

                            String(
                                Base64.decode(
                                    padded,
                                    Base64.NO_WRAP
                                ),
                                Charsets.ISO_8859_1
                            )

                        }.getOrElse {
                            return ""
                        }
                }

                "reverse" -> {
                    result =
                        result.reversed()
                }

                "rot" -> {

                    val shift =
                        ((step.a % 26) + 26) % 26

                    result =
                        buildString(
                            result.length
                        ) {

                            result.forEach { c ->

                                when {

                                    c in 'a'..'z' -> {

                                        val n =
                                            (
                                                c.code -
                                                    'a'.code +
                                                    shift
                                                ) % 26

                                        append(
                                            (
                                                'a'.code +
                                                    n
                                                ).toChar()
                                        )
                                    }

                                    c in 'A'..'Z' -> {

                                        val n =
                                            (
                                                c.code -
                                                    'A'.code +
                                                    shift
                                                ) % 26

                                        append(
                                            (
                                                'A'.code +
                                                    n
                                                ).toChar()
                                        )
                                    }

                                    else -> {
                                        append(c)
                                    }
                                }
                            }
                        }
                }

                "unmix" -> {

                    if (
                        step.b <= 0
                    ) {
                        return ""
                    }

                    val out =
                        StringBuilder(
                            result.length
                        )

                    for (
                        i in result.indices
                    ) {

                        val code =
                            result[i]
                                .code
                                .toLong()

                        val divisor =
                            i + step.b

                        if (
                            divisor <= 0
                        ) {
                            return ""
                        }

                        val decrypted =
                            (
                                code -
                                    (
                                        step.a.toLong() %
                                            divisor
                                        ) +
                                    256L
                                ) % 256L

                        out.append(
                            decrypted
                                .toInt()
                                .toChar()
                        )
                    }

                    result =
                        out.toString()
                }

                "xor" -> {

                    var acc =
                        step.a

                    val out =
                        StringBuilder(
                            result.length
                        )

                    for (
                        c in result
                    ) {

                        val byte =
                            c.code and 0xFF

                        acc =
                            (
                                acc +
                                    step.b
                                ) % 256

                        out.append(
                            (
                                byte xor
                                    acc
                                ).toChar()
                        )

                        acc =
                            (
                                acc +
                                    byte
                                ) % 256
                    }

                    result =
                        out.toString()
                }
            }
        }

        return result
    }

    // -------------------------------------------------------------------------
    // URL / MEDIA HELPERS
    // -------------------------------------------------------------------------

    private fun isValidVideoUrl(
        url: String
    ): Boolean {

        val value =
            url.trim()

        if (
            !value.startsWith(
                "https://"
            ) &&
            !value.startsWith(
                "http://"
            )
        ) {
            return false
        }

        val lower =
            value.lowercase()

        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.contains("master.txt") ||
            lower.endsWith(".mp4") ||
            lower.contains(".mp4?")
    }

    private fun isHlsLike(
        url: String
    ): Boolean {

        val lower =
            url.lowercase()

        return lower.contains(".m3u8") ||
            lower.contains("/hls/") ||
            lower.contains("/hls2/") ||
            lower.contains("master.txt")
    }

    private fun originOf(
        url: String
    ): String {

        return runCatching {

            val uri =
                java.net.URI(url)

            val scheme =
                uri.scheme

            val host =
                uri.host

            if (
                scheme.isNullOrBlank() ||
                host.isNullOrBlank()
            ) {
                ""
            } else {
                "$scheme://$host"
            }

        }.getOrDefault("")
    }

    private fun resolveMediaOrigin(
        source: String,
        playerUrl: String
    ): String {

        val isRapidrame =
            source.contains(
                "rapidrame",
                ignoreCase = true
            ) ||
                playerUrl.contains(
                    "/rplayer/",
                    ignoreCase = true
                ) ||
                playerUrl.contains(
                    "/playerr/",
                    ignoreCase = true
                ) ||
                playerUrl.contains(
                    "rapidrame_id=",
                    ignoreCase = true
                )

        if (
            isRapidrame
        ) {
            return originOf(
                mainUrl
            )
        }

        val playerOrigin =
            originOf(
                playerUrl
            )

        if (
            playerOrigin.isNotBlank()
        ) {
            return playerOrigin
        }

        return originOf(
            mainUrl
        )
    }

    private fun mediaHeaders(
        referer: String,
        origin: String
    ): Map<String, String> {

        return buildMap {

            put(
                "User-Agent",
                browserHeaders["User-Agent"].orEmpty()
            )

            put(
                "Accept",
                "*/*"
            )

            put(
                "Accept-Language",
                "tr-TR,tr;q=0.9,en;q=0.8"
            )

            if (
                referer.isNotBlank()
            ) {
                put(
                    "Referer",
                    referer
                )
            }

            if (
                origin.isNotBlank()
            ) {
                put(
                    "Origin",
                    origin
                )
            }
        }
    }

    private fun cleanUrl(
        value: String
    ): String {

        return decodeText(
            value
        )
            .trim()
            .trimEnd(
                ')',
                ']',
                '}',
                ';',
                ','
            )
    }

    private fun extractCandidateUrls(
        html: String
    ): LinkedHashSet<String> {

        val result =
            LinkedHashSet<String>()

        val normalized =
            decodeText(html)

        Regex(
            """https?://[^\s\"'<>]+(?:\.m3u8|\.mp4|master\.txt)(?:\?[^\s\"'<>]+)?""",
            RegexOption.IGNORE_CASE
        )
            .findAll(normalized)
            .forEach {

                result +=
                    cleanUrl(
                        it.value
                    )
            }

        return result
    }

    // -------------------------------------------------------------------------
    // HLS PROBE
    // -------------------------------------------------------------------------

    private data class MediaProbe(
        val type: ExtractorLinkType,
        val durationSeconds: Double?,
        val isVod: Boolean,
        val isMaster: Boolean
    )

    /**
     * Reads #EXTINF durations from an HLS media playlist.
     */
    private fun parseHlsDuration(
        playlist: String
    ): Double {

        var total =
            0.0

        Regex(
            """#EXTINF:([0-9]+(?:\.[0-9]+)?)"""
        )
            .findAll(
                playlist
            )
            .forEach {

                total +=
                    it.groupValues[1]
                        .toDoubleOrNull()
                        ?: 0.0
            }

        return total
    }

    private fun isHlsMaster(
        playlist: String
    ): Boolean {
        return playlist.contains(
            "#EXT-X-STREAM-INF",
            ignoreCase = true
        )
    }

    private fun findBestHlsVariant(
        playlist: String,
        baseUrl: String
    ): String? {

        val regex =
            Regex(
                """#EXT-X-STREAM-INF:([^\r\n]*)\r?\n\s*([^\s#][^\r\n]*)""",
                RegexOption.IGNORE_CASE
            )

        var bestUrl: String? = null
        var bestBandwidth =
            -1L

        for (
            match in regex.findAll(
                playlist
            )
        ) {

            val attributes =
                match.groupValues[1]

            val relativeUrl =
                match.groupValues[2]
                    .trim()

            val bandwidth =
                Regex(
                    """BANDWIDTH=(\d+)"""
                )
                    .find(
                        attributes
                    )
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull()
                    ?: 0L

            val absoluteUrl =
                runCatching {

                    java.net.URI(
                        baseUrl
                    )
                        .resolve(
                            relativeUrl
                        )
                        .toString()

                }.getOrNull()

            if (
                absoluteUrl != null &&
                bandwidth >= bestBandwidth
            ) {

                bestBandwidth =
                    bandwidth

                bestUrl =
                    absoluteUrl
            }
        }

        return bestUrl
    }

    /**
     * Downloads only the playlist, never the complete MP4.
     *
     * For HLS:
     *   - verifies #EXTM3U
     *   - follows the best master variant once
     *   - calculates VOD duration from #EXTINF
     *
     * For MP4:
     *   - only accepts the URL syntactically
     *   - avoids downloading the complete file in the plugin.
     */
    private suspend fun probeMedia(
        mediaUrl: String,
        referer: String,
        origin: String
    ): MediaProbe? {

        val url =
            cleanUrl(
                mediaUrl
            )

        if (
            !isValidVideoUrl(
                url
            )
        ) {
            return null
        }

        if (
            isHlsLike(
                url
            )
        ) {

            val headers =
                mediaHeaders(
                    referer,
                    origin
                )

            val playlist =
                runCatching {

                    app.get(
                        url,
                        headers = headers,
                        referer = referer,
                        allowRedirects = true
                    ).text

                }.getOrNull()
                    ?: run {

                        Log.w(
                            "HDFilmCehennemi",
                            "HLS erişilemedi: $url"
                        )

                        return null
                    }

            if (
                !playlist.contains(
                    "#EXTM3U",
                    ignoreCase = true
                )
            ) {

                Log.w(
                    "HDFilmCehennemi",
                    "HLS playlist değil: $url"
                )

                return null
            }

            val master =
                isHlsMaster(
                    playlist
                )

            if (
                master
            ) {

                val variant =
                    findBestHlsVariant(
                        playlist,
                        url
                    )

                if (
                    variant != null
                ) {

                    val variantProbe =
                        runCatching {

                            app.get(
                                variant,
                                headers = headers,
                                referer = referer,
                                allowRedirects = true
                            ).text

                        }.getOrNull()

                    if (
                        !variantProbe.isNullOrBlank() &&
                        variantProbe.contains(
                            "#EXTM3U",
                            ignoreCase = true
                        )
                    ) {

                        val duration =
                            parseHlsDuration(
                                variantProbe
                            )

                        val vod =
                            variantProbe.contains(
                                "#EXT-X-ENDLIST",
                                ignoreCase = true
                            )

                        Log.d(
                            "HDFilmCehennemi",
                            "HLS master -> variant duration=${duration}s vod=$vod url=$url"
                        )

                        return MediaProbe(
                            ExtractorLinkType.M3U8,
                            duration.takeIf {
                                it > 0.0
                            },
                            vod,
                            true
                        )
                    }
                }

                Log.d(
                    "HDFilmCehennemi",
                    "HLS master doğrulandı fakat varyant süresi okunamadı: $url"
                )

                return MediaProbe(
                    ExtractorLinkType.M3U8,
                    null,
                    false,
                    true
                )
            }

            val duration =
                parseHlsDuration(
                    playlist
                )

            val vod =
                playlist.contains(
                    "#EXT-X-ENDLIST",
                    ignoreCase = true
                )

            Log.d(
                "HDFilmCehennemi",
                "HLS media duration=${duration}s vod=$vod url=$url"
            )

            return MediaProbe(
                ExtractorLinkType.M3U8,
                duration.takeIf {
                    it > 0.0
                },
                vod,
                false
            )
        }

        if (
            url.lowercase().endsWith(".mp4") ||
            url.lowercase().contains(".mp4?")
        ) {

            Log.d(
                "HDFilmCehennemi",
                "Direct MP4 candidate accepted without full download: $url"
            )

            return MediaProbe(
                ExtractorLinkType.VIDEO,
                null,
                true,
                false
            )
        }

        return null
    }

    // -------------------------------------------------------------------------
    // CANDIDATE SCORING
    // -------------------------------------------------------------------------

    private data class Candidate(
        val url: String,
        val source: String,
        val referer: String,
        val origin: String,
        val provisional: Boolean = false
    )

    /**
     * Short streams are usually previews/trailers.
     *
     * For movies we intentionally reject <= 5 minutes.
     * Series episodes are allowed down to 60 seconds because some episodes
     * can legitimately be short.
     */
    private fun passesDurationFilter(
        durationSeconds: Double?,
        isMovie: Boolean
    ): Boolean {

        if (
            durationSeconds == null ||
            durationSeconds <= 0.0
        ) {
            return true
        }

        return if (
            isMovie
        ) {
            durationSeconds >= 300.0
        } else {
            durationSeconds >= 60.0
        }
    }

    private fun candidateScore(
        probe: MediaProbe,
        provisional: Boolean,
        isMovie: Boolean
    ): Int {

        var score = 0

        if (
            provisional
        ) {
            score -= 1000
        }

        if (
            probe.type ==
            ExtractorLinkType.M3U8
        ) {
            score += 400
        }

        if (
            probe.isVod
        ) {
            score += 300
        }

        val duration =
            probe.durationSeconds

        if (
            duration != null &&
            duration > 0.0
        ) {

            when {

                isMovie &&
                    duration >= 3600.0 ->
                    score += 1200

                isMovie &&
                    duration >= 1800.0 ->
                    score += 900

                isMovie &&
                    duration >= 900.0 ->
                    score += 700

                duration >= 600.0 ->
                    score += 500

                duration >= 300.0 ->
                    score += 300

                duration >= 120.0 ->
                    score += 50

                else ->
                    score -= 1000
            }
        }

        if (
            probe.isMaster
        ) {
            score += 100
        }

        return score
    }

    private suspend fun chooseBestCandidate(
        candidates: List<Candidate>,
        isMovie: Boolean
    ): Pair<Candidate, MediaProbe>? {

        if (
            candidates.isEmpty()
        ) {
            return null
        }

        var best: Pair<Candidate, MediaProbe>? =
            null

        var bestScore =
            Int.MIN_VALUE

        for (
            candidate in candidates
        ) {

            val probe =
                probeMedia(
                    candidate.url,
                    candidate.referer,
                    candidate.origin
                )
                    ?: continue

            val passes =
                passesDurationFilter(
                    probe.durationSeconds,
                    isMovie
                )

            if (
                !passes
            ) {

                Log.w(
                    "HDFilmCehennemi",
                    "SHORT/preview kaynak atlandı: duration=${probe.durationSeconds}s movie=$isMovie url=${candidate.url}"
                )

                continue
            }

            val score =
                candidateScore(
                    probe,
                    candidate.provisional,
                    isMovie
                )

            Log.d(
                "HDFilmCehennemi",
                "Candidate score=$score duration=${probe.durationSeconds}s vod=${probe.isVod} provisional=${candidate.provisional} url=${candidate.url}"
            )

            if (
                score > bestScore
            ) {

                bestScore =
                    score

                best =
                    candidate to
                        probe
            }
        }

        return best
    }

    // -------------------------------------------------------------------------
    // SUBTITLES
    // -------------------------------------------------------------------------

    private suspend fun addPlayerSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        playerUrl: String
    ) {

        val blocks =
            Regex(
                """(?:tracks|captions)\s*[:=]\s*\[([\s\S]*?)\]""",
                RegexOption.IGNORE_CASE
            )
                .findAll(html)
                .toList()

        for (
            blockMatch in blocks
        ) {

            val block =
                blockMatch
                    .groupValues
                    .getOrNull(1)
                    ?: continue

            val trackRegex =
                Regex(
                    """(?:file|src)\s*[:=]\s*[\"']([^\"']+)[\"'][\s\S]{0,240}?(?:label|language|srclang)\s*[:=]\s*[\"']([^\"']+)[\"']""",
                    RegexOption.IGNORE_CASE
                )

            for (
                match in trackRegex.findAll(block)
            ) {

                val rawUrl =
                    decodeText(
                        match.groupValues[1]
                    )

                val subtitleUrl =
                    try {

                        java.net.URI(
                            playerUrl
                        )
                            .resolve(
                                rawUrl
                            )
                            .toString()

                    } catch (
                        _: Exception
                    ) {

                        fixUrlNull(
                            rawUrl
                        )
                    }

                if (
                    subtitleUrl.isNullOrBlank()
                ) {
                    continue
                }

                val language =
                    match
                        .groupValues
                        .getOrNull(2)
                        .orEmpty()
                        .ifBlank {
                            "Türkçe"
                        }

                subtitleCallback(
                    newSubtitleFile(
                        language,
                        subtitleUrl
                    )
                )
            }
        }

        Jsoup
            .parse(html)
            .select(
                "video track"
            )
            .forEach { track ->

                val rawSrc =
                    track
                        .attr("src")
                        .trim()

                if (
                    rawSrc.isBlank()
                ) {
                    return@forEach
                }

                val subtitleUrl =
                    try {

                        java.net.URI(
                            playerUrl
                        )
                            .resolve(
                                rawSrc
                            )
                            .toString()

                    } catch (
                        _: Exception
                    ) {

                        fixUrlNull(
                            rawSrc
                        )
                    }

                if (
                    subtitleUrl.isNullOrBlank()
                ) {
                    return@forEach
                }

                val language =
                    track
                        .attr("label")
                        .ifBlank {
                            track.attr(
                                "srclang"
                            )
                        }
                        .ifBlank {
                            "Türkçe"
                        }

                subtitleCallback(
                    newSubtitleFile(
                        language,
                        subtitleUrl
                    )
                )
            }
    }

    // -------------------------------------------------------------------------
    // PLAYER EXTRACTION
    // -------------------------------------------------------------------------

    private suspend fun extractFromPlayer(
        source: String,
        playerUrl: String,
        pageUrl: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(
            "HDFilmCehennemi",
            "Player URL: $playerUrl"
        )

        val mediaOrigin =
            resolveMediaOrigin(
                source,
                playerUrl
            )

        val mediaReferer =
            "${mediaOrigin.trimEnd('/')}/"

        Log.d(
            "HDFilmCehennemi",
            "Resolved mediaOrigin=$mediaOrigin"
        )

        Log.d(
            "HDFilmCehennemi",
            "Resolved mediaReferer=$mediaReferer"
        )

        val response =
            runCatching {

                app.get(
                    playerUrl,
                    headers = browserHeaders,
                    referer = mediaReferer,
                    allowRedirects = true,
                    interceptor = interceptor
                )

            }.getOrNull()
                ?: run {

                    Log.e(
                        "HDFilmCehennemi",
                        "Player isteği başarısız: $playerUrl"
                    )

                    return false
                }

        val html =
            response.text

        if (
            html.isBlank()
        ) {

            Log.e(
                "HDFilmCehennemi",
                "Player HTML boş: $playerUrl"
            )

            return false
        }

        Log.d(
            "HDFilmCehennemi",
            "Player HTML length=${html.length}"
        )

        addPlayerSubtitles(
            html,
            subtitleCallback,
            playerUrl
        )

        val candidates =
            LinkedHashMap<String, Candidate>()

        fun addCandidate(
            url: String?,
            provisional: Boolean
        ) {

            if (
                url.isNullOrBlank()
            ) {
                return
            }

            val cleaned =
                cleanUrl(
                    url
                )

            if (
                !isValidVideoUrl(
                    cleaned
                )
            ) {
                return
            }

            if (
                candidates.containsKey(
                    cleaned
                )
            ) {
                return
            }

            candidates[cleaned] =
                Candidate(
                    url = cleaned,
                    source = source,
                    referer = mediaReferer,
                    origin = mediaOrigin,
                    provisional = provisional
                )
        }

        // -------------------------------------------------------------
        // 1) Current inline decoder
        // -------------------------------------------------------------

        val decoders =
            parseInlineDecoders(
                html
            )

        Log.d(
            "HDFilmCehennemi",
            "Inline decoder count=${decoders.size}"
        )

        for (
            decoder in decoders
        ) {

            val decoded =
                runCatching {

                    applyDecodeSteps(
                        decoder.parts,
                        decoder.steps
                    )

                }.getOrDefault("")

            Log.d(
                "HDFilmCehennemi",
                "Inline decoded=${decoded.take(180)}"
            )

            addCandidate(
                decoded,
                provisional = false
            )
        }

        // -------------------------------------------------------------
        // 2) Packed JavaScript fallback
        // -------------------------------------------------------------

        val unpacked =
            runCatching {
                getAndUnpack(
                    html
                )
            }.getOrNull()

        if (
            !unpacked.isNullOrBlank() &&
            unpacked != html
        ) {

            val packedDecoders =
                parseInlineDecoders(
                    unpacked
                )

            Log.d(
                "HDFilmCehennemi",
                "Unpacked decoder count=${packedDecoders.size}"
            )

            for (
                decoder in packedDecoders
            ) {

                val decoded =
                    runCatching {

                        applyDecodeSteps(
                            decoder.parts,
                            decoder.steps
                        )

                    }.getOrDefault("")

                Log.d(
                    "HDFilmCehennemi",
                    "Packed decoded=${decoded.take(180)}"
                )

                addCandidate(
                    decoded,
                    provisional = false
                )
            }
        }

        // -------------------------------------------------------------
        // 3) Direct media URLs in HTML
        // -------------------------------------------------------------

        for (
            candidateUrl in extractCandidateUrls(
                html
            )
        ) {

            addCandidate(
                candidateUrl,
                provisional = false
            )
        }

        // -------------------------------------------------------------
        // 4) JSON-LD provisional fallback
        // -------------------------------------------------------------

        val jsonLdContentUrl =
            Regex(
                """[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']""",
                RegexOption.IGNORE_CASE
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::decodeText)

        if (
            !jsonLdContentUrl.isNullOrBlank()
        ) {

            Log.d(
                "HDFilmCehennemi",
                "JSON-LD provisional=$jsonLdContentUrl"
            )

            addCandidate(
                jsonLdContentUrl,
                provisional = true
            )
        }

        if (
            candidates.isEmpty()
        ) {

            Log.e(
                "HDFilmCehennemi",
                "Bu player içinde medya adayı bulunamadı: $playerUrl"
            )

            return false
        }

        // JSON-LD is always last, but all candidates are scored/probed.
        val best =
            chooseBestCandidate(
                candidates.values.toList(),
                isMovie
            )

        if (
            best == null
        ) {

            Log.e(
                "HDFilmCehennemi",
                "Geçerli/uygun video kaynağı bulunamadı: $playerUrl"
            )

            return false
        }

        val selected =
            best.first

        val probe =
            best.second

        Log.d(
            "HDFilmCehennemi",
            "SELECTED source=${selected.source} type=${probe.type} duration=${probe.durationSeconds}s vod=${probe.isVod} provisional=${selected.provisional}"
        )

        callback(
            newExtractorLink(
                source = selected.source,
                name = selected.source,
                url = selected.url,
                type = probe.type
            ) {

                this.referer =
                    selected.referer

                this.headers =
                    mediaHeaders(
                        selected.referer,
                        selected.origin
                    )

                quality =
                    Qualities.Unknown.value
            }
        )

        Log.d(
            "HDFilmCehennemi",
            "VIDEO LINK EMITTED:"
                    + " ${selected.url}"
        )

        return true
    }

    // -------------------------------------------------------------------------
    // LOAD LINKS
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val pageDocument =
            runCatching {

                app.get(
                    data,
                    headers = browserHeaders,
                    referer = "$mainUrl/",
                    interceptor = interceptor
                ).document

            }.getOrNull()
                ?: return false

        val isMovie =
            pageDocument
                .select("div.seasons")
                .isEmpty()

        Log.d(
            "HDFilmCehennemi",
            "loadLinks isMovie=$isMovie data=$data"
        )

        var found =
            false

        pageDocument
            .select(
                "div.alternative-links"
            )
            .forEach { element ->

                val langCode =
                    element
                        .attr("data-lang")
                        .uppercase()
                        .ifBlank {
                            "TR"
                        }

                element
                    .select(
                        "button.alternative-link"
                    )
                    .forEach { button ->

                        val source =
                            "${
                                button
                                    .text()
                                    .replace(
                                        "(HDrip Xbet)",
                                        ""
                                    )
                                    .trim()
                            } $langCode"
                                .trim()

                        val videoId =
                            button
                                .attr("data-video")
                                .trim()

                        if (
                            videoId.isBlank()
                        ) {
                            return@forEach
                        }

                        val apiGet =
                            runCatching {

                                app.get(
                                    "${mainUrl}/video/$videoId/",
                                    interceptor = interceptor,
                                    headers = mapOf(
                                        "Content-Type" to
                                            "application/json",
                                        "X-Requested-With" to
                                            "fetch"
                                    ),
                                    referer = data
                                ).text

                            }.getOrNull()
                                ?: return@forEach

                        val iframe =
                            Regex(
                                """data-src\s*=\s*\\?[\"']([^\"']+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(
                                    apiGet
                                )
                                ?.groupValues
                                ?.getOrNull(1)
                                ?.let(
                                    ::decodeText
                                )
                                ?: runCatching {

                                    Jsoup.parse(
                                        apiGet
                                    )
                                        .selectFirst(
                                            "iframe[data-src], iframe[src]"
                                        )
                                        ?.let { frame ->

                                            frame
                                                .attr(
                                                    "data-src"
                                                )
                                                .ifBlank {
                                                    frame.attr(
                                                        "src"
                                                    )
                                                }
                                                .trim()
                                                .takeIf {
                                                    it.isNotBlank()
                                                }
                                        }

                                }.getOrNull()
                                ?: return@forEach

                        val normalizedIframe =
                            fixUrlNull(
                                iframe
                            ) ?: iframe

                        Log.d(
                            "HDFilmCehennemi",
                            "SOURCE=$source"
                        )

                        Log.d(
                            "HDFilmCehennemi",
                            "IFRAME=$normalizedIframe"
                        )

                        val playerCandidates =
                            LinkedHashSet<String>()

                        // Primary iframe.
                        playerCandidates +=
                            normalizedIframe

                        // -------------------------------------------------
                        // Rapidrame id
                        // -------------------------------------------------

                        val rapidrameId =
                            Regex(
                                """rapidrame_id=([^&\"']+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(
                                    iframe
                                )
                                ?.groupValues
                                ?.getOrNull(1)

                        if (
                            !rapidrameId.isNullOrBlank()
                        ) {

                            playerCandidates +=
                                "${mainUrl}/rplayer/$rapidrameId/"

                            playerCandidates +=
                                "${mainUrl}/playerr/$rapidrameId"
                        }

                        // -------------------------------------------------
                        // Existing /rplayer or /playerr iframe
                        // -------------------------------------------------

                        val rplayerMatch =
                            Regex(
                                """/(?:rplayer|playerr)/([^/?#]+)""",
                                RegexOption.IGNORE_CASE
                            )
                                .find(
                                    iframe
                                )

                        if (
                            rplayerMatch != null
                        ) {

                            val id =
                                rplayerMatch
                                    .groupValues[1]

                            playerCandidates +=
                                "${mainUrl}/rplayer/$id/"

                            playerCandidates +=
                                "${mainUrl}/playerr/$id"
                        }

                        // -------------------------------------------------
                        // Mobi nested iframes
                        // -------------------------------------------------

                        if (
                            iframe.contains(
                                "mobi",
                                ignoreCase = true
                            )
                        ) {

                            Jsoup.parse(
                                apiGet
                            )
                                .select(
                                    "iframe[src], iframe[data-src]"
                                )
                                .mapNotNull { frame ->

                                    frame
                                        .attr(
                                            "data-src"
                                        )
                                        .ifBlank {
                                            frame.attr(
                                                "src"
                                            )
                                        }
                                        .takeIf {
                                            it.isNotBlank()
                                        }

                                }
                                .mapNotNull {
                                    fixUrlNull(it)
                                }
                                .forEach {
                                    playerCandidates +=
                                        it
                                }
                        }

                        // -------------------------------------------------
                        // Try this source.
                        //
                        // Important:
                        // A short preview / trailer does NOT count as success
                        // for movies.
                        // If the player only exposes a short candidate, we keep
                        // trying other players / alternatives.
                        // -------------------------------------------------

                        for (
                            playerUrl in playerCandidates
                        ) {

                            Log.d(
                                "HDFilmCehennemi",
                                "TRY PLAYER=$playerUrl"
                            )

                            val success =
                                extractFromPlayer(
                                    source = source,
                                    playerUrl = playerUrl,
                                    pageUrl = data,
                                    isMovie = isMovie,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )

                            if (
                                success
                            ) {

                                found =
                                    true

                                break
                            }

                            Log.w(
                                "HDFilmCehennemi",
                                "PLAYER FAILED / SHORT / INVALID -> next=$playerUrl"
                            )
                        }
                    }
            }

        Log.d(
            "HDFilmCehennemi",
            "loadLinks result=$found"
        )

        return found
    }

    // -------------------------------------------------------------------------
    // DATA CLASSES
    // -------------------------------------------------------------------------

    private data class SubSource(
        @JsonProperty("file")
        val file: String? = null,

        @JsonProperty("label")
        val label: String? = null,

        @JsonProperty("language")
        val language: String? = null,

        @JsonProperty("kind")
        val kind: String? = null
    )

    data class Results(
        @JsonProperty("results")
        val results: List<String> =
            arrayListOf()
    )

    data class HDFC(
        @JsonProperty("html")
        val html: String,

        @JsonProperty("meta")
        val meta: Meta
    )

    data class Meta(
        @JsonProperty("title")
        val title: String,

        @JsonProperty("canonical")
        val canonical: String,

        @JsonProperty("keywords")
        val keywords: Boolean
    )
}
