package com.neoncs3

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Diziyou : MainAPI() {

    override var mainUrl = "https://www.diziyou.one"
    override var name = "Diziyou"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // Cloudflare / yavaş ana sayfa yüklemelerinde yardımcı olur.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    private val storageUrl = "https://storage.diziyou.one"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = app.get(mainUrl).document
        val home = ArrayList<HomePageList>()

        // 1) Popüler dizilerden son bölümler
        val recentEpisodes = document.select("div.dsmobil div.listepisodes").mapNotNull { element ->
            val anchor = element.selectFirst("a") ?: return@mapNotNull null
            val fullUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null

            val slug = fullUrl
                .removePrefix("$mainUrl/")
                .replace(Regex("""-\d+-sezon-\d+-bolum/?$"""), "")
                .trim('/')

            val href = "$mainUrl/$slug/"
            val title = anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?: anchor.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val poster = fixUrlNull(
                anchor.selectFirst("img.lazy")?.attr("data-src")
                    ?: anchor.selectFirst("img")?.attr("data-src")
                    ?: anchor.selectFirst("img")?.attr("src")
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }.distinctBy { it.url }

        if (recentEpisodes.isNotEmpty()) {
            home.add(HomePageList("Son Eklenen Bölümler", recentEpisodes))
        }

        // 2) Son eklenen diziler
        val latestSeries = document.select("div.dsmobil2 div#list-series-main").mapNotNull { element ->
            val href = fixUrlNull(element.selectFirst("div.cat-img-main a")?.attr("href"))
                ?: return@mapNotNull null
            val title = element.selectFirst("div.cat-title-main a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(
                element.selectFirst("div.cat-img-main img")?.attr("data-src")
                    ?: element.selectFirst("div.cat-img-main img")?.attr("src")
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }.distinctBy { it.url }

        if (latestSeries.isNotEmpty()) {
            home.add(HomePageList("Son Eklenen Diziler", latestSeries))
        }

        // 3) Efsane diziler
        val classics = document.select("div.incontent div#list-series-main").mapNotNull { element ->
            val href = fixUrlNull(element.selectFirst("div.cat-img-main a")?.attr("href"))
                ?: return@mapNotNull null
            val title = element.selectFirst("div.cat-title-main a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(
                element.selectFirst("div.cat-img-main img")?.attr("data-src")
                    ?: element.selectFirst("div.cat-img-main img")?.attr("src")
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }.distinctBy { it.url }

        if (classics.isNotEmpty()) {
            home.add(HomePageList("Efsane Diziler", classics))
        }

        // 4) Dikkat çeken diziler
        val featured = document.select("div.incontentyeni div#list-series-main").mapNotNull { element ->
            val href = fixUrlNull(element.selectFirst("div.cat-img-main a")?.attr("href"))
                ?: return@mapNotNull null
            val title = element.selectFirst("div.cat-title-main a")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(
                element.selectFirst("div.cat-img-main img")?.attr("data-src")
                    ?: element.selectFirst("div.cat-img-main img")?.attr("src")
            )

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }.distinctBy { it.url }

        if (featured.isNotEmpty()) {
            home.add(HomePageList("Dikkat Çeken Diziler", featured))
        }

        return newHomePageResponse(home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = selectFirst("div#categorytitle a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")
                ?: selectFirst("img")?.attr("src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document

        val results = document.select("div.incontent div#list-series")
            .mapNotNull { it.toSearchResponse() }

        if (results.isNotEmpty()) return results.distinctBy { it.url }

        // Tema yapısı değişirse daha genel fallback.
        return document.select("a[href]")
            .filter { anchor ->
                anchor.selectFirst("img") != null &&
                    anchor.text().trim().isNotEmpty() &&
                    anchor.attr("href").contains("$mainUrl/")
            }
            .mapNotNull { anchor ->
                val title = anchor.attr("title").trim().ifEmpty { anchor.text().trim() }
                if (title.isEmpty()) return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val poster = fixUrlNull(
                    anchor.selectFirst("img")?.attr("data-src")
                        ?: anchor.selectFirst("img")?.attr("src")
                )

                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.category_image img")?.attr("data-src")
                ?: document.selectFirst("div.category_image img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val year = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")
            ?.nextSibling()
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("Yapım Yılı\\s*:?\\s*(\\d{4})")
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val rating = document.selectFirst("span.dizimeta:contains(IMDB)")
            ?.nextSibling()
            ?.toString()
            ?.trim()
            ?.toRatingInt()
            ?: Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)")
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toRatingInt()

        val tags = document.select("div.genres a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val actors = document.selectFirst("span.dizimeta:contains(Oyuncular)")
            ?.nextSibling()
            ?.toString()
            ?.trim()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { Actor(it) }

        val trailer = document.selectFirst("iframe.trailer-video")?.attr("src")?.let(::fixUrlNull)

        val episodes = document.select("div.bolumust").mapNotNull { element ->
            val rawName = element.selectFirst("div.baslik")?.ownText()?.trim()
                ?: element.selectFirst("div.baslik")?.text()?.trim()
                ?: return@mapNotNull null

            val episodeHref = element.closest("a")?.attr("href")
                ?.let(::fixUrlNull)
                ?: element.selectFirst("a[href]")?.attr("href")?.let(::fixUrlNull)
                ?: return@mapNotNull null

            val season = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE)
                .find(rawName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val episode = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE)
                .find(rawName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            val displayName = element.selectFirst("div.bolumismi")?.text()?.trim()
                ?.replace(Regex("[()]"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: rawName

            newEpisode(episodeHref) {
                name = displayName
                this.season = season
                this.episode = episode
            }
        }

        // Fallback: tema yapısı değişirse bölüm URL'lerini doğrudan href üzerinden bul.
        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes.distinctBy { it.data }
        } else {
            document.select("a[href*='-sezon-'][href*='-bolum-']")
                .mapNotNull { anchor ->
                    val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                    val text = anchor.text().trim()
                    val season = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    val episode = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    newEpisode(href) {
                        name = text.ifEmpty { "${season}. Sezon ${episode ?: 0}. Bölüm" }
                        this.season = season
                        this.episode = episode
                    }
                }
                .distinctBy { it.data }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.rating = rating
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DIZIYOU", "episode url = $data")

        val document = app.get(data).document

        // Player iframe: mevcut sitede #diziyouPlayer kullanılıyor.
        val playerSrc = document.selectFirst("iframe#diziyouPlayer")?.attr("src")?.trim()
            ?: document.select("iframe[src]")
                .mapNotNull { it.attr("src").trim().takeIf(String::isNotEmpty) }
                .firstOrNull { it.contains("diziyou", ignoreCase = true) || it.contains("player", ignoreCase = true) }

        if (playerSrc.isNullOrEmpty()) {
            Log.d("DIZIYOU", "player iframe bulunamadı")
            return false
        }

        val itemId = extractItemId(playerSrc)
        if (itemId.isNullOrEmpty()) {
            Log.d("DIZIYOU", "itemId bulunamadı: $playerSrc")
            return false
        }

        Log.d("DIZIYOU", "itemId = $itemId")

        val optionIds = document.select(".diziyouOption, [id^=turkce], [id^=ingilizce]")
            .map { it.id() }
            .toSet()

        val hasTrSub = optionIds.contains("turkceAltyazili") ||
            document.text().contains("Türkçe Altyazılı", ignoreCase = true)
        val hasEnSub = optionIds.contains("ingilizceAltyazili") ||
            document.text().contains("İngilizce Altyazılı", ignoreCase = true)

        // Dublaj için yalnızca player seçeneğini kullan; sitenin alt bölümündeki
        // genel "Türkçe dublaj" metni yanlış pozitif üretmesin.
        val hasDub = optionIds.contains("turkceDublaj") ||
            document.select("#turkceDublaj, [data-id='turkceDublaj']").isNotEmpty()

        val originalStream = "$storageUrl/episodes/$itemId/play.m3u8"
        val dubStream = "$storageUrl/episodes/${itemId}_tr/play.m3u8"

        // Türkçe altyazı
        if (hasTrSub) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "Turkish",
                    url = "$storageUrl/subtitles/$itemId/tr.vtt"
                )
            )
        }

        // İngilizce altyazı
        if (hasEnSub) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "English",
                    url = "$storageUrl/subtitles/$itemId/en.vtt"
                )
            )
        }

        // Orijinal dil HLS
        if (hasTrSub || hasEnSub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Orijinal Dil 1080p",
                    url = originalStream,
                    type = INFER_TYPE
                ) {
                    referer = "$mainUrl/"
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                    quality = Qualities.FullHD.value
                }
            )
        }

        // Türkçe dublaj HLS
        if (hasDub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Türkçe Dublaj 1080p",
                    url = dubStream,
                    type = INFER_TYPE
                ) {
                    referer = "$mainUrl/"
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                    quality = Qualities.FullHD.value
                }
            )
        }

        // Seçenek sınıfları değiştiyse en azından orijinal akışı dene.
        if (!hasTrSub && !hasEnSub && !hasDub) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Orijinal Dil 1080p",
                    url = originalStream,
                    type = INFER_TYPE
                ) {
                    referer = "$mainUrl/"
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                    quality = Qualities.FullHD.value
                }
            )
        }

        return true
    }

    private fun extractItemId(playerSrc: String): String? {
        val clean = playerSrc.substringBefore('#').substringBefore('?').trimEnd('/')

        // Örn: .../ABC123.html
        Regex("/([^/]+)\\.html$")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Örn: .../ABC123/
        clean.substringAfterLast('/')
            .takeIf { it.isNotBlank() && !it.contains('.') }
            ?.let { return it }

        return null
    }
}
