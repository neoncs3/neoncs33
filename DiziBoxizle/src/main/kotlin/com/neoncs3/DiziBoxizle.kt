package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.extractors.helper.JWPlayerHelper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI


class DiziBoxizle : NeonMainAPI() {

    override var mainUrl = "https://diziboxizle.com"
    override var name = "DiziBoxizle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    private val requestHeaders = mapOf(
        "User-Agent" to BROWSER_USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-turu/" to "Tüm Diziler",
        "$mainUrl/tur/filmler/" to "Filmler",
        "$mainUrl/tum-bolumler/" to "Son Bölümler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = withPage(request.data, page)
        val document = runCatching {
            app.get(url, headers = requestHeaders).document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)

        val allowEpisodeItems = request.name.contains("bölüm", ignoreCase = true)

        val results = document.select("a[href]")
            .asSequence()
            .filterNot { it.isSiteChromeLink() }
            .mapNotNull { it.toSearchResponse(allowEpisodeItems) }
            .distinctBy { it.url }
            .toList()

        val hasNext = results.isNotEmpty() && page < 50 && hasNextPage(document, page)

        return newHomePageResponse(
            request.name,
            results,
            hasNext = hasNext,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?search=$encoded",
            "$mainUrl/ara/$encoded/",
        )

        for (searchUrl in urls) {
            val document = runCatching {
                app.get(searchUrl, headers = requestHeaders).document
            }.getOrNull() ?: continue

            val results = document.select("a[href]")
                .asSequence()
                .filterNot { it.isSiteChromeLink() }
                .mapNotNull { it.toSearchResponse(false) }
                .distinctBy { it.url }
                .filterNot { isEpisodeUrl(it.url) }
                .toList()

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = fixUrl(url)
        val document = runCatching {
            app.get(
                normalizedUrl,
                headers = requestHeaders + ("Referer" to "$mainUrl/"),
            ).document
        }.getOrNull() ?: return null

        val path = normalizedUrl.lowercase()

        // Movies use /film/{slug}/ on the current site.
        if ("/film/" in path) {
            val title = pageTitle(document) ?: return null
            return neonEnrichResponse(
            newMovieLoadResponse(title, normalizedUrl, TvType.Movie, normalizedUrl) {

                posterUrl = posterOf(document)
                plot = pagePlot(document)
                year = pageYear(document)
                pageRating(document)?.let { score = Score.from10(it) }
            
            },
            document = document,
            baseUrl = normalizedUrl,
        )
        }

        // The site's "Son Bölümler" list links directly to episode pages.
        // Expose such a page as a one-episode series so CloudStream can reach loadLinks().
        if (isEpisodeUrl(normalizedUrl)) {
            val episodeTitle = pageTitle(document) ?: return null
            val fullMatch = EPISODE_PATTERN.find(normalizedUrl)
                ?: ALT_EPISODE_PATTERN.find(normalizedUrl)
            val simpleMatch = SIMPLE_EPISODE_PATTERN.find(normalizedUrl)
            val season = fullMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val episode = fullMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            val seriesTitle = episodeTitle
                .replace(Regex("(?i)\\s*\\d+\\.\\s*Sezon\\s*\\d+\\.\\s*Bölüm\\s*$"), "")
                .trim()
                .ifBlank { episodeTitle }

            val singleEpisode = newEpisode(normalizedUrl) {
                name = episodeTitle
                this.season = season
                this.episode = episode
                posterUrl = posterOf(document)
            }

            return neonEnrichResponse(
            newTvSeriesLoadResponse(
                seriesTitle,
                normalizedUrl,
                TvType.TvSeries,
                listOf(singleEpisode),
            ) {

                posterUrl = posterOf(document)
                plot = pagePlot(document)
                year = pageYear(document)
                pageRating(document)?.let { score = Score.from10(it) }
            
            },
            document = document,
            baseUrl = normalizedUrl,
        )
        }

        // DiziBOX series pages are root-level slugs, e.g. /the-lowdown/.
        val title = pageTitle(document) ?: return null
        val episodes = parseEpisodes(document)

        if (episodes.isEmpty()) return null

        return neonEnrichResponse(
            newTvSeriesLoadResponse(title, normalizedUrl, TvType.TvSeries, episodes) {

            posterUrl = posterOf(document)
            plot = pagePlot(document)
            year = pageYear(document)
            pageRating(document)?.let { score = Score.from10(it) }
        
            },
            document = document,
            baseUrl = normalizedUrl,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val response = runCatching {
            app.get(
                episodeUrl,
                headers = requestHeaders + ("Referer" to "$mainUrl/"),
            )
        }.getOrNull() ?: return false

        val document = response.document
        val rawHtml = buildString {
            append(document.html())
            document.select("script, noscript, template").forEach {
                append("\n")
                append(it.data())
                append("\n")
                append(it.html())
            }
        }.decodeEmbeddedText()

        val candidates = LinkedHashSet<String>()
        // Native CloudStream extractors can report true merely because an extractor
        // matched the URL; that does not guarantee that an ExtractorLink was emitted.
        // Track emitted links so we can fall back to the local VidMoly/JS parser when
        // the native extractor matches but produces no playable link.
        val emittedLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val emitCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks.add(link)
            callback(link)
        }

        // 1) iframe/embed/provider links shown by DiziBOX.
        document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-original], [data-iframe], [data-embed], [data-video], [data-player], " +
                "[data-embed-url], [data-player-url], [data-video-url], [data-stream]"
        ).forEach { element ->
            extractUrlFromElement(element)?.let(candidates::add)
        }

        // Raw HTML fallback: some lazy/malformed iframe markup is not preserved as a
        // normal Jsoup iframe node, although the provider URL remains in the source.
        Regex(
            """(?is)<iframe[^>]+(?:src|data-src|data-lazy-src|data-original)\s*=\s*["']([^"']+)["']"""
        ).findAll(rawHtml)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .map { fixUrl(it) }
            .filter { isExternalPlayer(it) || isMediaUrl(it) }
            .forEach(candidates::add)

        // Provider URLs can also be embedded directly inside page JavaScript/JSON.
        Regex(
            """https?://[^\s"'<>]*(?:vidmoly|ok\.ru|odnoklassniki)[^\s"'<>]*"""
        ).findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';', ',') }
            .forEach(candidates::add)

        // 2) Common provider buttons/anchors (for example Vidmoly/Ok.ru).
        document.select(
            "a[href], button, [role='button'], [data-url], [data-href], [data-src], [data-link]"
        ).forEach { element ->
            val label = element.text().trim().lowercase()
            val providerLike = label.contains("vidmoly") ||
                label.contains("okru") ||
                label.contains("ok.ru") ||
                label.contains("moly") ||
                label.contains("player") ||
                label.contains("1080p") ||
                label.contains("720p")

            if (providerLike) {
                extractUrlFromElement(element)?.let(candidates::add)
            }
        }

        // 3) URLs embedded in attributes, scripts or JSON.
        document.select(
            "[href], [src], [data-url], [data-href], [data-src], [data-link], [data-video], [data-iframe], " +
                "[data-embed], [data-player], [data-embed-url], [data-player-url], [data-video-url], [data-stream], [onclick]"
        ).forEach { element ->
            element.attributes().forEach { attr ->
                val value = attr.value.trim().decodeEmbeddedText()
                Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                    .findAll(value)
                    .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                    .forEach { url ->
                        if (isMediaUrl(url) || isExternalPlayer(url)) candidates.add(url)
                    }
            }
        }

        // 4) Direct HLS/media URLs in page source.
        Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
            .findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';', ',') }
            .filter { isMediaUrl(it) || isExternalPlayer(it) }
            .forEach(candidates::add)

        // Some JWPlayer/VidMoly pages keep a relative HLS source such as
        // /hls2/.../master.txt or /stream/.../master.m3u8 in the script.
        Regex(
            """(?is)(?:file|src|url|source|hls)\s*[:=]\s*["'](\/(?:[^"'\s<>]+(?:\.m3u8|\.txt)(?:\?[^"'\s<>]*)?))["']"""
        ).findAll(rawHtml)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it) }
            .filter(::isMediaUrl)
            .forEach(candidates::add)

        // 5) VMEAS HLS URLs.
        //    Handles both /index-v1-a1.m3u8?... and master.m3u8?... URLs,
        //    including paths such as
        //    /y8f7mscf5o6e_,n,l,.urlset/master.m3u8?...
        VMEAS_M3U8_PATTERN.findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';') }
            .forEach(candidates::add)
        // Catch direct master/index/playlist URLs even when the player script
        // does not label them with file/src/url/source/hls.
        Regex(
            """https?://[^\s"'<>]+(?:master|index|playlist)[^\s"'<>]*\.(?:m3u8|txt)(?:\?[^\s"'<>]*)?"""
        ).findAll(rawHtml)
            .map { it.value.trimEnd(')', ']', '}', ';', ',') }
            .filter { isMediaUrl(it) }
            .forEach(candidates::add)

        Log.d(
            "DZBX",
            "loadLinks episode=${episodeUrl} candidates=${candidates.size} " +
                candidates.take(12).joinToString(" | "),
        )

        var found = false

        // Prefer OK.ru direct media first. The DiziBOX web player intentionally
        // displays a 15-second startup gate around the VidMoly iframe; when an OK.ru
        // mirror is available, its direct MP4/HLS stream avoids that page-level delay.
        val orderedCandidates = candidates.toList().sortedBy { candidate ->
            when {
                isOkRuPlayer(candidate) -> 0
                isMediaUrl(candidate) -> 1
                isVidMolyPlayer(candidate) -> 2
                else -> 3
            }
        }

        for (candidate in orderedCandidates) {
            val clean = candidate.decodeEmbeddedText()

            when {
                isMediaUrl(clean) -> {
                    val type = when {
                        Regex("(?i)\\.m3u8(?:$|\\?)").containsMatchIn(clean) -> ExtractorLinkType.M3U8
                        Regex("(?i)\\.mpd(?:$|\\?)").containsMatchIn(clean) -> ExtractorLinkType.DASH
                        else -> ExtractorLinkType.VIDEO
                    }

                    emitMediaLink(
                        clean,
                        episodeUrl,
                        emitCallback,
                    )
                    found = true
                }

                isExternalPlayer(clean) -> {
                    // Prefer CloudStream's native extractor first. The current Vidmoly
                    // extractor knows how to normalize /w/... embeds and resolve JWPlayer
                    // sources directly.
                    // DiziBOX currently uses VidMoly and OK.ru for the real
                    // player. Resolve these hosts directly first instead of relying only on
                    // CloudStream's generic extractor registry.
                    val directProviderFound = when {
                        isOkRuPlayer(clean) -> {
                            extractOkRuMedia(
                                clean,
                                emitCallback,
                            )
                        }

                        isVidMolyPlayer(clean) -> {
                            extractVidMolyMedia(
                                clean,
                                episodeUrl,
                                emitCallback,
                            )
                        }

                        else -> false
                    }

                    val nativeEmitted: Boolean
                    val providerFound: Boolean

                    if (directProviderFound) {
                        nativeEmitted = false
                        providerFound = true
                    } else {
                        val emittedBefore = emittedLinks.size
                        val extracted = runCatching {
                            loadExtractor(
                                clean,
                                episodeUrl,
                                subtitleCallback,
                                emitCallback,
                            )
                        }.getOrDefault(false)
                        nativeEmitted = emittedLinks.size > emittedBefore

                        // A native extractor may claim a host even when it does not
                        // return a usable stream. Give the local parser a second chance.
                        if (extracted && !nativeEmitted) {
                            Log.w(
                                "DZBX",
                                "Native extractor matched but emitted no link; local fallback: $clean",
                            )
                        }

                        providerFound = if (!nativeEmitted) {
                            extractProviderMedia(
                                clean,
                                episodeUrl,
                                subtitleCallback,
                                emitCallback,
                            )
                        } else {
                            false
                        }
                    }

                    found = providerFound || nativeEmitted || found
                }            }
        }

        // Public subtitle tracks only.
        document.select("track[src], track[data-src]").forEach { track ->
            val raw = track.attr("src").ifBlank { track.attr("data-src") }
            if (raw.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(
                        track.attr("label").ifBlank { "Türkçe" },
                        fixUrl(raw),
                    )
                )
            }
        }

        if (!found) {
            found = neonResolveLinks(
                data = data,
                sourceName = "DiziBoxizle Fallback",
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }

        Log.d(
            "DZBX",
            "loadLinks result found=${found} episode=${episodeUrl}",
        )
        return found
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        sourcePage: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = when {
            Regex("(?i)\\.(?:m3u8|txt)(?:$|\\?)").containsMatchIn(mediaUrl) -> ExtractorLinkType.M3U8
            Regex("(?i)\\.(?:mpd)(?:$|\\?)").containsMatchIn(mediaUrl) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }


        val providerOrigin = originOf(sourcePage)
        val isProviderPage = sourcePage.contains("vidmoly", ignoreCase = true) ||
            sourcePage.contains("moly", ignoreCase = true) ||
            sourcePage.contains("oynatloload.top", ignoreCase = true)

        // media-internals shows playback inside the VidMoly frame.
        // Use the provider origin as the HLS Referer/Origin instead of DiziBox.
        val mediaReferer = if (isProviderPage) {
            providerOrigin?.plus("/") ?: sourcePage
        } else {
            sourcePage
        }

        val mediaHeaders = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mediaReferer,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        )

        if (isProviderPage && providerOrigin != null) {
            mediaHeaders["Origin"] = providerOrigin
        }

        callback(
            newExtractorLink(
                source = name,
                name = hostLabel(mediaUrl),
                url = mediaUrl,
                type = type,
            ) {
                referer = mediaReferer
                quality = qualityFromUrl(mediaUrl)
                headers = mediaHeaders
            }
        )
    }


    private fun isOkRuPlayer(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("ok.ru") || value.contains("odnoklassniki")
    }

    private fun isVidMolyPlayer(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vidmoly")
    }

    private suspend fun extractVidMolyMedia(
        providerUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val candidates = LinkedHashSet<String>()
        candidates.add(providerUrl)
        vidMolyClassicUrl(providerUrl)?.let(candidates::add)

        for (pageUrl in candidates) {
            val normalized = pageUrl
                .replace("vidmoly.to", "vidmoly.biz", ignoreCase = true)
                .replace("vidmoly.net", "vidmoly.biz", ignoreCase = true)

            val firstResponse = runCatching {
                app.get(
                    normalized,
                    headers = mapOf(
                        "User-Agent" to BROWSER_USER_AGENT,
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    ),
                    referer = episodeUrl,
                )
            }.getOrElse {
                Log.e(
                    "DZBX",
                    "VidMoly HTTP exception page=" + normalized +
                        " error=" + it.message,
                )
                continue
            }

            var html = firstResponse.text
            var providerDocument = firstResponse.document

            Log.d(
                "DZBX",
                "VidMoly HTTP page=" + normalized +
                    " code=" + firstResponse.code +
                    " bytes=" + html.length +
                    " title=" + providerDocument.title().take(80) +
                    " pleaseWait=" + html.contains("Please wait", ignoreCase = true) +
                    " sources=" + html.contains("sources", ignoreCase = true) +
                    " hls=" + html.contains("hls", ignoreCase = true) +
                    " m3u8=" + html.contains(".m3u8", ignoreCase = true) +
                    " masterTxt=" + html.contains("master.txt", ignoreCase = true),
            )

            // VidMoly may return a challenge page before exposing player data.
            if (html.contains("<title>Please wait", ignoreCase = true)) {
                val waitId = Regex("""\?g=([a-fA-F0-9]+)""")
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)

                if (!waitId.isNullOrBlank()) {
                    val challengeUrl = normalized + "?g=" + waitId
                    val challengeResponse = runCatching {
                        app.get(
                            challengeUrl,
                            headers = mapOf(
                                "User-Agent" to BROWSER_USER_AGENT,
                                "Referer" to normalized,
                                "Upgrade-Insecure-Requests" to "1",
                                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                            ),
                        )
                    }.getOrNull()

                    if (challengeResponse != null) {
                        html = challengeResponse.text
                        providerDocument = challengeResponse.document

                        Log.d(
                            "DZBX",
                            "VidMoly challenge response code=" +
                                challengeResponse.code +
                                " bytes=" + html.length +
                                " title=" + providerDocument.title().take(80) +
                                " sources=" + html.contains("sources", ignoreCase = true) +
                                " hls=" + html.contains("hls", ignoreCase = true) +
                                " m3u8=" + html.contains(".m3u8", ignoreCase = true) +
                                " masterTxt=" +
                                html.contains("master.txt", ignoreCase = true),
                        )
                    } else {
                        Log.w(
                            "DZBX",
                            "VidMoly challenge request failed: " + challengeUrl,
                        )
                    }
                }
            }

            val searchable = buildString {
                append(html.decodeEmbeddedText())

                providerDocument.select("script, noscript, template").forEach { element ->
                    append("\n")
                    append(element.data().decodeEmbeddedText())
                    append("\n")
                    append(element.html().decodeEmbeddedText())

                    val scriptData = element.data().ifBlank { element.html() }
                    runCatching { getAndUnpack(scriptData) }
                        .getOrNull()
                        ?.takeIf { unpacked ->
                            unpacked.isNotBlank() && unpacked != scriptData
                        }
                        ?.let { unpacked ->
                            append("\n")
                            append(unpacked.decodeEmbeddedText())
                        }
                }
            }

            // Use CloudStream's maintained JWPlayer parser first. This is the same
            // parsing path used by the upstream VidMoly extractor.
            for (script in providerDocument.select("script")) {
                val scriptData = script.data().ifBlank { script.html() }
                if (!JWPlayerHelper.canParseJwScript(scriptData)) continue

                val emitted = java.util.Collections.synchronizedList(
                    mutableListOf<ExtractorLink>()
                )

                runCatching {
                    JWPlayerHelper.extractStreamLinks(
                        script = scriptData,
                        sourceName = name,
                        mainUrl = normalized,
                        callback = {
                            emitted.add(it)
                            callback(it)
                        },
                        subtitleCallback = subtitleCallback,
                        headers = mapOf(
                            "User-Agent" to BROWSER_USER_AGENT,
                        ),
                    )
                }.onFailure {
                    Log.w(
                        "DZBX",
                        "VidMoly JWPlayer parser hata: " + it.message,
                    )
                }

                if (emitted.isNotEmpty()) {
                    Log.d(
                        "DZBX",
                        "VidMoly JWPlayer source bulundu: " +
                            normalized +
                            " links=" +
                            emitted.size,
                    )
                    return true
                }
            }

            val streamUrls = LinkedHashSet<String>()

            // Standard JWPlayer/VidMoly source fields.
            Regex(
                """(?is)\b(?:file|src|url|source|hls)\s*[:=]\s*["']((?:https?:)?//[^"']+\.(?:m3u8|mpd|txt|mp4)(?:\?[^"']+)?|/[^"']+\.(?:m3u8|mpd|txt|mp4)(?:\?[^"']+)?)["']"""
            ).findAll(searchable)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .forEach(streamUrls::add)

            // sources: [{ file: "..." }] and sources:[{file:'...'}] variants.
            Regex(
                """(?is)sources\s*[:=]\s*\[\s*\{[^}]*?file\s*[:=]\s*["']([^"']+)["']"""
            ).findAll(searchable)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .forEach(streamUrls::add)

            // Common VidMoly HLS variables.
            Regex(
                """(?is)["']?(?:hls\d+|master|index|playlist)["']?\s*[:=]\s*["']((?:https?:)?//[^"']+\.(?:m3u8|txt)(?:\?[^"']+)?|/[^"']+\.(?:m3u8|txt)(?:\?[^"']+)?)["']"""
            ).findAll(searchable)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .forEach(streamUrls::add)

            // Generic HLS/CDN fallback, including newer VidMoly CDN hostnames.
            Regex(
                """(?i)(?:(?:https?:)?//|/)[^\s"'<>]+?\.(?:m3u8|mpd|txt|mp4)(?:\?[^\s"'<>]*)?"""
            ).findAll(searchable)
                .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                .filter { value ->
                    value.contains("/hls", ignoreCase = true) ||
                        value.contains("master", ignoreCase = true) ||
                        value.contains("index", ignoreCase = true) ||
                        value.contains("playlist", ignoreCase = true) ||
                        value.contains("vmeas", ignoreCase = true) ||
                        value.contains("vmwesa", ignoreCase = true)
                }
                .forEach(streamUrls::add)

            Log.d(
                "DZBX",
                "VidMoly parsed page=" + normalized +
                    " scripts=" + providerDocument.select("script").size +
                    " htmlBytes=" + html.length +
                    " streamCandidates=" + streamUrls.size +
                    " sourcesPattern=" +
                    Regex("""(?is)\bsources\s*[:=]""").containsMatchIn(searchable) +
                    " filePattern=" +
                    Regex("""(?is)\bfile\s*[:=]""").containsMatchIn(searchable) +
                    " hlsPattern=" +
                    Regex("""(?is)\bhls\d+\s*[:=]""").containsMatchIn(searchable),
            )

            if (streamUrls.isEmpty()) {
                val lower = searchable.lowercase()
                Log.w(
                    "DZBX",
                    "VidMoly NO_STREAM sourcePos=" +
                        lower.indexOf("sources") +
                        " hlsPos=" +
                        lower.indexOf("hls") +
                        " page=" + normalized,
                )
            }

            for (rawStreamUrl in streamUrls) {
                val streamUrl = normalizeProviderMediaUrl(rawStreamUrl, normalized)
                if (!isMediaUrl(streamUrl)) continue

                emitMediaLink(streamUrl, normalized, callback)
                Log.d(
                    "DZBX",
                    "VidMoly DIRECT_LINK emitted type=" +
                        if (Regex("(?i)\\.(?:m3u8|txt)(?:$|[?#])").containsMatchIn(streamUrl))
                            "M3U8"
                        else
                            "VIDEO" +
                                " url=" + streamUrl,
                )
                return true
            }
        }

        Log.w("DZBX", "VidMoly source bulunamadı: " + providerUrl)
        return false
    }

    private suspend fun extractOkRuMedia(
        providerUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalized = if (providerUrl.startsWith("//")) "https:$providerUrl" else providerUrl

        val html = runCatching {
            app.get(
                normalized,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                ),
            ).text
        }.getOrNull() ?: return false

        val dataOptions = Regex(
            """(?is)\bdata-options\s*=\s*["']([^"']+)["']"""
        ).find(html)?.groupValues?.getOrNull(1)

        if (dataOptions.isNullOrBlank()) {
            Log.w("DZBX", "OK.ru data-options bulunamadı: $normalized")
            return false
        }

        val decoded = dataOptions
            .decodeEmbeddedText()
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#039;", "'")

        val root = runCatching { org.json.JSONObject(decoded) }.getOrNull()
            ?: return false

        val flashvars = root.optJSONObject("flashvars") ?: return false

        var metadataObject: org.json.JSONObject? = flashvars.optJSONObject("metadata")
        if (metadataObject == null) {
            val metadataRaw = flashvars.optString("metadata")
            if (metadataRaw.isNotBlank()) {
                metadataObject = runCatching {
                    org.json.JSONObject(metadataRaw)
                }.getOrNull()
            }
        }

        val metadata = metadataObject ?: flashvars
        var found = false

        val videos = metadata.optJSONArray("videos")
            ?: flashvars.optJSONArray("videos")

        if (videos != null) {
            val qualityMap = mapOf(
                "full" to Qualities.P1080.value,
                "hd" to Qualities.P720.value,
                "sd" to Qualities.P480.value,
                "low" to Qualities.P360.value,
                "lowest" to Qualities.P240.value,
                "mobile" to Qualities.P240.value,
            )

            for (i in 0 until videos.length()) {
                val video = videos.optJSONObject(i) ?: continue
                val mediaUrl = video.optString("url").trim()
                if (mediaUrl.isBlank()) continue

                val label = video.optString("name").ifBlank { "OK.ru" }
                val quality = qualityMap[label.lowercase()]
                    ?: qualityFromUrl(mediaUrl)

                callback(
                    newExtractorLink(
                        source = name,
                        name = "OK.ru",
                        url = mediaUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        referer = normalized
                        this.quality = quality
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to normalized,
                        )
                    }
                )

                Log.d("DZBX", "OK.ru MP4: quality=$label url=$mediaUrl")
                found = true
            }
        }

        val hlsUrl = metadata.optString("hlsManifestUrl").ifBlank {
            flashvars.optString("hlsManifestUrl")
        }

        if (hlsUrl.isNotBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "OK.ru HLS",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    referer = normalized
                    quality = Qualities.P1080.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to normalized,
                    )
                }
            )
            Log.d("DZBX", "OK.ru HLS: $hlsUrl")
            found = true
        }

        return found
    }

    private suspend fun extractProviderMedia(
        providerUrl: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val providerCandidates = LinkedHashSet<String>()
        providerCandidates.add(providerUrl)
        vidMolyClassicUrl(providerUrl)?.let(providerCandidates::add)

        var found = false

        for (pageUrl in providerCandidates) {
            val providerResponse = runCatching {
                app.get(
                    pageUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to episodeUrl,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                        "Sec-Fetch-User" to "?1",
                    ),
                )
            }.getOrNull() ?: continue

            val providerDocument = providerResponse.document
            val providerHtml = buildString {
                append(providerDocument.html())
                providerDocument.select("script, noscript, template").forEach {
                    append("\n")
                    append(it.data())
                    append("\n")
                    append(it.html())
                }
            }.decodeEmbeddedText()

            // Search both raw and unpacked JavaScript.
            val unpackedHtml = runCatching { getAndUnpack(providerHtml) }
                .getOrDefault(providerHtml)
            val searchableHtml = if (unpackedHtml == providerHtml) {
                providerHtml
            } else {
                providerHtml + "\n" + unpackedHtml
            }

            val sourceUrls = LinkedHashSet<String>()

            PROVIDER_SOURCE_PATTERN.findAll(searchableHtml)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .map { it.decodeEmbeddedText() }
                .forEach(sourceUrls::add)

            PROVIDER_ANY_SOURCE_PATTERN.findAll(searchableHtml)
                .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
                .map { it.decodeEmbeddedText() }
                .forEach(sourceUrls::add)

            VMEAS_M3U8_PATTERN.findAll(searchableHtml)
                .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                .forEach(sourceUrls::add)

            GENERIC_M3U8_PATTERN.findAll(searchableHtml)
                .map { it.value.trimEnd(')', ']', '}', ';', ',') }
                .forEach(sourceUrls::add)

            for (rawSource in sourceUrls) {
                val mediaUrl = normalizeProviderMediaUrl(rawSource, pageUrl)
                if (!isMediaUrl(mediaUrl)) continue

                emitMediaLink(mediaUrl, pageUrl, callback)
                found = true
            }

            providerDocument.select("track[src], track[data-src]").forEach { track ->
                val subtitle = track.attr("src").ifBlank { track.attr("data-src") }
                if (subtitle.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            track.attr("label").ifBlank { "Türkçe" },
                            fixUrl(subtitle),
                        )
                    )
                }
            }

            if (found) return true
        }

        return found
    }

    private fun normalizeProviderMediaUrl(raw: String, pageUrl: String): String {
        val source = raw.trim()
        return when {
            source.startsWith("//") -> "https:$source"
            source.startsWith("/") -> (originOf(pageUrl) ?: mainUrl) + source
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> source
            else -> source
        }.replace("\\/", "/")
    }

    private fun vidMolyClassicUrl(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val id = Regex("(?i)/w/([a-z0-9]+)$").find(path)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)/v/([a-z0-9]+)$").find(path)?.groupValues?.getOrNull(1)
            ?: Regex("(?i)/embed-([a-z0-9]+)\\.html$").find(path)?.groupValues?.getOrNull(1)
            ?: return null

        val origin = originOf(url) ?: "https://vidmoly.biz"
        return "$origin/embed-$id.html"
    }
    private fun originOf(url: String): String? {
        return runCatching {
            val uri = URI(url)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return@runCatching null
            "${uri.scheme}://${uri.host}"
        }.getOrNull()
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        return document.select("a[href]")
            .mapNotNull { element ->
                val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val label = element.text().trim()
                val fullMatch = EPISODE_PATTERN.find(href)
                    ?: ALT_EPISODE_PATTERN.find(href)
                val simpleMatch = SIMPLE_EPISODE_PATTERN.find(href)
                val labelMatch = EPISODE_LABEL_PATTERN.find(label)

                if (fullMatch == null && simpleMatch == null && labelMatch == null) {
                    return@mapNotNull null
                }

                val season = fullMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: labelMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 1
                val episode = fullMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: simpleMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: labelMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: return@mapNotNull null

                val displayLabel = label.ifBlank { "$season. Sezon $episode. Bölüm" }

                newEpisode(href) {
                    name = displayLabel
                    this.season = season
                    this.episode = episode
                    posterUrl = posterFromElement(element, href) ?: guessedPoster(href)
                }
            }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
            )
    }

    private fun Element.toSearchResponse(allowEpisode: Boolean): SearchResponse? {
        if (isSiteChromeLink()) return null

        val href = attr("href").trim()
        val absolute = fixUrlNull(href) ?: return null
        val path = absolute.lowercase()

        if (absolute == "$mainUrl/" ||
            path.contains("/dizi-turu/") ||
            path.contains("/tum-bolumler/") ||
            path.contains("/tur/") ||
            path.contains("/kategori/") ||
            path.contains("/tag/") ||
            path.contains("/author/") ||
            path.contains("/page/") ||
            path.contains("/yil/") ||
            path.contains("/sort/")
        ) return null

        val rawTitle = text().trim().ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        if (rawTitle.isBlank()) return null

        val posterFromDom = posterFromElement(this, absolute)
        // Real DiziBOX content cards have a poster image in the same card/container.
        // Header alphabet entries and login/navigation links do not, so this prevents
        // those links from appearing as fake content cards.
        if (posterFromDom == null && !path.contains("/film/")) return null

        val poster = posterFromDom ?: guessedPoster(absolute)
        val title = cleanCardTitle(rawTitle)
        if (title.isBlank()) return null

        if (isEpisodeUrl(absolute)) {
            if (!allowEpisode) return null

            val match = EPISODE_PATTERN.find(absolute)
                ?: ALT_EPISODE_PATTERN.find(absolute)
            val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            val episodeName = buildString {
                append(title)
                if (season != null && episode != null) {
                    append(" - ")
                    append(season)
                    append(". Sezon ")
                    append(episode)
                    append(". Bölüm")
                }
            }

            return newTvSeriesSearchResponse(episodeName, absolute, TvType.TvSeries) {
                posterUrl = poster
            }
        }

        if (path.contains("/film/")) {
            return newMovieSearchResponse(title, absolute, TvType.Movie) {
                posterUrl = poster
            }
        }

        // DiziBOX series pages are root-level slugs.
        return newTvSeriesSearchResponse(title, absolute, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    private fun Element.isSiteChromeLink(): Boolean {
        val value = text().trim().replace(Regex("\\s+"), " ").lowercase()

        if (value == "dizibox" ||
            value == "dizibox izle" ||
            value == "üye ol" ||
            value == "üye girişi" ||
            value == "üye girişi yap" ||
            value == "anasayfa" ||
            value == "diziler" ||
            value == "bölümler" ||
            value == "filmler" ||
            value == "sonraki »" ||
            value == "son »" ||
            value == "sonraki" ||
            value == "en yeniler" ||
            value == "en çok yorumlananlar" ||
            value == "imdb puanı" ||
            value == "tüm diziler" ||
            value == "tüm filmler" ||
            value == "dizi arşivi" ||
            value == "mobil uygulam indir" ||
            value == "mobil uygulama indir" ||
            value == "iletişim" ||
            value == "iletişim / reklam" ||
            Regex("^[a-z]$").containsMatchIn(value) ||
            value == "#" ||
            Regex("^(?:19|20)\\d{2}$").containsMatchIn(value)
        ) return true

        var current: Element? = this
        var depth = 0
        while (current != null && depth < 8) {
            val tag = current.tagName().lowercase()
            if (tag == "header" || tag == "nav" || tag == "footer" || tag == "aside") {
                return true
            }

            val marker = buildString {
                append(current.id())
                append(' ')
                append(current.classNames().joinToString(" "))
            }.lowercase()

            if (Regex("\\b(site[-_]?header|site[-_]?footer|navbar|navigation|main[-_]?menu|mobile[-_]?menu|side[-_]?bar|sidebar|widget|login|register|alphabet|social|user[-_]?menu)\\b")
                    .containsMatchIn(marker)
            ) return true

            current = current.parent()
            depth++
        }

        return false
    }

    private fun posterFromElement(element: Element, targetUrl: String? = null): String? {
        // 1) Most DiziBOX cards put the poster directly inside the <a>.
        element.selectFirst("img, picture img")?.let { image ->
            posterOfElement(image)?.let { return it }
        }

        val targetSlug = targetUrl
            ?.let { runCatching { URI(it).path.trimEnd('/').substringAfterLast('/') }.getOrNull() }
            ?.lowercase()
            ?.replace(Regex("-(?:\\d+x\\d+|\\d+)$"), "")
            .orEmpty()

        // 2) Some layouts keep the title link and image in the same card container.
        //    Only inspect a few nearby ancestors and never climb into header/nav/sidebar.
        var current: Element? = element.parent()
        var depth = 0
        while (current != null && depth < 4) {
            val tag = current.tagName().lowercase()
            if (tag == "header" || tag == "nav" || tag == "footer" || tag == "aside") break

            val marker = (current.id() + " " + current.classNames().joinToString(" ")).lowercase()
            val looksLikeCard = Regex("\\b(card|item|post|entry|movie|film|series|dizi|content|thumbnail|list|archive)\\b")
                .containsMatchIn(marker)

            val images = current.select("img")
            if (images.size == 1 || looksLikeCard) {
                val preferred = images.firstOrNull { image ->
                    val imageUrl = posterOfElement(image).orEmpty().lowercase()
                    targetSlug.isNotBlank() && imageUrl.contains(targetSlug)
                } ?: images.firstOrNull()

                preferred?.let { image ->
                    posterOfElement(image)?.let { return it }
                }
            }

            current = current.parent()
            depth++
        }

        return null
    }

    private fun cleanCardTitle(raw: String): String {
        var title = raw.trim().replace(Regex("\\s+"), " ")
        title = title.replace(
            Regex("(?i)^IMDb\\s*[0-9]+(?:[.,][0-9]+)?(?:\\s*/\\s*10)?\\s*"),
            "",
        )
        title = title.replace(Regex("\\s+(?:19|20)\\d{2}\\s*$"), "")
        return title.trim()
    }

    private fun guessedPoster(url: String): String? {
        val slug = runCatching {
            URI(url).path.trimEnd('/').substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        if (slug.contains("-sezon-") || slug.contains("-bolum")) {
            return null
        }

        return "$mainUrl/wp-content/uploads/afisler/$slug-220x140.jpg"
    }

    private fun extractUrlFromElement(element: Element): String? {
        val attrs = listOf(
            "href",
            "src",
            "data-url",
            "data-href",
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-link",
            "data-video",
            "data-iframe",
            "data-embed",
            "data-player",
            "data-embed-url",
            "data-player-url",
            "data-video-url",
            "data-stream",
        )

        for (attribute in attrs) {
            val raw = element.attr(attribute).trim()
            if (raw.isBlank()) continue

            val decoded = raw.decodeEmbeddedText()
            val direct = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
                .find(decoded)
                ?.value
                ?.trimEnd(')', ']', '}', ';', ',')

            if (!direct.isNullOrBlank()) return direct

            if (decoded.startsWith("//") || decoded.startsWith("/")) {
                return fixUrl(decoded)
            }
        }

        return null
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return EPISODE_PATTERN.containsMatchIn(url) ||
            ALT_EPISODE_PATTERN.containsMatchIn(url) ||
            SIMPLE_EPISODE_PATTERN.containsMatchIn(url)
    }

    private fun isExternalPlayer(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vidmoly") ||
            value.contains("moly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki") ||
            value.contains("doodstream") ||
            value.contains("streamtape") ||
            value.contains("filemoon") ||
            value.contains("oynatloload.top") ||
            value.contains("vidmoly.biz")
    }

    private fun isMediaUrl(url: String): Boolean {
        val value = url.lowercase()

        val directMedia = Regex(
            "(?i)\\.(m3u8|mpd|mp4|webm)(?:$|[?#])"
        ).containsMatchIn(value)

        // VidMoly can expose an HLS master playlist as master.txt.
        // Only playlist-like .txt paths are accepted here so subtitles/text files
        // are not mistaken for video streams.
        val hlsTextManifest = Regex(
            "(?i)/(?:[^/?#]+/)*(?:master|index|playlist)\\.txt(?:$|[?#])"
        ).containsMatchIn(value)

        val hls2TextManifest =
            value.contains("/hls2/") &&
                Regex("(?i)\\.(?:m3u8|txt)(?:$|[?#])").containsMatchIn(value)

        return directMedia ||
            hlsTextManifest ||
            hls2TextManifest ||
            (value.contains(".vmeas.cloud/") && value.contains(".m3u8"))
    }

    private fun String.decodeEmbeddedText(): String {
        return this
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&#039;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&#x2F;", "/", ignoreCase = true)
            .replace("&#47;", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
    }

    private fun pageTitle(document: Document): String? {
        return document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun pagePlot(document: Document): String? {
        return document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .plot, article p, main p")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun pageYear(document: Document): Int? {
        val yearRegex = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")

        // First use fields that are normally the actual release/year label on the title card.
        val visibleYearSelectors = listOf(
            ".year", ".release-year", ".release_date", ".release-date",
            ".meta .year", ".post-meta .year", ".movie-year", ".dizi-year",
            "[itemprop='copyrightYear']", "[itemprop='releaseDate']"
        )

        for (selector in visibleYearSelectors) {
            val value = document.select(selector).joinToString(" ") { element ->
                element.text() + " " + element.attr("content")
            }
            yearRegex.find(value)?.value?.toIntOrNull()?.let { return it }
        }

        // Some cards put the year in the title/description rather than a dedicated field.
        val focusedText = listOfNotNull(
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property='og:title']")?.attr("content"),
            document.selectFirst("meta[name='description']")?.attr("content"),
            document.selectFirst("meta[property='og:description']")?.attr("content"),
        ).joinToString(" ")

        yearRegex.find(focusedText)?.value?.toIntOrNull()?.let { return it }

        // Prefer releaseDate in JSON-LD. datePublished is deliberately not used because it
        // is often the site's upload date rather than the series/movie release year.
        for (script in document.select("script[type='application/ld+json']")) {
            val json = script.data().ifBlank { script.html() }
            val release = Regex(
                """(?is)[\\\"']?releaseDate[\\\"']?\\s*:\\s*[\\\"']([^\\\"']+)"""
            ).find(json)?.groupValues?.getOrNull(1)
            yearRegex.find(release.orEmpty())?.value?.toIntOrNull()?.let { return it }
        }

        // Do not scan the entire document as a final fallback: that often returns the
        // website footer/copyright year (for example 2026) instead of the title's year.
        return null
    }

    private fun pageGenres(document: Document): List<String> {
        val result = linkedSetOf<String>()

        // DiziBox uses genre/category links on title pages. Prefer links because
        // they are less likely to capture unrelated words from the page.
        document.select(
            "a[href*='/tur/'], a[href*='/kategori/'], " +
                ".genres a, .genre a, .genres a[href], .genre a[href], " +
                ".movie-genres a, .dizi-genres a, .categories a"
        ).forEach { element ->
            val value = element.text()
                .replace(Regex("(?i)^(t[uü]r|t[uü]rler|kategori|kategoriler)\\s*:\\s*"), "")
                .trim()
            if (value.isNotBlank()) result.add(normalizeGenre(value))
        }

        // Some templates expose the genres as plain text instead of links.
        if (result.isEmpty()) {
            val candidates = document.select(
                ".genres, .genre, .movie-genres, .dizi-genres, .categories, " +
                    "[class*='genre'], [class*='kategori']"
            ).flatMap { it.text().split(",", "|", "•", "·", "/") }

            candidates.forEach { raw ->
                val value = raw
                    .replace(Regex("(?i)^(t[uü]r|t[uü]rler|kategori|kategoriler)\\s*:\\s*"), "")
                    .trim()
                if (value.isNotBlank() && value.length <= 40) {
                    result.add(normalizeGenre(value))
                }
            }
        }

        return result.filter { it.isNotBlank() }.distinct().take(10)
    }

    private fun normalizeGenre(value: String): String {
        val key = value.trim().lowercase()
        return when (key) {
            "action", "aksiyon" -> "Aksiyon"
            "adventure", "macera" -> "Macera"
            "animation", "animasyon" -> "Animasyon"
            "comedy", "komedi" -> "Komedi"
            "crime", "suç", "suc" -> "Suç"
            "documentary", "belgesel" -> "Belgesel"
            "drama", "dram" -> "Dram"
            "family", "aile" -> "Aile"
            "fantasy", "fantastik" -> "Fantastik"
            "history", "tarih" -> "Tarih"
            "horror", "korku" -> "Korku"
            "music", "müzik", "muzik" -> "Müzik"
            "mystery", "gizem" -> "Gizem"
            "romance", "romantik" -> "Romantik"
            "science fiction", "sci-fi", "bilim kurgu", "bilimkurgu" -> "Bilim Kurgu"
            "thriller", "gerilim" -> "Gerilim"
            "war", "savaş", "savas" -> "Savaş"
            "western" -> "Western"
            else -> value.trim()
        }
    }

    private fun pageRating(document: Document): Double? {
        val text = document.text()
        return Regex("(?i)(?:IMDb|IMDB)\\s*[:/]?\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun posterOf(document: Document): String? {
        return document.selectFirst("meta[property='og:image']")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: document.selectFirst("img")?.let(::posterOfElement)
    }

    private fun posterOfElement(element: Element): String? {
        val raw = element.attr("data-src")
            .ifBlank { element.attr("data-lazy-src") }
            .ifBlank { element.attr("data-original") }
            .ifBlank { element.attr("data-image") }
            .ifBlank { element.attr("data-lazy-srcset") }
            .ifBlank { element.attr("data-srcset") }
            .ifBlank { element.attr("srcset") }
            .ifBlank { element.attr("src") }

        val firstUrl = Regex("https?://[^\\s,]+", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.value
            ?.trimEnd(',')

        val firstRelative = raw
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

        return firstUrl
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: firstRelative?.let(::fixUrl)
    }

    private fun hostLabel(url: String): String {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "DiziBoxizle"
    }

    private fun qualityFromUrl(url: String): Int {
        val value = url.lowercase()
        return when {
            "2160" in value || "4k" in value -> Qualities.P2160.value
            "1440" in value -> Qualities.P1440.value
            "1080" in value -> Qualities.P1080.value
            "720" in value -> Qualities.P720.value
            "480" in value -> Qualities.P480.value
            "360" in value -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun withPage(url: String, page: Int): String {
        if (page <= 1) return url
        return "${url.trimEnd('/')}/page/$page/"
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextPage = page + 1
        return document.select("a[href]").any { element ->
            val href = fixUrlNull(element.attr("href")).orEmpty()
            val label = element.text().trim().lowercase()

            label.contains("sonraki") ||
                label.contains("next") ||
                label == "»" ||
                href.endsWith("/page/$nextPage/") ||
                href.contains("/page/$nextPage/?")
        }
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"

        // VidMoly classic embeds commonly expose:
        // sources: [{ file: "https://.../master.m3u8?..." }]
        private val PROVIDER_SOURCE_PATTERN = Regex(
            """(?is)\bsources\s*:\s*\[\s*\{\s*["']?file["']?[\s:=]*["']([^"']+)["']"""
        )

        // Fallback for variants using src/url/source/hls directly.
        private val PROVIDER_ANY_SOURCE_PATTERN = Regex(
            "(?is)\\b(?:file|src|url|source|hls)\\s*[:=]\\s*[\"'](https?://[^\"']+(?:m3u8|mpd|txt)(?:\\?[^\"']+)?)['\"]"
        )

        private val VMEAS_M3U8_PATTERN = Regex(
            "https?://[a-z0-9.-]+\\.vmeas\\.cloud/[^\\s\"'<>]+?\\.m3u8(?:\\?[^\\s\"'<>]+)?",
            RegexOption.IGNORE_CASE,
        )

        private val GENERIC_M3U8_PATTERN = Regex(
            "https?://[^\\s\"'<>]+(?:master|index|playlist)[^\\s\"'<>]*\\.m3u8(?:\\?[^\\s\"'<>]+)?",
            RegexOption.IGNORE_CASE,
        )

        // /the-lowdown-1-sezon-1-bolum/
        private val EPISODE_PATTERN = Regex(
            "(?i)-(\\d+)-sezon-(\\d+)-bolum(?:/|$)"
        )

        // Fallback for /.../sezon-1-bolum-1/ style URLs.
        private val ALT_EPISODE_PATTERN = Regex(
            "(?i)/sezon-(\\d+)-bolum-(\\d+)(?:/|$)"
        )

        // Some current DiziBOX pages expose episode URLs as /...-1-bolum/.
        private val SIMPLE_EPISODE_PATTERN = Regex(
            "(?i)-(\\d+)-bolum(?:/|$)"
        )

        // Use visible episode text when the site changes the URL slug format.
        // Captures: season, episode.
        private val EPISODE_LABEL_PATTERN = Regex(
            "(?i)(\\d+)\\.\\s*Sezon\\s*(\\d+)\\.\\s*Bölüm"
        )
    }
}
