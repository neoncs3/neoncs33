version = 2

    // Eklenti sürümü: her değişiklikte artır, yoksa CloudStream güncelleme görmez.

cloudstream {
    description = "Tizam"
    language = "ru"
    authors = listOf("neoncs3")
    status = 1
    tvTypes = listOf("NSFW")
}

android {
    buildFeatures {
        buildConfig = true
    }
}
