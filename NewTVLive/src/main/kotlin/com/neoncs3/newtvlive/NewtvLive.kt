package com.neoncs3.newtvlive

// ============================================================================
// YENİDEN YAZILMIŞ İSKELET - orijinal kaynak DEĞİLDİR.
// NewTVLive.cs3 içindeki dex'ten okunabilen sabitlere (adresler, CSS seçiciler,
// regex'ler) dayanır. Orijinal eklentideki şu parçalar BİLEREK yazılmadı:
//   - /api/playlist-token ve newkso.ru kimlik doğrulama adımları
//   - yerel proxy sunucusu (127.0.0.1)
//   - PIN korumalı yetişkin bölümü
//   - telemetri (WioTelemetry)
// Sitenin HTML yapısı doğrulanamadığı için seçicileri tarayıcıda kontrol et.
// ============================================================================

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// iptv-org API şemasının kullandığımız kısmı (https://iptv-org.github.io/api/)
data class NtlIptvChannel(
    val id: String? = null,
    val name: String? = null,
    @JsonProperty("alt_names") val altNames: List<String>? = null
)

data class NtlIptvLogo(
    val channel: String? = null,
    val url: String? = null
)

class NewTVLive : MainAPI() {
    override var mainUrl = "https://tv.newtane.com"
    override var name = "NewTV Live"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        // Dex'te görülen kanal bağlantısı seçicileri
        private const val CHANNEL_SELECTOR =
            "a.se-stream__link[href], a[href*='/stream/stream-'], " +
                "a[href*='/cast/stream-'], a[href*='/watch/stream-'], [data-url*='/stream-']"

        private val idRegex = Regex("""(?:id=|stream-)(\d+)""")
        private val linkRegex = Regex("""stream-\d+|[?&]id=\d+""")

        // Geniş tarama: kanal bağlantısı gibi görünen her öğe
        private const val GENERIC_SELECTOR = "a[href], [data-url], [data-href]"

        // Ana site boş/erişilemezse kullanılan DaddyLive katalog adresleri
        private val DL_DOMAINS = listOf(
            "https://daddylive.pk",
            "https://dlhd.pk",
            "https://dlhd.st",
            "https://dlstreams.st",
            "https://dlive.sx"
        )
        private const val DL_SELECTOR = "div.grid > a, a.card, a[href*='watch.php?id=']"

        // Dex'te görülen yayın adresi kalıpları
        private val m3u8Quoted = Regex("""["'](https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']""")
        private val srcConst = Regex("""const\s+SRC\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
        private val fileKey = Regex("""(?:streamUrl|file|source)\s*:\s*["'](https?:[^"']+\.m3u8[^"']*)["']""")
        private val atobRegex = Regex("""(?:source\s*:\s*)?window\.atob\(["']([^"']+)["']\)""")

        // Embed yedekleri (dex: dembed.top / dembed.st)
        private val EMBEDS = listOf(
            "https://dembed.top/premiumtv/daddy.php?id=",
            "https://dembed.st/premiumtv/daddy.php?id="
        )

        private val DROP_TOKENS = setOf(
            "hd", "fhd", "sd", "uhd", "4k", "tv", "live", "canli", "channel", "kanal",
            "uk", "usa", "us", "tr", "turkey", "de", "fr", "es"
        )
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
    )

    // ------------------------------------------------------------------ afişler
    private fun normalize(name: String): String {
        val n = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ı", "i")
        return n.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in DROP_TOKENS }
            .joinToString("")
    }

    private val logoMutex = Mutex()
    private var logoMap: Map<String, String>? = null

    private suspend fun logos(): Map<String, String> {
        logoMap?.let { return it }
        return logoMutex.withLock {
            logoMap ?: buildLogoMap().also { if (it.isNotEmpty()) logoMap = it }
        }
    }

    private suspend fun buildLogoMap(): Map<String, String> = runCatching {
        val base = "https://iptv-org.github.io/api"
        val channels = app.get("$base/channels.json", timeout = 30)
            .parsedSafe<Array<NtlIptvChannel>>() ?: return@runCatching emptyMap<String, String>()

        val logoById = HashMap<String, String>()
        app.get("$base/logos.json", timeout = 30)
            .parsedSafe<Array<NtlIptvLogo>>()?.forEach {
                val c = it.channel
                val u = it.url
                if (!c.isNullOrBlank() && !u.isNullOrBlank()) logoById.putIfAbsent(c, u)
            }

        val out = HashMap<String, String>()
        for (ch in channels) {
            val url = logoById[ch.id ?: continue] ?: continue
            for (n in listOfNotNull(ch.name) + (ch.altNames ?: emptyList())) {
                val key = normalize(n)
                if (key.length >= 3) out.putIfAbsent(key, url)
            }
        }
        out
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------ katalog
    private data class Row(val section: String, val item: LiveSearchResponse)

    private val cacheMutex = Mutex()
    private var cache: List<Row> = emptyList()
    private var cacheTime = 0L

    private fun cacheFresh() =
        cache.isNotEmpty() && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS

    private suspend fun getRows(): List<Row> {
        if (cacheFresh()) return cache
        return cacheMutex.withLock {
            if (cacheFresh()) return@withLock cache
            val fresh = fetchRows()
            if (fresh.isNotEmpty()) {
                cache = fresh
                cacheTime = System.currentTimeMillis()
            }
            if (fresh.isNotEmpty()) fresh else cache
        }
    }

    private fun collect(
        doc: Document,
        base: String,
        selector: String,
        defaultSection: String,
        logoDb: Map<String, String>,
        rows: MutableMap<String, Row>
    ) {
        for (el in doc.select(selector)) {
            val href = listOf("href", "data-url", "data-href")
                .map { el.attr(it) }
                .firstOrNull { linkRegex.containsMatchIn(it) } ?: continue
            val id = idRegex.find(href)?.groupValues?.getOrNull(1) ?: continue
            if (rows.containsKey(id)) continue

            val title = el.selectFirst(
                ".card__title, .card-title, .channel-title, .channel-name, .se-stream__name"
            )?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: el.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: el.attr("title").trim().takeIf { it.isNotBlank() }
                ?: el.text().trim().takeIf { it.isNotBlank() }
                ?: continue

            val url = if (href.startsWith("http")) href
            else runCatching { URI(base).resolve(href).toString() }.getOrDefault(fixUrl(href))

            // Bölüm adı: en yakın <section>'ın başlığı veya data-m-sport değeri
            val sec = el.parents().firstOrNull { it.tagName() == "section" }
            val section = sec?.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: sec?.attr("data-m-sport")?.trim()?.takeIf { it.isNotBlank() }
                ?: defaultSection

            val pageImg = el.selectFirst("img")?.let { i ->
                i.absUrl("data-src").ifBlank { i.absUrl("src") }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            val poster = pageImg ?: logoDb[normalize(title)]

            rows[id] = Row(
                section,
                newLiveSearchResponse(title, url, TvType.Live) { this.posterUrl = poster }
            )
        }
    }

    // --- /live sayfası JavaScript ile yüklendiği için sayfanın yaptığı /api/ isteklerini yakala
    private val mapper = ObjectMapper()

    private fun walk(node: JsonNode, out: MutableList<JsonNode>) {
        if (node.isObject) {
            out.add(node)
            node.elements().forEach { walk(it, out) }
        } else if (node.isArray) {
            node.forEach { walk(it, out) }
        }
    }

    private fun JsonNode.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            get(k)?.takeIf { it.isTextual || it.isNumber }?.asText()?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" }
        }

    private suspend fun sniffLiveRows(logoDb: Map<String, String>): List<Row> {
        val resolver = WebViewResolver(
            interceptUrl = Regex("""a^"""), // hiç eşleşmez; tüm istekleri additionalUrls ile topla
            additionalUrls = listOf(Regex("""newtane\.com/api/(?!playlist-token)""")),
            timeout = 15_000L
        )
        val requests = runCatching {
            resolver.resolveUsingWebView("$mainUrl/live", "$mainUrl/").second
        }.getOrDefault(emptyList())

        val rows = linkedMapOf<String, Row>()
        for (req in requests.distinctBy { it.url.toString() }) {
            val body = runCatching {
                app.get(req.url.toString(), headers = req.headers.toMap(), timeout = 15).text
            }.getOrNull() ?: continue
            val root = runCatching { mapper.readTree(body) }.getOrNull() ?: continue

            val nodes = mutableListOf<JsonNode>()
            walk(root, nodes)
            for (n in nodes) {
                val title = n.str("name", "title", "channel_name", "channelName") ?: continue
                val slug = n.str("slug", "channel_id", "channelId", "id") ?: continue
                val logo = n.str("logo", "image", "poster", "thumbnail", "icon", "logo_url")
                // Sadece id+ad taşıyan kayıtlar (tür listesi vb.) kanal sayılmaz
                if (n.str("slug") == null && logo == null) continue
                if (rows.containsKey(slug)) continue
                val section = n.str("category", "group", "genre", "type") ?: "Canlı"
                val poster = logo?.let { fixUrlNull(it) } ?: logoDb[normalize(title)]
                rows[slug] = Row(
                    section,
                    newLiveSearchResponse(title, "$mainUrl/live/$slug", TvType.Live) {
                        this.posterUrl = poster
                    }
                )
            }
            if (rows.isNotEmpty()) break
        }
        return rows.values.toList()
    }

    private suspend fun fetchRows(): List<Row> {
        val logoDb = runCatching { logos() }.getOrDefault(emptyMap())
        val rows = linkedMapOf<String, Row>()

        // 1) /live sayfasının kendi isteklerinden (JSON API)
        sniffLiveRows(logoDb).forEach { rows[it.item.url] = it }

        // 2) Statik HTML (eski seçiciler)
        if (rows.isEmpty()) {
            runCatching { app.get("$mainUrl/live", headers = headers("$mainUrl/")).document }
                .getOrNull()?.let { doc ->
                    collect(doc, mainUrl, CHANNEL_SELECTOR, "Kanallar", logoDb, rows)
                    if (rows.isEmpty()) collect(doc, mainUrl, GENERIC_SELECTOR, "Kanallar", logoDb, rows)
                }
        }

        // 3) Boşsa DaddyLive 24/7 kataloğu
        if (rows.isEmpty()) {
            for (d in DL_DOMAINS) {
                val doc = runCatching {
                    app.get("$d/24-7-channels.php", headers = headers("$d/"), timeout = 15).document
                }.getOrNull() ?: continue
                collect(doc, d, DL_SELECTOR, "24/7 Kanallar", logoDb, rows)
                if (rows.isEmpty()) collect(doc, d, GENERIC_SELECTOR, "24/7 Kanallar", logoDb, rows)
                if (rows.isNotEmpty()) break
            }
        }
        return rows.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = getRows()
            .groupBy({ it.section }, { it.item })
            .map { (section, items) -> HomePageList(section, items, false) }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return getRows().map { it.item }
            .filter { it.name.contains(query.trim(), ignoreCase = true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.isBlank()) throw ErrorLoadingException("Kanal adresi boş")
        val known = runCatching { getRows().firstOrNull { it.item.url == url }?.item }.getOrNull()
        val title = known?.name ?: runCatching {
            app.get(url, headers = headers("$mainUrl/")).document
                .selectFirst("h1, h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Kanal"
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = known?.posterUrl
        }
    }

    // ------------------------------------------------------------------ linkler
    private fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.scheme.isNullOrBlank() || u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    private fun extractM3u8(raw: String): List<String> {
        val text = raw.replace("\\/", "/").replace("&amp;", "&")
        val out = linkedSetOf<String>()
        m3u8Quoted.findAll(text).forEach { out.add(it.groupValues[1]) }
        srcConst.findAll(text).forEach { out.add(it.groupValues[1]) }
        fileKey.findAll(text).forEach { out.add(it.groupValues[1]) }
        atobRegex.findAll(text).forEach {
            val decoded = runCatching {
                String(android.util.Base64.decode(it.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull()
            if (decoded != null && decoded.contains(".m3u8")) out.add(decoded.trim())
        }
        return out.toList()
    }

    private suspend fun emit(mediaUrl: String, pageUrl: String, callback: (ExtractorLink) -> Unit) {
        val origin = originOf(pageUrl) ?: mainUrl
        callback(
            newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Origin" to origin
                )
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = idRegex.find(data)?.groupValues?.getOrNull(1)

        // 1) Kanal sayfası ve içindeki iframe'ler
        val page = runCatching { app.get(data, headers = headers("$mainUrl/")) }.getOrNull()
        if (page != null && page.code in 200..299) {
            extractM3u8(page.text).firstOrNull()?.let {
                emit(it, data, callback); return true
            }
            val frames = page.document.select("iframe[src]")
                .mapNotNull { runCatching { URI(data).resolve(it.attr("src")).toString() }.getOrNull() }
                .distinct()
            for (frame in frames) {
                val inner = runCatching { app.get(frame, headers = headers(data)).text }.getOrNull()
                    ?: continue
                extractM3u8(inner).firstOrNull()?.let {
                    emit(it, frame, callback); return true
                }
            }
        }

        // 2) Embed yedekleri
        if (id != null) {
            for (embed in EMBEDS) {
                val url = "$embed$id"
                val text = runCatching { app.get(url, headers = headers("$mainUrl/")).text }.getOrNull()
                    ?: continue
                extractM3u8(text).firstOrNull()?.let {
                    emit(it, url, callback); return true
                }
            }
        }

        // 3) Son çare: sayfanın kendi oynatıcısını WebView'da çalıştır ve normal
        //    .m3u8 isteğini gözlemle (doğrulamayı oynatıcı kendisi yapar)
        val candidates = buildList {
            add(data)
            if (id != null) EMBEDS.forEach { add("$it$id") }
        }
        for (candidate in candidates) {
            val sniffed = runCatching {
                app.get(
                    candidate,
                    referer = "$mainUrl/",
                    headers = headers("$mainUrl/"),
                    interceptor = WebViewResolver(
                        interceptUrl = Regex("""(?i)\.m3u8(?:\?|$)"""),
                        timeout = 10_000L
                    )
                ).url
            }.getOrNull()
            if (!sniffed.isNullOrBlank() && sniffed.contains(".m3u8", ignoreCase = true)) {
                emit(sniffed, candidate, callback)
                return true
            }
        }
        return false
    }
}
