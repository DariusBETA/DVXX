package com.phatbn11

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VlxxPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Vlxx())
    }
}
