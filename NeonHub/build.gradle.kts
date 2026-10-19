version = 3
cloudstream {
    description = "NeonHub — neoncs3 sağlayıcılarının tek eklentide birleştirilmiş sürümü"
    authors = listOf("neoncs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime", "Torrent", "NSFW")
    language = "tr"
    requiresResources = false
}

// Copy all site providers into one generated source tree while excluding
// their standalone CloudStream plugin entry points. This avoids unsupported
// source-set exclusion DSL in Android Gradle and preserves the original files.
val mergedProviderSources = layout.buildDirectory.dir("generated/mergedProviders")
val prepareMergedProviderSources by tasks.registering(org.gradle.api.tasks.Copy::class) {
    into(mergedProviderSources)

    from(rootProject.file("AsyaFilmIzle/src/main/kotlin")) {
        into("AsyaFilmIzle")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("AutoDdizimagCom/src/main/kotlin")) {
        into("AutoDdizimagCom")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("AutoHdfilmdiziizleCom/src/main/kotlin")) {
        into("AutoHdfilmdiziizleCom")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("AutoKlubnichkaLive/src/main/kotlin")) {
        into("AutoKlubnichkaLive")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("AutoTvdizilerTv/src/main/kotlin")) {
        into("AutoTvdizilerTv")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("CineStream/src/main/kotlin")) {
        into("CineStream")
        // Keep only shared parsing/host-extractor helpers required by site providers.
        // CineStream itself and every part of its catalogs/settings are not bundled.
        exclude(
            "**/*Plugin.kt",
            "**/CineStream.kt",
            "**/CineStreamProvider.kt",
            "**/CineSimklProvider.kt",
            "**/CineTmdbProvider.kt",
            "**/CineStreamExtractors.kt",
            "**/ProviderRegistry.kt",
            "**/settings/**"
        )
    }
    from(rootProject.file("DDizi/src/main/kotlin")) {
        into("DDizi")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("DiziBoxizle/src/main/kotlin")) {
        into("DiziBoxizle")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("DiziDad/src/main/kotlin")) {
        into("DiziDad")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("DiziPal/src/main/kotlin")) {
        into("DiziPal")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("Dizigecesi/src/main/kotlin")) {
        into("Dizigecesi")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("Dramadizilerim/src/main/kotlin")) {
        into("Dramadizilerim")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("FilmModu/src/main/kotlin")) {
        into("FilmModu")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("JetFilmizle/src/main/kotlin")) {
        into("JetFilmizle")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("SetFilmIzle/src/main/kotlin")) {
        into("SetFilmIzle")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("SinemaCX/src/main/kotlin")) {
        into("SinemaCX")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("TiZam/src/main/kotlin")) {
        into("TiZam")
        exclude("**/*Plugin.kt")
    }
    from(rootProject.file("WebDramaTurkey/src/main/kotlin")) {
        into("WebDramaTurkey")
        exclude("**/*Plugin.kt")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(prepareMergedProviderSources)
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
            java.srcDir(mergedProviderSources)
        }
    }
}
