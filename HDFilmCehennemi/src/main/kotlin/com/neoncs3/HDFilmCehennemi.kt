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
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class HDFilmCehennemi : MainAPI() {

    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    companion object {
        private const val TAG = "HDFilmCehennemi"
        private const val MAX_EXTERNAL_SCRIPTS = 10
        private const val MAX_DYNAMIC_ENDPOINTS = 12
        private const val MAX_JS_DEPTH = 8
    }

    class CloudflareInterceptor(
        private val cloudflareKiller: CloudflareKiller
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024 * 1024).string()
            if (body.contains("Just a moment", ignoreCase = true)) {
                response.close()
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
        val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .also {
                it.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            }

        val url = request.data.replace("sayfano", page.toString())
        val response = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to browserHeaders["User-Agent"].orEmpty(),
                    "Accept" to "*/*",
                    "X-Requested-With" to "fetch"
                ),
                referer = mainUrl,
                interceptor = interceptor
            )
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        if (response.text.contains("Sayfa Bulunamadı", ignoreCase = true)) {
            return newHomePageResponse(request.name, emptyList())
        }

        val data: HDFC = runCatching {
            mapper.readValue<HDFC>(response.text)
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        val document = Jsoup.parse(data.html)
        return newHomePageResponse(
            request.name,
            document.select("a").mapNotNull { it.toSearchResult() }
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title").trim()
        if (title.isBlank()) return null

        val href = fixUrlNull(attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src"))
            ?: fixUrlNull(selectFirst("img")?.attr("src"))

        val isSeries = href.contains("/dizi/", ignoreCase = true) ||
            selectFirst(".season, .seasons") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = runCatching {
            app.get(
                "${mainUrl}/search?q=${query}",
                headers = mapOf("X-Requested-With" to "fetch"),
                interceptor = interceptor
            ).parsedSafe<Results>()
        }.getOrNull() ?: return emptyList()

        return response.results.mapNotNull { html ->
            val document = Jsoup.parse(html)
            val title = document.selectFirst("h4.title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            val isSeries = href.contains("/dizi/", ignoreCase = true)
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster?.replace("/thumb/", "/list/")
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster?.replace("/thumb/", "/list/")
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = runCatching {
            app.get(
                url,
                headers = browserHeaders,
                interceptor = interceptor
            ).document
        }.getOrNull() ?: return null

        val title = document.selectFirst("h1.section-title")?.text()
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src")
        ) ?: fixUrlNull(
            document.select("aside.post-info-poster img").lastOrNull()?.attr("src")
        )

        val tags = document.select("div.post-info-genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = document.selectFirst("div.post-info-year-country a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val isSeries = document.select("div.seasons").isNotEmpty()
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()

        val actors = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()?.trim() ?: return@mapNotNull null
            Actor(
                actorName,
                fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                    ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            )
        }

        val recommendations = document.select(
            "div.section-slider-container div.slider-slide"
        ).mapNotNull {
            val recName = it.selectFirst("a")?.attr("title")?.trim()
                ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href"))
                ?: return@mapNotNull null
            val recPoster = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                posterUrl = recPoster
            }
        }

        val trailerId = document.selectFirst("div.post-info-trailer button")
            ?.attr("data-modal")
            ?.substringAfter("trailer/", "")
            ?.trim()

        val trailer = trailerId
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { "https://www.youtube.com/watch?v=$it" }

        if (isSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName = it.selectFirst("h4")?.text()?.trim()
                    ?: return@mapNotNull null
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null

                val episode = Regex("""(\d+)\.?\s*Bölüm""")
                    .find(epName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                val season = Regex("""(\d+)\.?\s*Sezon""")
                    .find(epName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    name = epName
                    this.season = season
                    this.episode = episode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun decodeText(value: String): String {
        var out = value
            .replace("\\/", "/")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)

        out = Regex("\\\\u([0-9a-fA-F]{4})").replace(out) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

        out = Regex("\\\\x([0-9a-fA-F]{2})").replace(out) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }

        return out.trim()
    }

    private fun cleanUrl(value: String): String {
        return runCatching { URLDecoder.decode(decodeText(value), "UTF-8") }
            .getOrDefault(decodeText(value))
            .trim()
            .trim('"', '\'', '`', '\\')
            .replace("\\/", "/")
    }

    private fun decodeBase64Latin1(value: String): String? {
        val input = value
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")
            .replace('-', '+')
            .replace('_', '/')
            .trim()

        if (input.isBlank() || input.any {
                it !in 'A'..'Z' &&
                    it !in 'a'..'z' &&
                    it !in '0'..'9' &&
                    it != '+' &&
                    it != '/' &&
                    it != '='
            }) return null

        val firstPadding = input.indexOf('=')
        if (firstPadding >= 0 && input.substring(firstPadding).any { it != '=' }) return null
        val unpadded = input.trimEnd('=')
        if (unpadded.length % 4 == 1) return null

        val padded = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
        }.getOrNull()
    }

    private fun isValidVideoUrl(url: String): Boolean {
        val value = cleanUrl(url)
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        if (value.any { it.isWhitespace() || it == '<' || it == '>' }) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.host.isNullOrBlank()) return false
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        return path.contains(".m3u8") ||
            path.contains(".mp4") ||
            path.contains("/hls/") ||
            path.contains("/hls2/") ||
            path.endsWith("/master.txt")
    }

    private fun mediaTypeForUrl(url: String): ExtractorLinkType {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return if (
            path.contains(".m3u8") ||
            path.contains("/hls/") ||
            path.contains("/hls2/") ||
            path.endsWith("/master.txt")
        ) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
    }

    private fun resolveAbsoluteUrl(raw: String, baseUrl: String): String? {
        val value = cleanUrl(raw)
        if (value.isBlank()) return null

        return runCatching {
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("http://") || value.startsWith("https://") -> value
                else -> URI(baseUrl).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun mediaHeaders(playerUrl: String): Map<String, String> {
        val origin = runCatching {
            val uri = URI(playerUrl)
            "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrNull() ?: mainUrl

        return mapOf(
            "User-Agent" to browserHeaders["User-Agent"].orEmpty(),
            "Accept" to "*/*",
            "Referer" to playerUrl,
            "Origin" to origin
        )
    }

    private fun findBalancedEnd(text: String, start: Int, open: Char = '{', close: Char = '}'): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == quote) {
                    quote = null
                }
                continue
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                continue
            }

            when (c) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun extractQuotedParts(arrayText: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("([\\\"'])(.*?)\\1", setOf(RegexOption.DOT_MATCHES_ALL))
        regex.findAll(arrayText).forEach { match ->
            val value = match.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
            result.add(value)
        }
        return result
    }

    private sealed class DecodeStep {
        data object Base64 : DecodeStep()
        data object Reverse : DecodeStep()
        data class Rot(val shift: Int) : DecodeStep()
        data class Unmix(val magic: Long, val offset: Int) : DecodeStep()
        data class RollingXor(val seed: Int, val inc: Int) : DecodeStep()
    }

    private data class PositionedStep(
        val position: Int,
        val step: DecodeStep
    )

    private data class InlineDecoder(
        val functionName: String,
        val parts: List<String>,
        val steps: List<DecodeStep>
    )

    /**
     * HDFilmCehennemi'nin güncel dc_xxx decoder yapısını dinamik olarak okur.
     * Site step sırasını, ROT değerini, unmix değerlerini ve rolling-XOR
     * parametrelerini sayfadan sayfaya değiştirebildiği için sabit varyant
     * kullanmak yerine function body taranır.
     */
    private fun parseInlineDecoders(html: String): List<InlineDecoder> {
        val result = mutableListOf<InlineDecoder>()
        val functionRegex = Regex(
            "function\\s+(dc_[A-Za-z0-9_$]+)\\s*\\(\\s*[A-Za-z0-9_$]+\\s*\\)\\s*\\{",
            RegexOption.IGNORE_CASE
        )

        functionRegex.findAll(html).forEach { functionMatch ->
            val functionName = functionMatch.groupValues[1]
            val openBrace = html.indexOf('{', functionMatch.range.last)
            if (openBrace < 0) return@forEach

            val closeBrace = findBalancedEnd(html, openBrace)
            if (closeBrace < 0) return@forEach

            val body = html.substring(openBrace + 1, closeBrace)
            val callRegex = Regex(
                "${Regex.escape(functionName)}\\s*\\(\\s*\\[",
                RegexOption.IGNORE_CASE
            )
            val callMatch = callRegex.find(html) ?: return@forEach
            val arrayOpen = html.indexOf('[', callMatch.range.last)
            if (arrayOpen < 0) return@forEach
            val arrayClose = findBalancedEnd(html, arrayOpen, '[', ']')
            if (arrayClose < 0) return@forEach

            val parts = extractQuotedParts(html.substring(arrayOpen + 1, arrayClose))
            if (parts.isEmpty()) return@forEach

            val steps = parseDecodeSteps(body)
            if (steps.isEmpty()) return@forEach

            result.add(
                InlineDecoder(
                    functionName = functionName,
                    parts = parts,
                    steps = steps
                )
            )

            Log.d(
                TAG,
                "INLINE DECODER=$functionName PARTS=${parts.size} STEPS=$steps"
            )
        }

        return result.distinctBy { it.functionName + it.parts.joinToString("|") }
    }

    private fun parseDecodeSteps(body: String): List<DecodeStep> {
        val found = mutableListOf<PositionedStep>()

        val base64Regex = Regex(
            "(?:=|\\b)return?\\s*atob\\s*\\(|=\\s*(?:window\\.)?atob\\s*\\("
        )
        base64Regex.findAll(body).forEach { match ->
            found.add(PositionedStep(match.range.first, DecodeStep.Base64))
        }

        val reverseRegex = Regex(
            "\\.split\\(\\s*(?:''|\"\")\\s*\\)\\s*\\.reverse\\(\\)\\s*\\.join\\(\\s*(?:''|\"\")\\s*\\)"
        )
        reverseRegex.findAll(body).forEach { match ->
            found.add(PositionedStep(match.range.first, DecodeStep.Reverse))
        }

        val rotRegex = Regex(
            "\\(\\s*o\\s*-\\s*base\\s*\\+\\s*(-?\\d+)\\s*\\)\\s*%\\s*26"
        )
        rotRegex.findAll(body).forEach { match ->
            val shift = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            found.add(PositionedStep(match.range.first, DecodeStep.Rot(shift.mod(26))))
        }

        val unmixRegex = Regex(
            "charCode\\s*=\\s*\\(\\(charCode\\s*-\\s*\\(\\s*(-?\\d+)\\s*%\\s*\\(\\s*i\\s*\\+\\s*(\\d+)\\s*\\)\\s*\\)\\s*%\\s*256"
        )
        unmixRegex.findAll(body).forEach { match ->
            val magic = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return@forEach
            val offset = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            found.add(PositionedStep(match.range.first, DecodeStep.Unmix(magic, offset)))
        }

        val rollingXorRegex = Regex(
            "(?:var|let|const)\\s+acc\\s*=\\s*(\\d+)[\\s\\S]{0,320}?acc\\s*=\\s*\\(\\s*acc\\s*\\+\\s*(\\d+)\\s*\\)\\s*%\\s*256",
            RegexOption.DOT_MATCHES_ALL
        )
        rollingXorRegex.findAll(body).forEach { match ->
            val seed = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val inc = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
            found.add(PositionedStep(match.range.first, DecodeStep.RollingXor(seed, inc)))
        }

        return found
            .sortedBy { it.position }
            .map { it.step }
            .dedupeAdjacent()
    }

    private fun List<DecodeStep>.dedupeAdjacent(): List<DecodeStep> {
        if (size < 2) return this
        val result = mutableListOf<DecodeStep>()
        for (step in this) {
            if (result.lastOrNull() != step) result.add(step)
        }
        return result
    }

    private fun applyDecodeSteps(parts: List<String>, steps: List<DecodeStep>): String {
        var result = parts.joinToString("")

        for (step in steps) {
            result = when (step) {
                DecodeStep.Base64 -> decodeBase64Latin1(result) ?: return ""
                DecodeStep.Reverse -> result.reversed()
                is DecodeStep.Rot -> rot(result, step.shift)
                is DecodeStep.Unmix -> unmix(result, step.magic, step.offset)
                is DecodeStep.RollingXor -> rollingXor(result, step.seed, step.inc)
            }
        }

        return result
    }

    private fun rot(value: String, shift: Int): String = buildString(value.length) {
        for (c in value) {
            when {
                c in 'A'..'Z' -> append(((c.code - 65 + shift).mod(26) + 65).toChar())
                c in 'a'..'z' -> append(((c.code - 97 + shift).mod(26) + 97).toChar())
                else -> append(c)
            }
        }
    }

    private fun unmix(value: String, magic: Long, offset: Int): String = buildString(value.length) {
        for (i in value.indices) {
            val byte = value[i].code
            val moduloBase = i + offset
            val delta = if (moduloBase == 0) 0 else (magic % moduloBase).toInt()
            append(((byte - delta).mod(256)).toChar())
        }
    }

    private fun rollingXor(value: String, seed: Int, inc: Int): String = buildString(value.length) {
        var acc = seed
        for (c in value) {
            val byte = c.code and 0xFF
            acc = (acc + inc).mod(256)
            append((byte xor acc).toChar())
            acc = (acc + byte).mod(256)
        }
    }

    private fun legacyCharacterUnmix(value: String): String = buildString(value.length) {
        val magic = 399756995L
        for (i in value.indices) {
            val delta = (magic % (i + 5)).toInt()
            append(((value[i].code - delta).mod(256)).toChar())
        }
    }

    private fun legacyDecode(parts: List<String>): List<String> {
        if (parts.isEmpty()) return emptyList()
        val joined = parts.joinToString("")
        val candidates = linkedSetOf<String>()

        fun add(value: String) {
            val cleaned = cleanUrl(value)
            if (isValidVideoUrl(cleaned)) candidates.add(cleaned)
        }

        runCatching {
            var v = joined
            v = v.reversed()
            v = rot(v, 13)
            v = decodeBase64Latin1(v) ?: ""
            add(legacyCharacterUnmix(v))
        }

        runCatching {
            var v = joined.reversed()
            v = decodeBase64Latin1(v) ?: ""
            v = rot(v, 13)
            add(legacyCharacterUnmix(v))
        }

        runCatching {
            var v = decodeBase64Latin1(joined) ?: ""
            v = v.reversed()
            v = rot(v, 13)
            add(legacyCharacterUnmix(v))
        }

        return candidates.toList()
    }

    private fun extractInlineDecoderUrls(html: String): List<String> {
        val result = linkedSetOf<String>()
        parseInlineDecoders(html).forEach { decoder ->
            runCatching {
                val decoded = applyDecodeSteps(decoder.parts, decoder.steps)
                if (isValidVideoUrl(decoded)) {
                    result.add(cleanUrl(decoded))
                    Log.d(TAG, "INLINE DECODE OK=${cleanUrl(decoded)}")
                } else {
                    Log.d(TAG, "INLINE DECODE NON-URL=${decoded.take(300)}")
                    legacyDecode(decoder.parts).forEach(result::add)
                }
            }.onFailure {
                Log.d(TAG, "INLINE DECODE FAILED=${it.message}")
            }
        }
        return result.toList()
    }

    private fun extractDirectVideoUrls(text: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val normalized = decodeText(text)
        val urlRegex = Regex(
            "https?://[^\\s\"'<>\\\\]+?(?:\\.m3u8(?:\\?[^\\s\"'<>\\\\]+)?|\\.mp4(?:\\?[^\\s\"'<>\\\\]+)?|/master\\.txt(?:\\?[^\\s\"'<>\\\\]+)?)",
            RegexOption.IGNORE_CASE
        )

        urlRegex.findAll(normalized).forEach { match ->
            val resolved = resolveAbsoluteUrl(match.value, baseUrl) ?: return@forEach
            if (isValidVideoUrl(resolved)) result.add(resolved)
        }

        Regex(
            "(?i)(?:file|src|source|url|media|stream|hls|playlist|contentUrl)\\s*[:=]\\s*[\"']([^\"']+)[\"']"
        ).findAll(normalized).forEach { match ->
            val resolved = resolveAbsoluteUrl(match.groupValues[1], baseUrl) ?: return@forEach
            if (isValidVideoUrl(resolved)) result.add(resolved)
        }

        return result.toList()
    }

    private fun extractJsonLdVideoUrls(html: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val scripts = Regex(
            "<script\\s+type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>",
            RegexOption.IGNORE_CASE
        )

        scripts.findAll(html).forEach { match ->
            val raw = decodeText(match.groupValues[1])
            Regex("[\"']contentUrl[\"']\\s*:\\s*[\"']([^\"']+)[\"']")
                .findAll(raw)
                .forEach { urlMatch ->
                    val resolved = resolveAbsoluteUrl(urlMatch.groupValues[1], baseUrl) ?: return@forEach
                    if (isValidVideoUrl(resolved)) result.add(resolved)
                }
        }

        return result.toList()
    }

    private fun extractScriptUrls(html: String, baseUrl: String): List<String> {
        val result = linkedSetOf<String>()
        Regex("<script[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { resolveAbsoluteUrl(it.groupValues[1], baseUrl) }
            .filter { it.endsWith(".js", ignoreCase = true) || it.contains("javascript", ignoreCase = true) }
            .take(MAX_EXTERNAL_SCRIPTS)
            .forEach(result::add)
        return result.toList()
    }

    private fun extractPlayerUrls(apiHtml: String, pageUrl: String): List<String> {
        val result = linkedSetOf<String>()
        val document = Jsoup.parse(apiHtml, pageUrl)

        document.select("iframe[data-src], iframe[src]").forEach { frame ->
            val raw = frame.attr("data-src").ifBlank { frame.attr("src") }
            resolveAbsoluteUrl(raw, pageUrl)?.let(result::add)
        }

        listOf("data-src", "data-player", "data-embed", "data-url").forEach { attr ->
            document.select("[$attr]").forEach { node ->
                resolveAbsoluteUrl(node.attr(attr), pageUrl)?.let(result::add)
            }
        }

        Regex("rapidrame_id=([^&\"']+)", RegexOption.IGNORE_CASE)
            .find(apiHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { id ->
                result.add("${mainUrl}/rplayer/$id/")
                result.add("${mainUrl}/playerr/$id")
            }

        Regex("/(?:rplayer|playerr)/([^/?#\"' ]+)", RegexOption.IGNORE_CASE)
            .findAll(apiHtml)
            .forEach { match ->
                val id = match.groupValues[1]
                result.add("${mainUrl}/rplayer/$id/")
                result.add("${mainUrl}/playerr/$id")
            }

        return result.mapNotNull { resolveAbsoluteUrl(it, pageUrl) }.distinct()
    }

    private suspend fun emitVideoLink(
        source: String,
        playerUrl: String,
        videoUrl: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = cleanUrl(videoUrl)
        if (!isValidVideoUrl(clean)) return false

        val label = if (suffix.isBlank()) source else "$source $suffix"
        val headers = mediaHeaders(playerUrl)

        Log.d(TAG, "VIDEO URL=$clean")
        Log.d(TAG, "VIDEO TYPE=${mediaTypeForUrl(clean)}")
        Log.d(TAG, "VIDEO REFERER=${headers["Referer"]}")
        Log.d(TAG, "VIDEO ORIGIN=${headers["Origin"]}")

        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = clean,
                type = mediaTypeForUrl(clean)
            ) {
                referer = headers["Referer"].orEmpty()
                this.headers = headers
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private suspend fun tryEmitCandidates(
        candidates: Collection<String>,
        source: String,
        playerUrl: String,
        prefix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var count = 1
        var emitted = false
        for (candidate in candidates.distinct()) {
            if (emitVideoLink(source, playerUrl, candidate, "$prefix $count", callback)) {
                emitted = true
            }
            count++
        }
        return emitted
    }

    private suspend fun addPlayerSubtitles(
        html: String,
        playerUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emitted = linkedSetOf<String>()

        fun emitSubtitle(label: String, url: String) {
            val clean = resolveAbsoluteUrl(url, playerUrl) ?: return
            if (!clean.startsWith("http", ignoreCase = true)) return
            if (!emitted.add(clean)) return
            val language = label.ifBlank { "Türkçe" }
            subtitleCallback(newSubtitleFile(language, clean))
        }

        val document = Jsoup.parse(html, playerUrl)
        document.select("video track[src], track[src]").forEach { track ->
            emitSubtitle(
                track.attr("label").ifBlank { track.attr("srclang") },
                track.attr("src")
            )
        }

        val tracksRegex = Regex(
            "\\{[^{}]{0,2000}?(?:file|src)\\s*:\\s*[\"']([^\"']+)[\"'][^{}]{0,2000}?(?:label|srclang)\\s*:\\s*[\"']([^\"']+)[\"'][^{}]*}",
            RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL
        )

        tracksRegex.findAll(html).forEach { match ->
            emitSubtitle(match.groupValues[2], match.groupValues[1])
        }
    }

    private suspend fun extractFromPlayer(
        source: String,
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "PLAYER=$playerUrl")

        val playerResponse = runCatching {
            app.get(
                playerUrl,
                headers = browserHeaders,
                referer = pageUrl,
                allowRedirects = true,
                interceptor = interceptor
            )
        }.getOrNull() ?: return false

        val html = playerResponse.text
        Log.d(TAG, "PLAYER HTML LENGTH=${html.length}")
        if (html.isBlank()) return false

        addPlayerSubtitles(html, playerUrl, subtitleCallback)

        // 1) Güncel HDF formatı: inline dc_xxx decoder.
        val inline = extractInlineDecoderUrls(html)
        Log.d(TAG, "INLINE VIDEO CANDIDATES=$inline")
        if (tryEmitCandidates(inline, source, playerUrl, "HDF", callback)) return true

        // 2) Düz URL / JW / JSON-LD fallback.
        val direct = extractDirectVideoUrls(html, playerUrl)
        Log.d(TAG, "DIRECT VIDEO CANDIDATES=$direct")
        if (tryEmitCandidates(direct, source, playerUrl, "Direct", callback)) return true

        val jsonLd = extractJsonLdVideoUrls(html, playerUrl)
        Log.d(TAG, "JSON-LD VIDEO CANDIDATES=$jsonLd")
        if (tryEmitCandidates(jsonLd, source, playerUrl, "JSON-LD", callback)) return true

        // 3) Eski packed JS fallback.
        val unpacked = runCatching { getAndUnpack(html) }.getOrNull()
        if (!unpacked.isNullOrBlank() && unpacked != html) {
            val packedInline = extractInlineDecoderUrls(unpacked)
            if (tryEmitCandidates(packedInline, source, playerUrl, "Packed-HDF", callback)) return true

            val packedDirect = extractDirectVideoUrls(unpacked, playerUrl)
            Log.d(TAG, "PACKED DIRECT CANDIDATES=$packedDirect")
            if (tryEmitCandidates(packedDirect, source, playerUrl, "Packed", callback)) return true
        }

        // 4) Harici player scriptleri.
        val scripts = extractScriptUrls(html, playerUrl)
        for (scriptUrl in scripts) {
            val body = runCatching {
                app.get(
                    scriptUrl,
                    headers = browserHeaders,
                    referer = playerUrl,
                    allowRedirects = true,
                    interceptor = interceptor
                ).text
            }.getOrNull().orEmpty()

            if (body.isBlank()) continue

            val scriptInline = extractInlineDecoderUrls(body)
            if (tryEmitCandidates(scriptInline, source, playerUrl, "Script-HDF", callback)) return true

            val scriptDirect = extractDirectVideoUrls(body, playerUrl)
            if (tryEmitCandidates(scriptDirect, source, playerUrl, "Script", callback)) return true
        }

        Log.e(TAG, "VIDEO BULUNAMADI player=$playerUrl page=$pageUrl")
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "LOAD LINKS=$data")

        val pageDocument = runCatching {
            app.get(
                data,
                headers = browserHeaders,
                referer = "$mainUrl/",
                interceptor = interceptor
            ).document
        }.getOrNull() ?: return false

        var found = false
        val alternativeBlocks = pageDocument.select("div.alternative-links")
        Log.d(TAG, "ALTERNATIVE BLOCKS=${alternativeBlocks.size}")

        for (element in alternativeBlocks) {
            val langCode = element.attr("data-lang").uppercase().ifBlank { "TR" }

            for (button in element.select("button.alternative-link")) {
                val source = "${button.text().replace("(HDrip Xbet)", "").trim()} $langCode".trim()
                val videoId = button.attr("data-video").trim()
                if (videoId.isBlank()) continue

                Log.d(TAG, "SOURCE=$source VIDEO_ID=$videoId")

                val apiHtml = runCatching {
                    app.get(
                        "${mainUrl}/video/$videoId/",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch",
                            "Accept" to "*/*",
                            "User-Agent" to browserHeaders["User-Agent"].orEmpty()
                        ),
                        referer = data,
                        interceptor = interceptor
                    ).text
                }.getOrNull().orEmpty()

                if (apiHtml.isBlank()) continue

                val playerCandidates = extractPlayerUrls(apiHtml, data).toMutableList()

                // Bazı cevaplarda doğrudan HLS URL'si dönebiliyor.
                val directApi = extractDirectVideoUrls(apiHtml, data)
                if (tryEmitCandidates(directApi, source, data, "API", callback)) {
                    found = true
                    break
                }

                Log.d(TAG, "PLAYER CANDIDATES=$playerCandidates")

                for (playerUrl in playerCandidates.distinct()) {
                    Log.d(TAG, "TRY PLAYER=$playerUrl")
                    if (extractFromPlayer(source, playerUrl, data, subtitleCallback, callback)) {
                        found = true
                        break
                    }
                }

                if (found) break
            }

            if (found) break
        }

        Log.d(TAG, "LOAD LINKS RESULT=$found")
        return found
    }

    data class Results(
        @JsonProperty("results")
        val results: List<String> = emptyList()
    )

    data class HDFC(
        @JsonProperty("html")
        val html: String,
        @JsonProperty("meta")
        val meta: Meta
    )

    data class Meta(
        @JsonProperty("title")
        val title: String = "",
        @JsonProperty("canonical")
        val canonical: String = "",
        @JsonProperty("keywords")
        val keywords: Boolean = false
    )
}
