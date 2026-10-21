version = 6

cloudstream {
    description = "GölgeTV film ve dizi kataloğu; açıkça yayınlanan, DRM'siz medya bağlantıları"
    authors = listOf("neoncs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://golgetv.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
