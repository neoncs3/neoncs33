package com.dizican

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

private fun cleanTitle(raw: String?): String = (raw ?: "")
    .replace(Regex("""\s*\|.*$"""), "")
    .replace(Regex("""\s*[-–]\s*(?:HDFilm|Film|Dizi|Full).*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\s*\(\d{4}\).*$"""), "")
    .replace(Regex("""\s+(?:Türkçe|Dublaj|Altyazılı)\b.*$""", RegexOption.IGNORE_CASE), "")
    .trim().trim('-', '–', '|', ',')

class DiziCaNProvider : MainAPI() {
    private val uaHeaders get() = browserHeaders

    override var mainUrl = "https://dizican.cc"
    override var name = "DiziCaN"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val mainPage get() = ROWS.mapNotNull { rowName ->
        if (!Settings.rowEnabled(rowName)) return@mapNotNull null
        MainPageData(rowName, when (rowName) {
            "BL" -> "https://dizican.cc/category/bl-filmleri-izle/"
            "Fantastik" -> "https://dizican.cc/category/fantastik-filmleri-izle/"
            "Gençlik" -> "https://dizican.cc/category/genclik/"
            "Gerilim" -> "$mainUrl/category/gerilim-filmleri-izle/"
            "Aksiyon" -> "$mainUrl/dizi-kategori/aksiyon-dizileri-izle/"
            "Dram" -> "$mainUrl/dizi-kategori/dram-dizileri-izle/"
            "Komedi" -> "$mainUrl/dizi-kategori/komedi-dizileri-izle/"
            "Korku" -> "$mainUrl/dizi-kategori/korku-dizileri-izle/"
            "Romantik" -> "$mainUrl/dizi-kategori/romantik-dizileri-izle/"
            "Bilim Kurgu" -> "$mainUrl/dizi-kategori/bilim-kurgu-dizileri-izle/"
            "Gizem" -> "$mainUrl/dizi-kategori/gizem-dizileri-izle/"
            "Suç" -> "$mainUrl/dizi-kategori/suc-dizileri-izle/"
            else -> mainUrl
        })
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name in ROWS) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val doc = app.get(request.data, headers = uaHeaders).document
            return newHomePageResponse(request.name, cards(doc), hasNext = false)
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    companion object {
        val ROWS = listOf("BL", "Fantastik", "Gençlik", "Gerilim", "Aksiyon", "Dram",
            "Komedi", "Korku", "Romantik", "Bilim Kurgu", "Gizem", "Suç")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        val first = cards(app.get(url, headers = uaHeaders).document, query)
        if (first.isNotEmpty() || !query.contains(' ')) return first
        val token = query.trim().split(Regex("\\s+")).first()
        return cards(app.get("$mainUrl/?s=" + java.net.URLEncoder.encode(token, "UTF-8"), headers = uaHeaders).document, token)
    }

    private fun cards(doc: Document, query: String? = null): List<SearchResponse> =
        doc.select("div.col-lg-8 a[href], div.dz-grid a[href]").ifEmpty { doc.select("a[href]") }.mapNotNull { card ->
        val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
        val path = card.attr("href").substringAfter(mainUrl, card.attr("href")).substringBefore('?')
        if (listOf("/", "/bolum/").none { path.startsWith(it) } || path.trim('/').length < 4) return@mapNotNull null
        if (path.startsWith("/ulke/") || path.startsWith("/category/") || path.startsWith("/dizi-kategori/")) return@mapNotNull null
        if (path.contains("login") || path.contains("uye-ol") || path.contains("/giris")) return@mapNotNull null
        val img = card.selectFirst("img") ?: return@mapNotNull null
        val boxTitle = card.parents().firstOrNull { it.hasClass("movie-box") || it.tagName() == "article" }
            ?.selectFirst(".name, .title, h2, h3")?.text()?.trim().orEmpty()
        val title = card.attr("title").trim()
            .ifBlank { boxTitle }
            .ifBlank { img.attr("alt").trim() }
            .ifBlank { card.text().trim() }
            .takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (query != null && !title.contains(query, ignoreCase = true) &&
            !href.contains(query, ignoreCase = true)) return@mapNotNull null
        val hrefLower = href.lowercase()
        val isSeries = hrefLower.contains("/dizi/") || hrefLower.contains("/seri/") ||
            hrefLower.contains("/anime/") || hrefLower.contains("/series/")
        if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = fixUrlNull(img.posterSrcLazy())
            }
        }
    }.distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = uaHeaders)
        val doc = resp.document
        val raw = resp.text
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1")?.text()
                ?: throw ErrorLoadingException("$name: başlık okunamadı")
        ).ifBlank { throw ErrorLoadingException("$name: başlık okunamadı") }
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
        val score = Score.from10(
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")
                ?: Regex(""""(?:imdbPuani|ratingValue|imdb)"\s*:\s*"?([0-9.]+)""").find(raw)?.groupValues?.get(1)
        )
        val basePath = url.substringAfter(mainUrl).substringBefore('?').trimEnd('/')
        val slug = basePath.trim('/').split('/').lastOrNull().orEmpty()
        val slugBase = slug.removeSuffix("-izle").removeSuffix("-film-izle")
        val episodes = doc.select("a[href]").mapNotNull { anchor ->
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            val path = href.substringAfter(mainUrl).substringBefore('?')
            if (basePath.isNotBlank() && !path.startsWith(basePath) &&
                !path.contains(slug) && !path.contains(slugBase)) return@mapNotNull null
            if (!path.contains("/bolum/")) return@mapNotNull null
            val seasonEp = Regex("""(\d+)-sezon-(\d+)-bolum""").find(path)
            val number = seasonEp?.groupValues?.get(2)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?:bolum|episode)[^/]*?[-_](\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(href) {
                if (seasonEp != null) this.season = seasonEp.groupValues[1].toIntOrNull() ?: 1
                this.episode = number
                this.name = anchor.text().trim().ifBlank { "Bölüm $number" }
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val firstResp = app.get(data, headers = uaHeaders)
        val baseData = data.trimEnd('/')
        val partUrls = (listOf(data) + firstResp.document.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (href.trimEnd('/').matches(Regex(Regex.escape(baseData) + """/\d+"""))) href else null
        }).distinct().take(6)

        var found = false
        val seen = mutableSetOf<String>()
        suspend fun emit(stream: String, label: String, referer: String, explicitQuality: Int? = null) {
            if (!stream.startsWith("http") || !seen.add(stream)) return
            val isM3u8 = stream.contains(".m3u8")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality = explicitQuality ?: if (isM3u8) Qualities.Unknown.value else getQualityFromName(label)
            if (Settings.qualityAllowed(quality)) {
                callback.invoke(newExtractorLink(name, label, stream, type) {
                    this.referer = referer
                    this.quality = quality
                })
                found = true
            }
            if (isM3u8 && explicitQuality == null) {
                val playlist = runCatching { app.get(stream, headers = uaHeaders, referer = referer).text }.getOrNull().orEmpty()
                for ((h, vUrl) in parseHlsVariants(stream, playlist)) {
                    emit(vUrl, "$label ${h}p", referer, h)
                }
            }
        }

        for (pageUrl in partUrls) {
            val resp = if (pageUrl == data) firstResp else runCatching { app.get(pageUrl, headers = uaHeaders, referer = data) }.getOrNull() ?: continue
            val text = resp.text.replace("\\/", "/").replace("\\\"", "\"")
            val media = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(text)
            for (m in media) emit(m.value, "Direct", pageUrl)
            val embeds = Regex(""""(?:embedUrl|contentUrl|embed_url|player_url)"\s*:\s*"([^"]+)""")
                .findAll(text).map { it.groupValues[1] }.toList()
            for (embed in embeds) {
                val absolute = fixUrl(embed)
                if (absolute == pageUrl) continue
                if (!absolute.contains("embed") && !absolute.contains(".mp4") && !absolute.contains(".m3u8")) continue
                if (absolute.matches(Regex(""".*\.(?:mp4|m3u8).*"""))) {
                    emit(absolute, "Embed", pageUrl)
                    continue
                }
                val inner = runCatching { app.get(absolute, headers = uaHeaders, referer = pageUrl).text }.getOrNull() ?: continue
                val innerMedia = Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(inner)
                for (m in innerMedia) emit(m.value, "Embed", absolute)
            }
            val regexFrames = Regex("""<iframe\b[^>]*?\b(?:src|data-src|vdo-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(text).mapNotNull { fixUrlNull(it.groupValues[1]?.replace("&amp;", "&")) }.toList()
            val frames = (resp.document.select("iframe").mapNotNull {
                val raw = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("vdo-src") }
                fixUrlNull(raw)
            } + regexFrames).distinct()
            for (frame in frames) {
                if (!frame.startsWith("http") || frame.endsWith("/embed/") || frame.endsWith("/video/") ||
                    frame.contains("vzaar.com") || frame.contains("youtu")) continue
                Regex("""[?&]video=([^&#"'\s]+)""").find(frame)?.groupValues?.get(1)?.let { enc ->
                    val decoded = runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(enc)
                    val resolved = runCatching { java.net.URI(frame).resolve(decoded).toString() }.getOrDefault(decoded)
                    if (resolved.contains(".m3u8") || resolved.contains(".mp4")) {
                        emit(resolved, "HintDizi", frame)
                        return@let
                    }
                }
                if (frame.contains(".mp4") || frame.contains(".m3u8")) {
                    emit(frame, "Embed", pageUrl)
                    continue
                }
                if (frame.contains("ok.ru") || frame.contains("odnoklassniki")) {
                    if (loadOkRu(frame, callback)) found = true
                    continue
                }
                val seenBefore = seen.size
                val inner = runCatching { app.get(frame, headers = uaHeaders, referer = pageUrl).text }.getOrNull()
                if (inner != null) {
                    val innerText = inner.replace("\\/", "/")
                    for (m in Regex("""https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""").findAll(innerText)) {
                        emit(m.value, "Embed", frame)
                    }
                    for (sub in Regex("""<iframe[^>]+(?:src|data-src)="([^"]+)""").findAll(innerText)) {
                        val subUrl = fixUrl(sub.groupValues[1])
                        if (loadExtractor(subUrl, frame, subtitleCallback, callback)) found = true
                    }
                }
                if (seen.size == seenBefore && loadExtractor(frame, "$mainUrl/", subtitleCallback, callback)) found = true
            }
            val subs = Regex("""https?://[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)""").findAll(text)
            for (m in subs) {
                if (Settings.subtitleAllowed("tr")) subtitleCallback.invoke(SubtitleFile("tr", m.value))
            }
        }
        if (!found) {
            throw ErrorLoadingException("$name: oynatılabilir akış yok")
        }
        return found
    }

    private suspend fun loadOkRu(embed: String, callback: (ExtractorLink) -> Unit): Boolean {
        val id = Regex("""videoembed/(\d+)""").find(embed)?.groupValues?.get(1) ?: return false
        val body = runCatching {
            app.post(
                "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id",
                data = emptyMap(),
                headers = mapOf("user-agent" to "Mozilla/5.0 (Linux; Android 13)", "Referer" to "https://ok.ru/"),
            ).text
        }.getOrNull() ?: return false
        val videos = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull() ?: return false
        var found = false
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val stream = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("name").ifBlank { "OkRu" }
            val q = when (label.lowercase()) {
                "mobile", "lowest" -> 240
                "low" -> 360
                "sd" -> 480
                "hd" -> 720
                "full" -> 1080
                else -> getQualityFromName(label.uppercase())
            }
            if (!Settings.qualityAllowed(q)) continue
            callback.invoke(
                newExtractorLink(name, "OkRu $label", stream, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = q
                }
            )
            found = true
        }
        return found
    }
}


// -----------------------------------------------------------------------------
// DiziCaN'a özel küçük yardımcılar. Başka modüllere bağlı değildir.
// -----------------------------------------------------------------------------

private val browserHeaders: Map<String, String> = mapOf(
    "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "accept-language" to "tr-TR,tr;q=0.9",
)

private val PLACEHOLDER_IMG = Regex(
    """^(data:|about:|javascript:)|/(pixel|blank|lazy|placeholder|empty|spacer|transparent|altyazi|dublaj|tr-flag|en-flag)\.(png|gif|jpg|jpeg|svg|webp)""",
    RegexOption.IGNORE_CASE,
)
private val BG_URL = Regex("""url\(['"]?([^'")\s]+)['"]?\)""", RegexOption.IGNORE_CASE)

private fun Element.posterSrcLazy(): String? {
    for (attrName in listOf("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "data-lazy", "data-bg", "data-background", "data-image", "data-poster", "src")) {
        val value = attr(attrName).trim()
        if (value.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(value)) return value
    }
    for (attrName in listOf("data-srcset", "srcset")) {
        val value = attr(attrName).trim().substringBefore(',').trim().substringBefore(' ').trim()
        if (value.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(value)) return value
    }
    val style = attr("style").trim()
    if (style.isNotEmpty()) {
        val background = BG_URL.find(style)?.groupValues?.get(1)?.trim().orEmpty()
        if (background.isNotEmpty() && !PLACEHOLDER_IMG.containsMatchIn(background)) return background
    }
    return null
}

private val HLS_RES = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
private val HLS_NAME = Regex("""NAME=\"(\d{3,4})p?\"""", RegexOption.IGNORE_CASE)

private fun parseHlsVariants(masterUrl: String, m3u8Text: String): List<Pair<Int, String>> {
    if (!m3u8Text.contains("#EXT-X-STREAM-INF") ||
        m3u8Text.contains("TYPE=AUDIO", ignoreCase = true) ||
        m3u8Text.contains("AUDIO=", ignoreCase = true)
    ) return emptyList()

    val base = runCatching { URI(masterUrl.trim()) }.getOrNull() ?: return emptyList()
    val lines = m3u8Text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val variants = ArrayList<Pair<Int, String>>()
    val seen = HashSet<String>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val height = HLS_RES.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: HLS_NAME.find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            var next = index + 1
            while (next < lines.size && lines[next].startsWith("#")) next++
            if (next < lines.size) {
                val target = runCatching {
                    val raw = lines[next]
                    if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else base.resolve(raw).toString()
                }.getOrNull()
                if (!target.isNullOrBlank() && height > 0 && seen.add("$height|$target")) {
                    variants.add(height to target)
                }
                index = next
            }
        }
        index++
    }
    return variants.sortedByDescending { it.first }
}

/**
 * DiziCaN ayarları: kalite sınırı, altyazı tercihi ve ana sayfa satırları.
 * Bu ayar katmanı yalnızca bu eklentiye aittir.
 */
object Settings {
    private var prefs: SharedPreferences? = null
    private val qualityLabels = arrayOf("Sınırsız", "1080p", "720p", "480p", "360p")
    private val qualityValues = intArrayOf(0, 1080, 720, 480, 360)
    private val subtitleLabels = arrayOf("Tümü", "Türkçe", "İngilizce", "Kapalı")
    private val subtitleValues = arrayOf("all", "tr", "en", "off")

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("bigbull_dizican", Context.MODE_PRIVATE)
    }

    var qualityCap: Int
        get() = prefs?.getInt("quality_cap", 0) ?: 0
        set(value) { prefs?.edit()?.putInt("quality_cap", value)?.apply() }

    var subtitleLang: String
        get() = prefs?.getString("subtitle_lang", "all") ?: "all"
        set(value) { prefs?.edit()?.putString("subtitle_lang", value)?.apply() }

    fun qualityAllowed(quality: Int): Boolean {
        val cap = qualityCap
        return cap == 0 || quality <= 0 || quality <= cap
    }

    fun subtitleAllowed(language: String?): Boolean {
        val preference = subtitleLang
        if (preference == "off") return false
        if (preference == "all") return true
        val label = language?.lowercase().orEmpty()
        return when (preference) {
            "tr" -> label.contains("tr") || label.contains("türk") || label.contains("turk")
            "en" -> label.contains("en") || label.contains("ingiliz") || label.contains("english")
            else -> true
        }
    }

    fun rowEnabled(name: String): Boolean =
        prefs?.getStringSet(ROW_KEY, null)?.contains(name) != true

    private fun setRows(names: Collection<String>, enabled: Set<String>) {
        prefs?.edit()?.putStringSet(ROW_KEY, names.filterNot { it in enabled }.toSet())?.apply()
    }

    fun showDialog(context: Context, title: String = "Ayarlar", rows: List<String> = emptyList()) {
        val activity = context as? Activity ?: return
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
        }
        layout.addView(TextView(activity).apply { text = "Kalite üst sınırı" })
        val qualitySpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, qualityLabels)
            setSelection(qualityValues.indexOf(qualityCap).coerceAtLeast(0))
        }
        layout.addView(qualitySpinner)
        layout.addView(TextView(activity).apply { text = "Altyazı" })
        val subtitleSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, subtitleLabels)
            setSelection(subtitleValues.indexOf(subtitleLang).coerceAtLeast(0))
        }
        layout.addView(subtitleSpinner)

        val checks = if (rows.isNotEmpty()) {
            layout.addView(TextView(activity).apply { text = "Ana sayfa kategorileri" })
            val list = rows.map { row ->
                CheckBox(activity).apply {
                    text = row
                    isChecked = rowEnabled(row)
                }
            }
            layout.addView(ScrollView(activity).apply {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    list.forEach { addView(it) }
                })
            })
            list
        } else {
            emptyList()
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                qualityCap = qualityValues[qualitySpinner.selectedItemPosition]
                subtitleLang = subtitleValues[subtitleSpinner.selectedItemPosition]
                if (rows.isNotEmpty()) {
                    setRows(rows, checks.filter { it.isChecked }.map { it.text.toString() }.toSet())
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private const val ROW_KEY = "rows"
}
