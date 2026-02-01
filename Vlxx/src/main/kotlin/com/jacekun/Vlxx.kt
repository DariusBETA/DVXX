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
        var count = 0
        var resp = app.get(url, referer = referer, interceptor = interceptor)
        Log.i(DEV, "Page Response => ${resp}")
        return resp
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val apiName = this.name
        val document = getPage(mainUrl, mainUrl).document
        val all = ArrayList<HomePageList>()
        val title = "Homepage"
        Log.i(DEV, "Fetching videos..")
        val elements = document.select("div#video-list > div.video-item")
            .mapNotNull {
                val firstA = it.selectFirst("a")
                val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
                val img = it.selectFirst("img")?.attr("data-original")
                val name = it.selectFirst("div.video-name")?.text() ?: it.text()
                Log.i(DEV, "Result => $link")
                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = img
                }
            }.distinctBy { it.url }

        if (elements.isNotEmpty()) {
            all.add(
                HomePageList(
                    title, elements
                )
            )
        }
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getPage("$mainUrl/search/${query}/", mainUrl).document
            .select("#container .box .video-list")
            .mapNotNull {
                val link = fixUrlNull(it.select("a").attr("href")) ?: return@mapNotNull null
                val imgArticle = it.select(".video-image").attr("src")
                val name = it.selectFirst(".video-name")?.text() ?: ""
                val year = null

                newMovieSearchResponse(
                    name = name,
                    url = link,
                    type = globaltvType,
                ) {
                    this.posterUrl = imgArticle
                    this.year = year
                    this.posterHeaders = interceptor.getCookieHeaders(url).toMap()
                }
            }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val apiName = this.name
        val doc = getPage(url, url).document

        val container = doc.selectFirst("div#container")
        val title = container?.selectFirst("h2")?.text() ?: "No Title"
        val descript = container?.selectFirst("div.video-description")?.text()
        val year = null
        val poster = null
        return newMovieLoadResponse(
            name = title,
            url = url,
            dataUrl = url,
            type = globaltvType,
        ) {
            this.apiName = apiName
            this.posterUrl = poster
            this.year = year
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
            val pathSplits = data.split("/")
            val id = pathSplits[pathSplits.size - 2]
            Log.i(DEV, "Data -> $data")
            Log.i(DEV, "ID -> $id")
            
            val cookies = interceptor.getCookieHeaders(data).toMap()
            Log.i(DEV, "Cookies: $cookies")
            
            val res = app.post(
                "$mainUrl/ajax.php",
                headers = cookies,
                data = mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                ),
                referer = data
            ).text
            
            Log.i(DEV, "Response from ajax: $res")
            
            if (res.isBlank()) {
                Log.e(DEV, "Empty response!")
                return false
            }
            
            if (!res.contains("sources")) {
                Log.e(DEV, "Response does not contain sources!")
                Log.i(DEV, "Full response: $res")
                return false
            }

            val json = getParamFromJS(res, "var opts = {\\r\\n\\t\\t\\t\\t\\t\\tsources:", "}]")
            
            if (json == null) {
                Log.e(DEV, "Unable to parse JSON from response!")
                return false
            }
            
            Log.i(DEV, "JSON parsed: $json")
            parseAndAddLinks(json, data, callback)
            return true
            
        } catch (e: Exception) {
            Log.e(DEV, "Error in loadLinks: ${e.message}")
            e.printStackTrace()
            logError(e)
            return false
        }
    }

    private fun parseAndAddLinks(
        json: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val sourcesList = tryParseJson<List<Sources?>>(json)
            
            if (sourcesList == null || sourcesList.isEmpty()) {
                Log.e(DEV, "Failed to parse sources list or list is empty")
                return
            }
            
            sourcesList.forEach { vidlink ->
                if (vidlink == null) {
                    Log.w(DEV, "Source item is null, skipping")
                    return@forEach
                }
                
                val file = vidlink.file
                if (file == null || file.isBlank()) {
                    Log.w(DEV, "File URL is null or empty, skipping")
                    return@forEach
                }
                
                Log.i(DEV, "Found link: $file - Quality: ${vidlink.label}")
                
                val extractorLinkType = if (file.endsWith("m3u8")) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                
                try {
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = file,
                            referer = referer,
                            quality = getQualityFromName(vidlink.label),
                            type = extractorLinkType
                        )
                    )
                    Log.i(DEV, "Successfully added link: $file")
                } catch (e: Exception) {
                    Log.e(DEV, "Error adding link: ${e.message}")
                    e.printStackTrace()
                    logError(e)
                }
            }
        } catch (e: Exception) {
            Log.e(DEV, "Error in parseAndAddLinks: ${e.message}")
            e.printStackTrace()
            logError(e)
        }
    }

    private fun getParamFromJS(str: String, key: String, keyEnd: String): String? {
        try {
            val firstIndex = str.indexOf(key)
            if (firstIndex == -1) {
                Log.w(DEV, "Key not found: $key")
                return null
            }
            
            val startIndex = firstIndex + key.length
            val temp = str.substring(startIndex)
            val lastIndex = temp.indexOf(keyEnd)
            
            if (lastIndex == -1) {
                Log.w(DEV, "KeyEnd not found: $keyEnd")
                return null
            }
            
            val jsonConfig = temp.substring(0, lastIndex + keyEnd.length)
            Log.i(DEV, "jsonConfig raw: $jsonConfig")

            val cleaned = jsonConfig
                .replace("\\r", "")
                .replace("\\t", "")
                .replace("\\\"", "\"")
                .replace("\\\\\\/", "/")
                .replace("\\n", "")
                .trim()

            Log.i(DEV, "jsonConfig cleaned: $cleaned")
            return cleaned
            
        } catch (e: Exception) {
            Log.e(DEV, "Error in getParamFromJS: ${e.message}")
            e.printStackTrace()
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
