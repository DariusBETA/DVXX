package com.jacekun

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
        val doc = getPage(url, url).document
        val container = doc.selectFirst("div#container")
        val title = container?.selectFirst("h2")?.text() ?: "No Title"
        val descript = container?.selectFirst("div.video-description")?.text()
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
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
        Log.d(DEV, "START loadLinks")
        Log.d(DEV, "data = $data")
        
        return try {
            // Extract ID from URL: https://vlxx.ms/video/chong-lanh.../3060/
            val pathSegments = data.split("/").filter { it.isNotEmpty() }
            Log.d(DEV, "pathSegments = $pathSegments")
            
            val id = pathSegments.getOrNull(pathSegments.size - 1) ?: run {
                Log.e(DEV, "Cannot extract ID from URL")
                return false
            }
            Log.d(DEV, "id = $id")
            
            val postUrl = "$mainUrl/ajax.php"
            Log.d(DEV, "postUrl = $postUrl")
            
            val response = app.post(
                url = postUrl,
                data = mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                ),
                referer = data
            )
            
            Log.d(DEV, "response.code = ${response.code}")
            val responseText = response.text
            Log.d(DEV, "response.length = ${responseText.length}")
            Log.d(DEV, "response = $responseText")
            
            if (responseText.isEmpty()) {
                Log.e(DEV, "Empty response")
                return false
            }
            
            // Try to parse sources
            val sourcesRegex = Regex("sources\\s*:\\s*\\[([^\\]]+)\\]")
            val match = sourcesRegex.find(responseText)
            
            if (match != null) {
                val sourcesJson = "[${match.groupValues[1]}]"
                Log.d(DEV, "sourcesJson = $sourcesJson")
                
                val sources = tryParseJson<List<Sources>>(sourcesJson)
                Log.d(DEV, "sources parsed = ${sources?.size}")
                
                sources?.forEach { source ->
                    source.file?.let { fileUrl ->
                        Log.d(DEV, "Adding link: $fileUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = fileUrl,
                                type = if (fileUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ).apply {
                                referer = data
                                quality = getQualityFromName(source.label)
                            }
                        )
                    }
                }
                
                return sources?.isNotEmpty() == true
            } else {
                Log.e(DEV, "sources not found in response")
                
                // Fallback: try to find any video URL
                val urlRegex = Regex("(https?://[^\\s\"']+\\.m3u8[^\\s\"']*)")
                val urls = urlRegex.findAll(responseText)
                
                var found = false
                urls.forEach { urlMatch ->
                    val videoUrl = urlMatch.groupValues[1]
                    Log.d(DEV, "Found fallback URL: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = ExtractorLinkType.M3U8
                        ).apply {
                            referer = data
                        }
                    )
                    found = true
                }
                
                return found
            }
            
        } catch (e: Exception) {
            Log.e(DEV, "Exception in loadLinks: ${e.message}", e)
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
