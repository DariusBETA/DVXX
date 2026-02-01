package com.DariusBETA

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse

class Vlxx : MainAPI() {
    private val DEV = "DevDebug"
    private val globaltvType = TvType.NSFW

    override var name = "Vlxx"
    override var mainUrl = "https://vlxx.ms"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val interceptor = CloudflareKiller()

    private suspend fun getPage(url: String, referer: String): NiceResponse {
        return app.get(url, referer = referer, interceptor = interceptor)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getPage(mainUrl, mainUrl).document
        val all = ArrayList<HomePageList>()
        
        val elements = document.select("div#video-list > div.video-item")
            .mapNotNull {
                val firstA = it.selectFirst("a")
                val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst("div.video-name")?.text() ?: it.text()
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }

        if (elements.isNotEmpty()) {
            all.add(HomePageList("Homepage", elements))
        }
        
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/${query}/"
        val document = getPage(searchUrl, mainUrl).document
        
        return document.select("div.video-item")
            .mapNotNull {
                val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst(".video-name")?.text() ?: ""
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.replace(" ", "")
        val doc = getPage(cleanUrl, cleanUrl).document
        val container = doc.selectFirst("div#container")
        val title = container?.selectFirst("h2")?.text() ?: "No Title"
        val descript = container?.selectFirst("div.video-description")?.text()
        
        return newMovieLoadResponse(
            name = title,
            url = cleanUrl,
            dataUrl = cleanUrl,
            type = globaltvType,
        ) {
            this.plot = descript
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val cleanData = data.replace(" ", "").trim()
            Log.d(DEV, "Loading: $cleanData")
            
            // Méthode 1: Extraire depuis la page HTML
            val doc = app.get(cleanData, referer = mainUrl, interceptor = interceptor).document
            val scriptText = doc.html()
            
            Log.d(DEV, "Got page, looking for sources...")
            
            // Chercher les sources dans les scripts
            var foundLinks = 0
            
            // Pattern 1: sources:[{file:"url"}]
            val sourcesPattern = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
            sourcesPattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                Log.d(DEV, "Found source: $url")
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ).apply {
                            this.referer = cleanData
                        }
                    )
                    foundLinks++
                } catch (e: Exception) {
                    Log.e(DEV, "Error adding link", e)
                }
            }
            
            if (foundLinks > 0) {
                Log.d(DEV, "Found $foundLinks links from sources pattern")
                return true
            }
            
            // Pattern 2: Chercher directement les URLs .m3u8
            val m3u8Pattern = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            m3u8Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                Log.d(DEV, "Found m3u8: $url")
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ).apply {
                            this.referer = cleanData
                        }
                    )
                    foundLinks++
                } catch (e: Exception) {
                    Log.e(DEV, "Error adding m3u8", e)
                }
            }
            
            if (foundLinks > 0) {
                Log.d(DEV, "Found $foundLinks total links")
                return true
            }
            
            // Pattern 3: Chercher les URLs .mp4
            val mp4Pattern = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            mp4Pattern.findAll(scriptText).forEach { match ->
                val url = match.groupValues[1]
                Log.d(DEV, "Found mp4: $url")
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ).apply {
                            this.referer = cleanData
                        }
                    )
                    foundLinks++
                } catch (e: Exception) {
                    Log.e(DEV, "Error adding mp4", e)
                }
            }
            
            Log.d(DEV, "Total links found: $foundLinks")
            return foundLinks > 0
            
        } catch (e: Exception) {
            Log.e(DEV, "Exception in loadLinks", e)
            logError(e)
            false
        }
    }

    data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
}
