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
            val id = Regex("""(\d+)[/]*$""").find(data)?.groupValues?.get(1) 
                 ?: return false
            val res = app.post(
            "$mainUrl/ajax.php",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to data,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            val cleanData = try {
                data.replace(" ", "").replace("\n", "").replace("\r", "").trim()
            } catch (e: Exception) {
                Log.e(DEV, "Error cleaning data", e)
                data
            }
            
            Log.d(DEV, "original: $data")
            Log.d(DEV, "cleaned: $cleanData")
            
            val pathSegments = try {
                cleanData.trimEnd('/').split("/").filter { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(DEV, "Error splitting path", e)
                return false
            }
            
            Log.d(DEV, "segments: $pathSegments")
            
            val id = pathSegments.lastOrNull()
            if (id == null) {
                Log.e(DEV, "ID is null")
                return false
            }
            
            Log.d(DEV, "id: $id")
            
            val postUrl = "$mainUrl/ajax.php"
            Log.d(DEV, "Posting to: $postUrl")
            
            val postData = try {
                mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                )
            } catch (e: Exception) {
                Log.e(DEV, "Error creating post data", e)
                return false
            }
            
            Log.d(DEV, "postData: $postData")
            
            val response = try {
                app.post(
                    url = postUrl,
                    data = postData,
                    referer = cleanData
                )
            } catch (e: Exception) {
                Log.e(DEV, "Error posting request", e)
                return false
            }
            
            Log.d(DEV, "Got response: ${response.code}")
            
            val responseText = try {
                response.text
            } catch (e: Exception) {
                Log.e(DEV, "Error getting response text", e)
                return false
            }
            
            Log.d(DEV, "Response length: ${responseText.length}")
            Log.d(DEV, "Response: $responseText")
            
            if (responseText.isEmpty() || responseText.length < 10) {
                Log.e(DEV, "Response too short")
                return false
            }
            
            // Try simple pattern first
            if (responseText.contains("sources")) {
                Log.d(DEV, "Response contains 'sources'")
                
                val sourcesRegex = Regex("sources\\s*:\\s*\\[([^\\]]+)\\]")
                val match = sourcesRegex.find(responseText)
                
                if (match != null) {
                    Log.d(DEV, "Regex matched")
                    val sourcesJson = "[${match.groupValues[1]}]"
                    Log.d(DEV, "JSON: $sourcesJson")
                    
                    try {
                        val sources = tryParseJson<List<Sources>>(sourcesJson)
                        Log.d(DEV, "Parsed ${sources?.size} sources")
                        
                        var added = 0
                        sources?.forEach { source ->
                            source.file?.let { fileUrl ->
                                Log.d(DEV, "Adding: $fileUrl")
                                try {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = name,
                                            url = fileUrl,
                                            type = if (fileUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ).apply {
                                            referer = cleanData
                                            quality = getQualityFromName(source.label)
                                        }
                                    )
                                    added++
                                } catch (e: Exception) {
                                    Log.e(DEV, "Error adding link", e)
                                }
                            }
                        }
                        
                        Log.d(DEV, "Added $added links")
                    if (added > 0) return true 

                } catch (e: Exception) {
                    Log.e(DEV, "Error parsing JSON", e)
                }
            } else {
                Log.d(DEV, "Regex did not match")
            }
        } else {
            Log.d(DEV, "Response does not contain 'sources'")
        }
        
        Log.d(DEV, "Trying fallback URL extraction")
        val urlRegex = Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)")
        val urls = urlRegex.findAll(responseText)
        
        var count = 0
        urls.forEach { urlMatch ->
            val videoUrl = urlMatch.groupValues[1]
            Log.d(DEV, "Fallback found: $videoUrl")
            try {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ).apply {
                        referer = cleanData
                   }
                )
                count++
            } catch (e: Exception) {
                Log.e(DEV, "Error adding fallback link", e)
            }
        }
        
        return count > 0

    } catch (e: Exception) {
        Log.e(DEV, "MAIN EXCEPTION in loadLinks", e)
        false
    }
}

    data class Sources(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
}

