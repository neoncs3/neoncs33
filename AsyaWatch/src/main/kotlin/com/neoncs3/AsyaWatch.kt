package com.asyawatch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import java.net.URLEncoder

/**
 * AsyaWatch sağlayıcısı.
 * Kullanıcının verdiği AsyaWatchProvider kodu temel alınarak düzenlenmiştir.
 */
class AsyaWatchProvider : MainAPI() {
    override var name = "AsyaWatch"
    override var mainUrl = "https://asyawatch.com"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/json,*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to (mainUrl + "/"),
        )

    override val mainPage = mainPageOf(
        mainUrl + "/dizi-izle" to "Yeni Diziler",
        mainUrl + "/film-izle" to "Son Filmler",
    )

    private val cardSelector =
        "a.pcard[href], a[href*='/dizi/'], a[href*='/film/']"

    private val badImg = Regex(
        """(?i)^(?:data:|about:|javascript:)|/(?:pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(?:png|gif|jpe?g|svg|webp)(?:[?#]|$)"""
    )

    private val mediaRegex = Regex(
        """(?i)(?:https?:)?//[^"'<>\s]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^"'<>\s]*)?"""
    )

    private val playerUrlRegex = Regex(
        """(?i)["'](?:src|master|file|source|url)["']\s*:\s*["']((?:https?:)?//[^"']+)["']"""
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (page > 1) {
            request.data.trimEnd('/') + "?page=" + page
        } else {
            request.data
        }

        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        val url = mainUrl + "/ara?q=" + URLEncoder.encode(term, "UTF-8")
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrNull() ?: return emptyList()

        return document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .distinctBy { it.url }
    }

    private fun Element.toHtmlCard(): SearchResponse? {
        val href = absoluteUrl(attr("href"))?.takeIf { it.isNotBlank() } ?: return null
        val title = sequenceOf(
            selectFirst("a.tcn, b, h2, h3")?.text(),
            attr("title"),
            selectFirst("img")?.attr("alt"),
        ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull() ?: return null

        val image = selectFirst("img")
        val rawPoster = image?.let {
            sequenceOf(
                it.attr("data-src"),
                it.attr("data-lazy-src"),
                it.attr("data-original"),
                it.attr("src"),
            ).firstOrNull { value -> value.isNotBlank() }
        }?.takeIf { !badImg.containsMatchIn(it) }

        val poster = rawPoster?.let { absoluteUrl(it) }
        return if (href.contains("/dizi/", ignoreCase = true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = runCatching {
            app.get(url, headers = headers, referer = mainUrl + "/").document
        }.getOrElse {
            throw ErrorLoadingException("AsyaWatch içerik sayfası açılamadı")
        }

        val ld = readJsonLd(document)
        val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Başlık okunamadı")

        val imageValue = when (val image = ld?.opt("image")) {
            is JSONArray -> image.optString(0)
            is JSONObject -> image.optString("url")
            is String -> image
            else -> null
        }

        val rawPoster = imageValue?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
        val poster = rawPoster?.let { absoluteUrl(it) }

        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val rating = ld?.optJSONObject("aggregateRating")
            ?.optString("ratingValue")
            ?.takeIf { it.isNotBlank() }

        val actors = readActors(ld)
        val recommendations = document.select(cardSelector)
            .mapNotNull { it.toHtmlCard() }
            .filterNot { it.url == url }
            .distinctBy { it.url }

        val episodes = if (url.contains("/dizi/", ignoreCase = true)) {
            document.select("a.simr[href]").mapNotNull { anchor ->
                val href = absoluteUrl(anchor.attr("href"))
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = anchor.text().ifBlank {
                    href.substringAfterLast("/")
                }

                newEpisode(href) {
                    name = label
                    season = Regex("""(?i)sezon-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episode = Regex("""(?i)bolum-(\d+)""")
                        .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    posterUrl = poster
                }
            }.distinctBy { it.data }
        } else {
            emptyList()
        }

        return if (url.contains("/dizi/", ignoreCase = true)) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    private fun readJsonLd(document: org.jsoup.nodes.Document): JSONObject? {
        val candidates = mutableListOf<JSONObject>()

        document.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            val parsed: Any = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: return@forEach

            when (parsed) {
                is JSONObject -> {
                    val graph = parsed.optJSONArray("@graph")
                    if (graph != null) {
                        for (index in 0 until graph.length()) {
                            graph.optJSONObject(index)?.let(candidates::add)
                        }
                    } else {
                        candidates.add(parsed)
                    }
                }
                is JSONArray -> {
                    for (index in 0 until parsed.length()) {
                        parsed.optJSONObject(index)?.let(candidates::add)
                    }
                }
            }
        }

        return candidates.firstOrNull { item ->
            when (val type = item.opt("@type")) {
                is String -> type in setOf("Movie", "TVSeries", "CreativeWork", "Article")
                is JSONArray -> (0 until type.length()).any {
                    type.optString(it) in setOf("Movie", "TVSeries", "CreativeWork", "Article")
                }
                else -> false
            }
        }
    }

    private fun readActors(ld: JSONObject?): List<Actor> {
        val raw = ld?.opt("actor") ?: return emptyList()
        val values = when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.opt(it) }
            else -> listOf(raw)
        }

        return values.mapNotNull { item ->
            val actorName = when (item) {
                is JSONObject -> item.optString("name")
                is String -> item
                else -> ""
            }.trim()

            actorName.takeIf { it.isNotBlank() }?.let { Actor(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageResponse = runCatching {
            app.get(data, headers = headers, referer = "$mainUrl/")
        }.getOrNull() ?: return false

        val pageText = pageResponse.text
            .replace("\\\"", "\"")
            .replace("\\/", "/")
        val document = pageResponse.document
        val candidates = linkedSetOf<String>()

        // AsyaWatch sayfasındaki JSON player tanımlarından embed adreslerini al.
        Regex("""(?i)["'](?:src|master)["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(pageText)
            .map { cleanUrl(it.groupValues[1]) }
            .filter {
                it.contains("pichive", true) ||
                    it.contains("hotlinger", true) ||
                    it.contains("playru", true) ||
                    it.isMediaUrl()
            }
            .forEach(candidates::add)

        // Bazı sayfalarda iframe URL'si yalnızca HTML niteliğinde yer alıyor.
        document.select(
            "iframe[src], iframe[data-src], video[src], video source[src], source[src]"
        ).forEach { element ->
            val raw = sequenceOf(element.attr("src"), element.attr("data-src"))
                .firstOrNull { it.isNotBlank() }
            absoluteUrl(raw, data)?.let(candidates::add)
        }

        var found = false

        for (candidate in candidates) {
            if (candidate.isMediaUrl()) {
                val path = candidate.substringBefore('?').lowercase()
                val type = if (path.endsWith(".mp4") || path.endsWith(".webm")) {
                    ExtractorLinkType.VIDEO
                } else if (path.endsWith(".mpd")) {
                    ExtractorLinkType.DASH
                } else {
                    ExtractorLinkType.M3U8
                }
                callback(
                    newExtractorLink(name, "AsyaWatch", candidate, type) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
                    }
                )
                found = true
                continue
            }

            // Embed sunucusunu kendi domain'i ve referer'iyle aç.
            var candidateFound = false
            val embed = runCatching {
                app.get(candidate, headers = headers, referer = "$mainUrl/").text
            }.getOrNull()

            if (embed.isNullOrBlank()) {
                var extractorFound = false
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        extractorFound = true
                    }
                }
                if (extractorFound) found = true
                continue
            }

            val origin = Regex("""^https?://[^/]+""")
                .find(candidate)?.value
            val token = Regex("""(?i)openPlayer\s*\(\s*['"]([^'"]+)""")
                .find(embed)?.groupValues?.getOrNull(1)

            // Asıl kaynak API'si bölüm sayfasının değil embed sunucusunun domain'inde.
            if (!origin.isNullOrBlank() && !token.isNullOrBlank()) {
                val sourceText = runCatching {
                    app.get(
                        "$origin/source2.php?v=$token",
                        headers = headers + mapOf("Accept" to "application/json"),
                        referer = candidate,
                    ).text.replace("\\/", "/")
                }.getOrNull().orEmpty()

                val playlistArray = runCatching {
                    JSONObject(sourceText).optJSONArray("playlist")
                }.getOrNull()

                if (playlistArray != null) {
                    for (i in 0 until playlistArray.length()) {
                        val item = playlistArray.optJSONObject(i) ?: continue
                        val label = item.optString("title").trim().ifBlank { "AsyaWatch" }
                        val sources = item.optJSONArray("sources") ?: continue

                        for (j in 0 until sources.length()) {
                            val rawFile = sources.optJSONObject(j)
                                ?.optString("file")
                                ?.takeIf { it.startsWith("http", true) }
                                ?: continue
                            val master = if (rawFile.contains("m.php", ignoreCase = true)) {
                                rawFile.replace("m.php", "master.m3u8", ignoreCase = true)
                            } else {
                                rawFile
                            }
                            val path = master.substringBefore('?').lowercase()
                            val type = if (path.endsWith(".mp4") || path.endsWith(".webm")) {
                                ExtractorLinkType.VIDEO
                            } else if (path.endsWith(".mpd")) {
                                ExtractorLinkType.DASH
                            } else {
                                ExtractorLinkType.M3U8
                            }
                            callback(
                                newExtractorLink(name, label, master, type) {
                                    this.referer = candidate
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf(
                                        "User-Agent" to ua,
                                        "Referer" to candidate,
                                    )
                                }
                            )
                            candidateFound = true
                            found = true
                        }
                    }
                }
            }

            // Pichive player'larında master/file URL'si proof-of-work imzası ister.
            val rawMasters = Regex(
                """"(?:master|file)"\s*:\s*"(https?://[^"]+)"""",
                RegexOption.IGNORE_CASE,
            ).findAll(embed).map { it.groupValues[1].replace("\\/", "/") }.distinct()

            for (rawMaster in rawMasters) {
                val master = pichiveSign(embed, rawMaster)
                val playlist = runCatching {
                    app.get(master, headers = headers, referer = candidate).text
                }.getOrNull().orEmpty()

                if (!playlist.startsWith("#EXTM3U") &&
                    !master.contains("pichive.online", ignoreCase = true)
                ) {
                    continue
                }

                callback(
                    newExtractorLink(name, "AsyaWatch", master, ExtractorLinkType.M3U8) {
                        this.referer = candidate
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to candidate,
                        )
                    }
                )
                candidateFound = true
                found = true
            }

            // Pichive dışındaki embed sağlayıcıları CloudStream extractor'larına bırak.
            if (!candidateFound) {
                runCatching {
                    loadExtractor(candidate, data, subtitleCallback) { link ->
                        callback(link)
                        candidateFound = true
                        found = true
                    }
                }
            }
        }

        // Eski sayfa tipinde player token'ı bölüm sayfasında doğrudan bulunabiliyor.
        // Yeni embed çözümlemesi bağlantı bulamadıysa geriye dönük uyumluluk olarak dene.
        if (!found) {
            val videoId = sequenceOf(
                Regex("""(?i)window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""")
                    .find(pageText)?.groupValues?.getOrNull(1),
                Regex("""(?i)[?&]v=([\w-]+)""")
                    .find(pageText)?.groupValues?.getOrNull(1),
                Regex("""(?i)(?:videoId|video_id|data-video-id)\s*[:=]\s*['"]?([\w-]+)""")
                    .find(pageText)?.groupValues?.getOrNull(1),
            ).firstOrNull { !it.isNullOrBlank() }

            if (!videoId.isNullOrBlank()) {
                val sourceUrl = "$mainUrl/source2.php?v=$videoId"
                val sourceText = runCatching {
                    app.get(
                        sourceUrl,
                        headers = headers + mapOf("Accept" to "application/json"),
                        referer = data,
                    ).text.replace("\\/", "/")
                }.getOrNull().orEmpty()

                val streams = buildSet {
                    mediaRegex.findAll(sourceText).forEach { add(cleanUrl(it.value)) }
                    playerUrlRegex.findAll(sourceText).forEach { add(cleanUrl(it.groupValues[1])) }
                    runCatching { JSONObject(sourceText) }.getOrNull()?.let { json ->
                        listOf("src", "master", "file", "url").forEach { key ->
                            json.optString(key).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                for (stream in streams) {
                    if (!stream.isMediaUrl()) continue
                    if (emitMedia(stream, data, callback)) found = true
                }
            }
        }

        // Mevcut sayfa içi track altyazıları video çözümleme sonrasında raporlanır.
        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            val subtitle = absoluteUrl(raw, data) ?: return@forEach
            runCatching {
                subtitleCallback(
                    newSubtitleFile(
                        lang = track.attr("label").ifBlank {
                            track.attr("srclang").ifBlank { "Türkçe" }
                        },
                        url = subtitle,
                    ) {
                        headers = mapOf("User-Agent" to ua, "Referer" to data)
                    }
                )
            }
        }

        return found
    }

    private fun pichiveGateNonce(html: String): String? {
        val gate = Regex(
            """"gate"\s*:\s*\{\s*"n"\s*:\s*"([^"]+)"(?:\s*,\s*"b"\s*:\s*(\d+))?"""
        ).find(html) ?: return null
        val n = gate.groupValues[1]
        val bits = gate.groupValues[2].toIntOrNull() ?: 12
        val digest = MessageDigest.getInstance("SHA-256")

        fun wordAt(message: String, index: Int): Long {
            val bytes = digest.digest(message.toByteArray(Charsets.UTF_8))
            val offset = index.coerceIn(0, 7) * 4
            return ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)
        }

        val function = Regex(
            """window\.NP_GATE\s*=\s*function\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*\{([\s\S]*?)\};"""
        ).find(html)

        if (function == null) {
            if (bits !in 1..32) return null
            for (i in 0 until 50_000_000) {
                if (wordAt("$n:$i", 0) ushr (32 - bits) == 0L) return i.toString()
            }
            return null
        }

        val nonceVariable = function.groupValues[1]
        val body = function.groupValues[3]
        val charMatch = Regex(
            """var\s+(\w+)\s*=\s*String\.fromCharCode\(([\d,\s]+)\)"""
        ).find(body) ?: return null
        val saltVariable = charMatch.groupValues[1]
        val salt = charMatch.groupValues[2].split(',')
            .map { it.trim().toInt().toChar() }.joinToString("")

        val nonceMatch = Regex(
            """var\s+(\w+)\s*=\s*(?:\w+\.toString\((\d*)\)|String\(\w+\))"""
        ).find(body) ?: return null
        val nonceValueVariable = nonceMatch.groupValues[1]
        val radix = nonceMatch.groupValues[2].toIntOrNull()?.coerceIn(2, 36) ?: 10

        val maskMatch = Regex(
            """\(\s*\w+\(([^)]+)\)\[(\d+)\]\s*(>>>|&)\s*(\d+)\s*\)\s*===?\s*0"""
        ).find(body) ?: return null
        val parts = maskMatch.groupValues[1].split('+').map { it.trim() }
        val wordIndex = maskMatch.groupValues[2].toIntOrNull() ?: return null
        val operation = maskMatch.groupValues[3]
        val rhs = maskMatch.groupValues[4].toLongOrNull() ?: return null
        if (rhs !in 0L..31L) return null

        for (i in 0 until 50_000_000) {
            val nonce = java.lang.Long.toString(i.toLong(), radix)
            val message = buildString {
                for (part in parts) {
                    when (part) {
                        nonceVariable -> append(n)
                        saltVariable -> append(salt)
                        nonceValueVariable -> append(nonce)
                        else -> append(part.trim('\'', '"'))
                    }
                }
            }
            val word = wordAt(message, wordIndex)
            val valid = if (operation == ">>>") {
                (word ushr rhs.toInt()) == 0L
            } else {
                (word and rhs) == 0L
            }
            if (valid) return nonce
        }
        return null
    }

    private fun pichiveSign(html: String, url: String): String {
        if (!(url.contains("/nm.php") || url.contains("/nsub.php")) || url.contains("&p=")) {
            return url
        }
        val nonce = pichiveGateNonce(html) ?: return url
        return "$url&p=$nonce"
    }

    private suspend fun emitMedia(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val clean = cleanUrl(url)
        val path = clean.substringBefore('?').lowercase()
        val type = when {
            path.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(name, name, clean, type) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to referer,
                )
            }
        )
        return true
    }

    private fun String.isMediaUrl(): Boolean {
        val path = lowercase().substringBefore('#').substringBefore('?')
        return path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm")
    }

    private fun cleanUrl(raw: String): String {
        val value = raw.trim()
            .replace("\\/", "/")
            .trim('"', '\'', ' ', ')', ']', '}', ',')
        return if (value.startsWith("//")) "https:" + value else value
    }

    private fun absoluteUrl(raw: String?, base: String = mainUrl): String? {
        val value = raw?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true) &&
                    !it.equals("undefined", ignoreCase = true)
            } ?: return null
        return runCatching { URI(base).resolve(value).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}
