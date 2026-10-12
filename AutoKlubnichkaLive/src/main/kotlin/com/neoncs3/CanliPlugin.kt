package com.example.canli

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CanliPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CanliProvider())
    }
}
