package com.neoncs3

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GolgeTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GolgeTVProvider())
    }
}
