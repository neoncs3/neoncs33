version = 1

cloudstream {
    description = "NeonHub — neoncs3 sağlayıcılarının tek eklentide birleştirilmiş sürümü"
    authors = listOf("neoncs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime", "Torrent", "NSFW")
    language = "tr"
    requiresResources = false
    iconUrl = "https://github.com/neoncs3/neoncs33/raw/master/CineStream/icon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val properties = org.jetbrains.kotlin.konan.properties.Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }

        fun buildConfigString(name: String): String {
            val value = System.getenv(name) ?: properties.getProperty(name, "")
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            return "\"$escaped\""
        }

        buildConfigField("String", "SIMKL_API", buildConfigString("SIMKL_API"))
        buildConfigField("String", "SIMKL_CLIENT_ID", buildConfigString("SIMKL_CLIENT_ID"))
        buildConfigField("String", "TMDB_KEY", buildConfigString("TMDB_KEY"))
        buildConfigField("String", "CASTLE_KEY", buildConfigString("CASTLE_KEY"))
        buildConfigField("String", "MOVIEBLAST_TOKEN", buildConfigString("MOVIEBLAST_TOKEN"))
        buildConfigField("String", "MOVIEBLAST_API", buildConfigString("MOVIEBLAST_API"))
        buildConfigField("String", "MOVIEBLAST_KEY", buildConfigString("MOVIEBLAST_KEY"))
    }

    sourceSets {
        getByName("main") {
            java.srcDir(rootProject.file("AsyaFilmIzle/src/main/kotlin"))
            java.srcDir(rootProject.file("AutoDdizimagCom/src/main/kotlin"))
            java.srcDir(rootProject.file("AutoHdfilmdiziizleCom/src/main/kotlin"))
            java.srcDir(rootProject.file("AutoKlubnichkaLive/src/main/kotlin"))
            java.srcDir(rootProject.file("AutoTvdizilerTv/src/main/kotlin"))
            java.srcDir(rootProject.file("CineStream/src/main/kotlin"))
            java.srcDir(rootProject.file("DDizi/src/main/kotlin"))
            java.srcDir(rootProject.file("DiziBoxizle/src/main/kotlin"))
            java.srcDir(rootProject.file("DiziDad/src/main/kotlin"))
            java.srcDir(rootProject.file("DiziPal/src/main/kotlin"))
            java.srcDir(rootProject.file("Dizigecesi/src/main/kotlin"))
            java.srcDir(rootProject.file("Dramadizilerim/src/main/kotlin"))
            java.srcDir(rootProject.file("FilmModu/src/main/kotlin"))
            java.srcDir(rootProject.file("JetFilmizle/src/main/kotlin"))
            java.srcDir(rootProject.file("SetFilmIzle/src/main/kotlin"))
            java.srcDir(rootProject.file("SinemaCX/src/main/kotlin"))
            java.srcDir(rootProject.file("TiZam/src/main/kotlin"))
            java.srcDir(rootProject.file("WebDramaTurkey/src/main/kotlin"))

            // All providers are registered by NeonHubEntry.kt. Exclude the
            // individual plugin entry points to prevent duplicate loaders.
            java { exclude("**/*Plugin.kt", "**/CineStream.kt") }
        }
    }
}
