version = 1

cloudstream {
    description = "FilmHane film ve dizi sağlayıcısı"
    authors = listOf("neoncs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://www.filmhane.shop/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
