version = 1

cloudstream {
    description = "SeyirTURK genel katalog, film, dizi ve herkese açık IPTV sağlayıcısı (beta)"
    authors = listOf("neoncs3")
    status = 3
    tvTypes = listOf("Movie", "TvSeries", "Live")
    language = "tr"
    iconUrl = "https://seyirturk.net/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
