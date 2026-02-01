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
        val resp = app.get(url, referer = referer, interceptor = interceptor)
        return resp
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
                val name = it.selectFirst("div.video-name")?.text() 
                    ?: it.selectFirst("a")?.attr("title") 
                    ?: it.text()
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }

        if (elements.isEmpty()) {
            val altElements = document.select("div.video-item")
                .mapNotNull {
                    val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                    val img = it.selectFirst("img")?.attr("data-original") 
                        ?: it.selectFirst("img")?.attr("src")
                    val name = it.selectFirst(".video-name")?.text() 
                        ?: it.selectFirst("a")?.attr("title") 
                        ?: "Video"
                    newMovieSearchResponse(
                        name = name,
                        url = link,
                        type = globaltvType,
                    ) {
                        this.posterUrl = img
                    }
                }.distinctBy { it.url }
            if (altElements.isNotEmpty()) {
                all.add(HomePageList("Homepage", altElements))
            }
        } else {
            all.add(HomePageList("Homepage", elements))
        }
        
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/${query}/"
        val document = getPage(searchUrl, mainUrl).document
        
        return document.select(".video-list .video-item, div.video-item")
            .mapNotNull {
                val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original") 
                    ?: it.selectFirst("img")?.attr("src")
                val name = it.selectFirst(".video-name")?.text() 
                    ?: it.selectFirst("a")?.attr("title") 
                    ?: ""
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                    this.posterHeaders = interceptor.getCookieHeaders(searchUrl).toMap()
                }
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getPage(url, url).document

        val container = doc.selectFirst("div#container")
        val title = container?.selectFirst("h2")?.text() 
            ?: container?.selectFirst("h1")?.text() 
            ?: "No Title"
        val descript = container?.selectFirst("div.video-description")?.text()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("video")?.attr("poster")
        
        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            type = globaltvType,
        ) {
            this.posterUrl = poster
            this.plot = descript
            this.posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            Log.d(DEV, "=== LOADING LINKS ===")
            Log.d(DEV, "Full URL: $data")
            
            // Try multiple ways to extract ID
            val urlParts = data.trimEnd('/').split("/")
            Log.d(DEV, "URL parts: $urlParts")
            
            // Method 1: Get last part (slug)
            val slug = urlParts.lastOrNull { it.isNotEmpty() } ?: ""
            Log.d(DEV, "Slug: $slug")
            
            // Method 2: Get second to last (if last is empty from trailing slash)
            val id2 = if (urlParts.size >= 2) urlParts[urlParts.size - 2] else slug
            Log.d(DEV, "ID attempt 2: $id2")
            
            // Try both IDs
            val idsToTry = listOf(slug, id2).distinct().filter { it.isNotEmpty() }
            
            for (id in idsToTry) {
                Log.d(DEV, "Trying ID: $id")
                
                val cookies = interceptor.getCookieHeaders(data).toMap()
                val headers = cookies.toMutableMap()
                headers["X-Requested-With"] = "XMLHttpRequest"
                
                val postData = mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                )
                Log.d(DEV, "POST data: $postData")
                
                val res = app.post(
                    "$mainUrl/ajax.php",
                    headers = headers,
                    data = postData,
                    referer = data
                ).text
                
                Log.d(DEV, "Response length: ${res.length}")
                Log.d(DEV, "Response: $res")
                
                if (res.isBlank() || res.length < 10) {
                    Log.w(DEV, "Response too short, trying next ID")
                    continue
                }
                
                // Try to find video sources
                var foundLinks = false
                
                // Pattern 1: Standard JSON sources
                val patterns = listOf(
                    "sources:[" to "]",
                    "sources: [" to "]",
                    "\"sources\":[" to "]",
                    "'sources':[" to "]",
                    "sources:" to "}]"
                )
                
                for ((key, end) in patterns) {
                    val json = getParamFromJS(res, key, end)
                    if (json != null && json.length > 5) {
                        Log.d(DEV, "Pattern '$key' matched. JSON: $json")
                        
                        val sources = tryParseJson<List<Sources?>>(json)
                        Log.d(DEV, "Parsed sources count: ${sources?.size}")
                        
                        sources?.forEach { vidlink ->
                            val file = vidlink?.file
                            if (!file.isNullOrBlank()) {
                                Log.d(DEV, "Adding link: $file (${vidlink.label})")
                                callback.invoke(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} ${vidlink.label ?: ""}",
                                        url = file,
                                        type = if (file.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ).apply {
                                        this.referer = data
                                        this.quality = getQualityFromName(vidlink.label)
                                    }
                                )
                                foundLinks = true
                            }
                        }
                        
                        if (foundLinks) {
                            Log.d(DEV, "Found links with JSON parsing")
                            return true
                        }
                    }
                }
                
                // Pattern 2: Direct URL in response
                val urlRegex = Regex("(https?://[^\"'\\s]+\\.(?:m3u8|mp4)[^\"'\\s]*)")
                val matches = urlRegex.findAll(res)
                
                matches.forEach { match ->
                    val url = match.groupValues[1]
                    Log.d(DEV, "Found direct URL: $url")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = url,
                            type = if (url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ).apply {
                            this.referer = data
                        }
                    )
                    foundLinks = true
                }
                
                if (foundLinks) {
                    Log.d(DEV, "Found links with direct URL extraction")
                    return true
                }
            }
            
            Log.e(DEV, "No links found with any method or ID")
            return false
            
        } catch (e: Exception) {
            Log.e(DEV, "Exception in loadLinks", e)
            logError(e)
            return false
        }
    }

    private fun getParamFromJS(str: String, key: String, keyEnd: String): String? {
        try {
            val firstIndex = str.indexOf(key)
            if (firstIndex == -1) return null
            
            val startIndex = firstIndex + key.length
            val temp = str.substring(startIndex)
            val lastIndex = temp.indexOf(keyEnd)
            if (lastIndex == -1) return null
            
            val jsonConfig = temp.substring(0, lastIndex + keyEnd.length)

            return jsonConfig
                .replace("\\r", "")
                .replace("\\t", "")
                .replace("\\\"", "\"")
                .replace("\\\\\\/", "/")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim()
            
        } catch (e: Exception) {
            logError(e)
        }
        return null
    }

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )
}
