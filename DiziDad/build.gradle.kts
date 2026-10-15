// CloudStream updates require a higher version number for existing installations.
version = 10

cloudstream {
    description = "DiziDad - yerli, yabancı ve eski diziler"
    authors = listOf("neoncs3")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("TvSeries")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=dizidad.com&sz=%size%"
}
