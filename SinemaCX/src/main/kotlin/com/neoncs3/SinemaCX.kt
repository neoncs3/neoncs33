// Site yapısı ve player akışı için mevcut açık kaynak SinemaCX sağlayıcısından yararlanılmıştır.

package com.neoncs3

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.annotation.JsonProperty

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch = true
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
	/*
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye
	*/

    override val mainPage = mainPageOf(
        "${mainUrl}/page/"			                     to		"Son Eklenen Filmler",
        "${mainUrl}/izle/aile-filmleri/page/"			 to		"Aile Filmleri",
        "${mainUrl}/izle/aksiyon-filmleri/page/"		 to		"Aksiyon Filmleri",
        "${mainUrl}/izle/animasyon-filmleri/page/"		 to		"Animasyon Filmleri",
        "${mainUrl}/izle/belgesel/page/"				 to		"Belgesel Filmleri",
        "${mainUrl}/izle/bilim-kurgu-filmleri/page/"	 to		"Bilim Kurgu Filmler",
        "${mainUrl}/izle/biyografi/page/"				 to		"Biyografi Filmleri",
        "${mainUrl}/izle/fantastik-filmler/page/"		 to		"Fantastik Filmler",
        "${mainUrl}/izle/gizem-filmleri/page/"			 to		"Gizem Filmleri",
        "${mainUrl}/izle/komedi-filmleri/page/"			 to		"Komedi Filmleri",
        "${mainUrl}/izle/korku-filmleri/page/"			 to		"Korku Filmleri",
        "${mainUrl}/izle/macera-filmleri/page/"			 to		"Macera Filmleri",
        "${mainUrl}/izle/romantik-filmler/page/"		 to		"Romantik Filmler",
        "${mainUrl}/izle/erotik-filmler/page/"			 to		"Erotik Film izle",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.son div.frag-k, div.icerik div.frag-k").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.yanac span")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div.yanac a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a.resim img")?.attr("data-src")) ?: fixUrlNull(this.selectFirst("a.resim img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.icerik div.frag-k").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.f-bilgi h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("link[rel='image_src']")?.attr("href"))
        val year        = document.selectFirst("div.f-bilgi ul.detay a[href*='yapim']")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.f-bilgi div.ackl")?.text()?.trim()
        val tags        = document.select("div.f-bilgi div.tur a").map { it.text() }
        val duration    = Regex("""Süre: </span>(\d+) Dakika</li>""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.select("li.oync li.oyuncu-k").map {
            Actor(it.selectFirst("span.isim")!!.text(), it.selectFirst("img")!!.attr("data-src"))
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
        }
    }

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    Log.d("SCX", "data = " + data)

    val document = runCatching {
        app.get(data, headers = mapOf("User-Agent" to SCX_UA), referer = mainUrl + "/").document
    }.getOrNull() ?: return false

    val iframeUrls = document.select("iframe").mapNotNull { iframe ->
        val raw = iframe.attr("data-vsrc").ifBlank {
            iframe.attr("src").ifBlank { iframe.attr("data-src") }
        }.trim()
        if (raw.isBlank()) return@mapNotNull null
        val url = fixUrlNull(raw)?.substringBefore("?img=") ?: return@mapNotNull null
        if (url.contains("youtube", true) || url.contains("youtu.be", true) ||
            url.contains("fragman", true) || url.contains("trailer", true)) null else url
    }.distinct()

    val candidates = if (iframeUrls.isNotEmpty()) iframeUrls else {
        val fallback = if (data.endsWith("/")) data + "2/" else data + "/2/"
        runCatching {
            app.get(fallback, headers = mapOf("User-Agent" to SCX_UA), referer = mainUrl + "/")
                .document.select("iframe").mapNotNull { iframe ->
                    val raw = iframe.attr("data-vsrc").ifBlank {
                        iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    }.trim()
                    if (raw.isBlank()) return@mapNotNull null
                    fixUrlNull(raw)?.substringBefore("?img=")
                }.filterNot { it.contains("youtube", true) || it.contains("youtu.be", true) }
        }.getOrDefault(emptyList()).distinct()
    }

    if (candidates.isEmpty()) return false

    var emitted = false
    for (iframe in candidates) {
        Log.d("SCX", "iframe = " + iframe)

        runCatching {
            val iframeSource = app.get(
                iframe,
                headers = mapOf("User-Agent" to SCX_UA),
                referer = mainUrl + "/"
            ).text
            val subtitleMatch = Regex("""playerjsSubtitle\s*=\s*["'](.+?)["']""", RegexOption.IGNORE_CASE)
                .find(iframeSource)
            subtitleMatch?.groupValues?.getOrNull(1)?.let { section ->
                Regex("""\[(.*?)](https?://[^\s"',]+)""").findAll(section).forEach { match ->
                    val lang = match.groupValues.getOrNull(1).orEmpty().ifBlank { "Türkçe" }
                    val subUrl = match.groupValues.getOrNull(2).orEmpty()
                    if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(lang = lang, url = fixUrl(subUrl)))
                }
            }
        }

        if (iframe.contains("player.filmizle.in", true)) {
            val host = Regex("""https?://([^/]+)""").find(iframe)?.groupValues?.getOrNull(1)
            val token = iframe.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
            if (!host.isNullOrBlank() && !token.isNullOrBlank()) {
                val direct = runCatching {
                    app.post(
                        "https://" + host + "/player/index.php?data=" + token + "&do=getVideo",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest", "User-Agent" to SCX_UA),
                        referer = mainUrl + "/"
                    ).parsedSafe<Panel>()?.securedLink
                }.getOrNull()
                if (!direct.isNullOrBlank()) {
                    callback(newExtractorLink(name, name, direct, if (direct.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value
                        referer = iframe
                        headers = mapOf("Referer" to iframe, "User-Agent" to SCX_UA)
                    })
                    emitted = true
                    continue
                }
            }
        }

        runCatching {
            loadExtractor(iframe, mainUrl + "/", subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }.onFailure {
            Log.e("SCX", "extractor hata: " + it.message)
        }
    }

    return emitted
}
    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
