package com.sokuja

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SokujaPlugin : Plugin() {
    override fun load(context: Context) {
        SokujaProvider.context = context
        registerMainAPI(SokujaProvider())
    }
}
