pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

// Bu liste içerisine yazılan klasörler derlemeye dahil edilmez
val disabled = listOf(
    "__Temel",
    "AsyaFilmIzle",
    "AutoDdizimagCom",
    "AutoHdfilmdiziizleCom",
    "AutoKlubnichkaLive",
    "AutoTvdizilerTv",
    "CineStream",
    "DDizi",
    "DiziBoxizle",
    "DiziDad",
    "DiziPal",
    "Dizigecesi",
    "Dramadizilerim",
    "FilmModu",
    "JetFilmizle",
    "SetFilmIzle",
    "SinemaCX",
    "TiZam",
    "WebDramaTurkey"
)

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
