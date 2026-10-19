package com.neoncs3.settings

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.neoncs3.ProviderRegistry

object Settings {

    // ── Global keys ──────────────────────────────────────────
    const val CONCURRENCY_KEY          = "ScrapeConcurrency"
    const val DOWNLOAD_ENABLE          = "DownloadEnable"
    const val PROVIDER_CINESTREAM      = "ProviderCineStream"
    const val PROVIDER_SIMKL           = "ProviderSimkl"
    const val PROVIDER_TMDB            = "ProviderTmdb"
    const val SHOWBOX_TOKEN_KEY        = "showbox_ui_token"
    const val WYZIE_SUBS_KEY           = "wyzie_subs_api_key"
    const val GRAMCINEMA_TOKEN_KEY     = "gramcinema_bearer_token"
    const val STREMIO_ADDONS_KEY       = "stremio_addons"
    const val NEW_PROVIDER_DEFAULT_ON  = "new_provider_default_on"
    const val CF_BYPASS_ENABLED        = "cloudflare_webview_bypass_enabled"
 
    // ── NeonHub: individual website-provider switches ─────────
    const val HUB_SOURCE_ASYAFILM       = "neonhub_source_asyafilm"
    const val HUB_SOURCE_DIZIMAG         = "neonhub_source_dizimag"
    const val HUB_SOURCE_HDFILMDIZI      = "neonhub_source_hdfilmdizi"
    const val HUB_SOURCE_CANLI           = "neonhub_source_canli"
    const val HUB_SOURCE_TVDIZILER       = "neonhub_source_tvdiziler"
    const val HUB_SOURCE_DDIZI           = "neonhub_source_ddizi"
    const val HUB_SOURCE_DIZIBOXIZLE     = "neonhub_source_diziboxizle"
    const val HUB_SOURCE_DIZIDAD         = "neonhub_source_dizidad"
    const val HUB_SOURCE_DIZIPAL         = "neonhub_source_dizipal"
    const val HUB_SOURCE_DIZIGECESI      = "neonhub_source_dizigecesi"
    const val HUB_SOURCE_DRAMADIZILERIM  = "neonhub_source_dramadizilerim"
    const val HUB_SOURCE_FILMMODU        = "neonhub_source_filmmodu"
    const val HUB_SOURCE_JETFILMIZLE     = "neonhub_source_jetfilmizle"
    const val HUB_SOURCE_SETFILMIZLE     = "neonhub_source_setfilmizle"
    const val HUB_SOURCE_SINEMACX        = "neonhub_source_sinemacx"
    const val HUB_SOURCE_TIZAM           = "neonhub_source_tizam"
    const val HUB_SOURCE_WEBDRAMATURKEY  = "neonhub_source_webdramaturkey"

    data class NeonHubSource(
        val key: String,
        val title: String,
        val description: String
    )

    val NEONHUB_SOURCES: List<NeonHubSource> = listOf(
        NeonHubSource(HUB_SOURCE_ASYAFILM, "AsyaFilmIzle", "Asya filmleri ve dizileri"),
        NeonHubSource(HUB_SOURCE_DIZIMAG, "DiziMag", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_HDFILMDIZI, "HDFilmDiziİzle", "HD film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_CANLI, "Canlı Sağlayıcı", "Canlı içerik sağlayıcısı"),
        NeonHubSource(HUB_SOURCE_TVDIZILER, "TVDiziler", "Yerli ve yabancı diziler"),
        NeonHubSource(HUB_SOURCE_DDIZI, "DDizi", "Dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_DIZIBOXIZLE, "DiziBoxIzle", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_DIZIDAD, "DiziDad", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_DIZIPAL, "DiziPal", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_DIZIGECESI, "DiziGecesi", "Dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_DRAMADIZILERIM, "DramaDizilerim", "Dikey mini diziler"),
        NeonHubSource(HUB_SOURCE_FILMMODU, "FilmModu", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_JETFILMIZLE, "JetFilmIzle", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_SETFILMIZLE, "SetFilmIzle", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_SINEMACX, "SinemaCX", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_TIZAM, "TiZam", "Film ve dizi kataloğu"),
        NeonHubSource(HUB_SOURCE_WEBDRAMATURKEY, "WebDramaTurkey", "Asya dizileri"),
        NeonHubSource(PROVIDER_CINESTREAM, "CineStream", "Cinemeta içerik kataloğu"),
        NeonHubSource(PROVIDER_SIMKL, "CineSimkl", "Simkl içerik kataloğu"),
        NeonHubSource(PROVIDER_TMDB, "CineTMDB", "TMDB içerik kataloğu")
    )

    fun neonHubSourceEnabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    private const val SEEN_PROVIDERS_KEY = "seen_providers"
    private const val PROVIDER_ORDER_KEY = "provider_order"

    // ── Configuration Getters ────────────────────────────────
    fun getConcurrency(): Int = getKey<Int>(CONCURRENCY_KEY) ?: 10

    val allowDownloadLinks: Boolean
        get() = getKey<Boolean>(DOWNLOAD_ENABLE) ?: false

    val activeProviderOrder: List<String>
        get() = getOrder().filter { enabled(it) }

    fun cloudflareBypassEnabled(): Boolean = getKey<Boolean>(CF_BYPASS_ENABLED) ?: false

    private fun normalizeBypassDomain(domain: String): String {
        return domain.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .lowercase()
            .trim()
    }

    data class BypassDomain(val domain: String, val displayName: String)

    private val CLOUDFLARE_BYPASS_SITES = listOf(
        BypassDomain("anidao.to/search?q=hi", "AniDao"),
    )

    fun getCloudflareBypassDomains(): List<BypassDomain> = CLOUDFLARE_BYPASS_SITES

    fun hasCloudflareBypassForUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase()?.trim().orEmpty()
        if (host.isBlank()) return false
        return CLOUDFLARE_BYPASS_SITES.any { site ->
            val normalizedDomain = normalizeBypassDomain(site.domain)
            normalizedDomain.isNotBlank() && (host == normalizedDomain || host.endsWith(".$normalizedDomain"))
        }
    }

    // ── Per-domain cookie persistence (Cloudflare bypass) ────
    private const val CF_COOKIE_KEY_PREFIX = "cf_cookie_"

    private fun cookieKeyForDomain(urlOrDomain: String): String {
        val domain = if (urlOrDomain.contains("://")) {
            Uri.parse(urlOrDomain).host?.lowercase()?.removePrefix("www.")
                ?: normalizeBypassDomain(urlOrDomain)
        } else {
            normalizeBypassDomain(urlOrDomain)
        }
        return "$CF_COOKIE_KEY_PREFIX$domain"
    }

    fun saveCookieForDomain(urlOrDomain: String, cookies: String) {
        setKey(cookieKeyForDomain(urlOrDomain), cookies.trim())
    }

    fun getCookieForDomain(urlOrDomain: String): String? =
        getKey<String>(cookieKeyForDomain(urlOrDomain))?.takeIf { it.isNotBlank() }

    fun hasSavedCookieForDomain(urlOrDomain: String): Boolean =
        !getCookieForDomain(urlOrDomain).isNullOrBlank()

    fun removeCookieForDomain(urlOrDomain: String) {
        setKey(cookieKeyForDomain(urlOrDomain), null as String?)
    }

    // ── Dynamic Provider Maps ────────────────────────────────
    // We dynamically pull these from our single source of truth (ProviderRegistry)!
    val TORRENT_KEYS: Set<String> get() = ProviderRegistry.torrentKeys
    val PROVIDER_NAMES: Map<String, String> get() = ProviderRegistry.namesMap
    val DEFAULT_ORDER: List<String> get() = ProviderRegistry.keys

    // ── Provider ordering ────────────────────────────────────

    fun newProviderDefaultOn(): Boolean = getKey<Boolean>(NEW_PROVIDER_DEFAULT_ON) ?: true

    fun getSeenProviders(): Set<String> =
        getKey<String>(SEEN_PROVIDERS_KEY)
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    fun initSeenProviders() {
        val stremioKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown    = (DEFAULT_ORDER + stremioKeys).toSet()
        val currentSeen = getSeenProviders()

        if (currentSeen.isEmpty()) {
            setKey(SEEN_PROVIDERS_KEY, allKnown.joinToString(","))
            return
        }

        val defaultState = newProviderDefaultOn()

        allKnown.forEach { key ->
            if (key !in currentSeen
                && !key.startsWith("stremio_")
                && key !in TORRENT_KEYS
                && getKey<Boolean>(key) == null
            ) {
                setKey(key, defaultState)
            }
        }

        val removedKeys = currentSeen - allKnown
        removedKeys.forEach { setKey(it, null as Boolean?) }
        val prunedSeen  = (currentSeen - removedKeys) + allKnown
        setKey(SEEN_PROVIDERS_KEY, prunedSeen.joinToString(","))
    }

    fun markProvidersSeen(keys: Collection<String>) {
        val currentlySeen = getSeenProviders()
        val defaultState  = newProviderDefaultOn()
        keys.forEach { key ->
            if (key !in currentlySeen
                && !key.startsWith("stremio_")
                && key !in TORRENT_KEYS
                && getKey<Boolean>(key) == null
            ) {
                setKey(key, defaultState)
            }
        }
        val merged = currentlySeen + keys
        setKey(SEEN_PROVIDERS_KEY, merged.joinToString(","))
    }

    fun enabled(key: String): Boolean {
        val explicit = getKey<Boolean>(key)
        if (explicit != null) return explicit
        if (key.startsWith("stremio_")) return true
        if (key in TORRENT_KEYS) return false
        if (key !in getSeenProviders()) return newProviderDefaultOn()
        return true
    }

    fun getOrder(): List<String> {
        val stremioKeys = getStremioAddons().map { stremioAddonKey(it.name) }
        val allKnown    = DEFAULT_ORDER + stremioKeys
        val saved       = getKey<String>(PROVIDER_ORDER_KEY)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: return allKnown

        val validSaved = saved.filter { it in allKnown }
        return validSaved + (allKnown - validSaved.toSet())
    }

    fun saveOrder(order: List<String>) =
        setKey(PROVIDER_ORDER_KEY, order.joinToString(","))

    // ── Stremio addon helpers ────────────────────────────────

    enum class AddonType { HTTPS, TORRENT, DEBRID, SUBTITLE }

    data class StremioAddon(val name: String, val url: String, val type: AddonType)

    fun stremioAddonKey(name: String): String =
        "stremio_${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"

    fun providerDisplayName(key: String): String {
        PROVIDER_NAMES[key]?.let { return it }
        if (key.startsWith("stremio_")) {
            val addon = getStremioAddons().firstOrNull { stremioAddonKey(it.name) == key }
            if (addon != null) {
                val icon = when (addon.type) {
                    AddonType.TORRENT  -> "🧲"
                    AddonType.DEBRID   -> "☁️"
                    AddonType.SUBTITLE -> "📝"
                    AddonType.HTTPS    -> "🔌"
                }
                return "$icon ${addon.name}"
            }
            return "🔌 " + key.removePrefix("stremio_").replace("_", " ")
                .replaceFirstChar { it.uppercaseChar() }
        }
        return key
    }

    fun isStremioTorrent(key: String): Boolean {
        if (!key.startsWith("stremio_")) return false
        return getStremioAddons().firstOrNull { stremioAddonKey(it.name) == key }
            ?.type == AddonType.TORRENT
    }

    fun getStremioAddons(): MutableList<StremioAddon> {
        val raw = getKey<String>(STREMIO_ADDONS_KEY) ?: return mutableListOf()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val type = runCatching { AddonType.valueOf(parts[2]) }.getOrDefault(AddonType.HTTPS)
            StremioAddon(name = parts[0], url = parts[1], type = type)
        }.toMutableList()
    }

    fun saveStremioAddons(addons: List<StremioAddon>) {
        if (addons.isEmpty()) setKey(STREMIO_ADDONS_KEY, null as String?)
        else setKey(STREMIO_ADDONS_KEY, addons.joinToString("\n") { "${it.name}|${it.url}|${it.type}" })
        val validKeys = addons.map { stremioAddonKey(it.name) }.toSet()
        val current   = getKey<String>(PROVIDER_ORDER_KEY)
            ?.split(",")?.filter { it.isNotBlank() } ?: return
        val pruned = current.filter { !it.startsWith("stremio_") || it in validKeys }
        if (pruned.size != current.size) saveOrder(pruned)
    }

    // ── ShowBox token helpers ────────────────────────────────
    fun saveShowboxToken(token: String) = setKey(SHOWBOX_TOKEN_KEY, token.trim())
    fun getShowboxToken(): String?       = getKey<String>(SHOWBOX_TOKEN_KEY)?.takeIf { it.isNotBlank() }
    fun clearShowboxToken()              = setKey(SHOWBOX_TOKEN_KEY, null)

    // ── Wyzie Subs API key helpers ───────────────────────────
    fun saveWyzieSubsKey(key: String) = setKey(WYZIE_SUBS_KEY, key.trim())
    fun getWyzieSubsKey(): String?     = getKey<String>(WYZIE_SUBS_KEY)?.takeIf { it.isNotBlank() }
    fun clearWyzieSubsKey()            = setKey(WYZIE_SUBS_KEY, null)

    // ── GramCinema bearer token helpers ──────────────────────
    fun saveGramCinemaToken(token: String) = setKey(GRAMCINEMA_TOKEN_KEY, token.trim())
    fun getGramCinemaToken(): String?       = getKey<String>(GRAMCINEMA_TOKEN_KEY)?.takeIf { it.isNotBlank() }
    fun clearGramCinemaToken()              = setKey(GRAMCINEMA_TOKEN_KEY, null)

    // ── Entry point ──────────────────────────────────────────
    fun showSettingsDialog(context: Context, onSave: () -> Unit) =
        SettingsDialog.show(context, onSave)
}
