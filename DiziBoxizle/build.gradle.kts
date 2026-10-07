version = 1

cloudstream {
    description = "DiziBoxizle film ve dizi katalog sağlayıcısı"
    authors = listOf("DiziBoxizle")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://diziboxizle.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
