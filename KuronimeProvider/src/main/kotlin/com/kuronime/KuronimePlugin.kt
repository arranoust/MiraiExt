package com.kuronime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuronimePlugin : Plugin() {
    override fun load(context: Context) {
        KuronimeProvider.context = context
        registerMainAPI(KuronimeProvider())
    }
}
