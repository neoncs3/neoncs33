package com.neoncs3.newtvlive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NewtvLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NewTVLive())
    }
}
