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
            Log.i(DEV, "Data -> $data id -> $id")
            
            val res = app.post(
                "$mainUrl/ajax.php",
                headers = interceptor.getCookieHeaders(data).toMap(),
                data = mapOf(
                    "vlxx_server" to "1",
                    "id" to id,
                    "server" to "1"
                ),
                referer = data
            ).text
            
            Log.i(DEV, "res $res")

            // Try multiple parsing patterns
            var json = getParamFromJS(res, "var opts = {\\r\\n\\t\\t\\t\\t\\t\\tsources:", "}]")
            
            if (json == null) {
                json = getParamFromJS(res, "sources:", "}]")
            }
            
            if (json == null) {
                json = getParamFromJS(res, "sources: ", "}]")
            }
            
            if (json == null) {
                json = getParamFromJS(res, "\"sources\":", "}]")
            }
            
            // Try to find any .m3u8 or .mp4 URLs directly in response
            if (json == null) {
                Log.e(DEV, "All patterns failed, trying direct URL extraction")
                val urlPattern = Regex("(https?://[^\"'\\s]+\\.(m3u8|mp4)[^\"'\\s]*)")
                val matches = urlPattern.findAll(res)
                matches.forEach { match ->
                    val url = match.groupValues[1]
                    Log.i(DEV, "Found direct URL: $url")
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
                }
                return matches.count() > 0
            }
            
            Log.i(DEV, "json $json")
            
            tryParseJson<List<Sources?>>(json)?.forEach { vidlink ->
                vidlink?.file?.let { file ->
                    Log.i(DEV, "Found link: $file")
                    val extractorLinkType = if (file.endsWith("m3u8")) 
                        ExtractorLinkType.M3U8 
                    else 
                        ExtractorLinkType.VIDEO
                    
                    try {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = file,
                                type = extractorLinkType
                            ).apply {
                                this.referer = data
                                this.quality = getQualityFromName(vidlink.label)
                            }
                        )
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            return true
            
        } catch (e: Exception) {
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
            Log.i(DEV, "jsonConfig $jsonConfig")

            return jsonConfig
                .replace("\\r", "")
                .replace("\\t", "")
                .replace("\\\"", "\"")
                .replace("\\\\\\/", "/")
                .replace("\\n", "")
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
