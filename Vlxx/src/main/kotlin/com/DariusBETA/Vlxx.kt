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
import org.jsoup.nodes.Document

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
            Log.d(DEV, "========================================")
            Log.d(DEV, "loadLinks called with: $cleanData")
            Log.d(DEV, "========================================")
            
            // Charger la page
            val response = app.get(cleanData, referer = mainUrl, interceptor = interceptor)
            val html = response.text
            val doc = response.document
            
            Log.d(DEV, "Response code: ${response.code}")
            Log.d(DEV, "HTML length: ${html.length}")
            
            var foundLinks = 0
            val foundUrls = mutableSetOf<String>()
            
            // Stratégie 1: Chercher dans les balises <script>
            Log.d(DEV, "--- Strategy 1: Script tags ---")
            doc.select("script").forEach { script ->
                val scriptContent = script.html()
                
                // Chercher les patterns courants
                val patterns = listOf(
                    Regex("""['"]file['"]:\s*['"]([^'"]+)['"]"""),
                    Regex("""file\s*:\s*['"]([^'"]+)['"]"""),
                    Regex("""source[s]?\s*:\s*\[\s*\{[^}]*['"]file['"]:\s*['"]([^'"]+)['"]"""),
                    Regex("""(https?://[^\s'"<>]+\.m3u8[^\s'"<>]*)"""),
                    Regex("""(https?://[^\s'"<>]+\.mp4[^\s'"<>]*)""")
                )
                
                patterns.forEach { pattern ->
                    pattern.findAll(scriptContent).forEach { match ->
                        val url = match.groupValues[1]
                        if (url.startsWith("http") && !foundUrls.contains(url)) {
                            foundUrls.add(url)
                            Log.d(DEV, "Found in script: $url")
                        }
                    }
                }
            }
            
            // Stratégie 2: Chercher dans tout le HTML
            Log.d(DEV, "--- Strategy 2: HTML content ---")
            val htmlPatterns = listOf(
                Regex("""(https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]+)?)"""),
                Regex("""(https?://[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]+)?)""")
            )
            
            htmlPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val url = match.groupValues[1].replace("\\", "")
                    if (!foundUrls.contains(url)) {
                        foundUrls.add(url)
                        Log.d(DEV, "Found in HTML: $url")
                    }
                }
            }
            
            // Stratégie 3: Chercher les iframes
            Log.d(DEV, "--- Strategy 3: iframes ---")
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    Log.d(DEV, "Found iframe: $src")
                    // On pourrait charger l'iframe ici si nécessaire
                }
            }
            
            // Stratégie 4: Chercher les balises video/source
            Log.d(DEV, "--- Strategy 4: video tags ---")
            doc.select("video source, video").forEach { videoTag ->
                val src = videoTag.attr("src")
                if (src.isNotEmpty() && !foundUrls.contains(src)) {
                    foundUrls.add(src)
                    Log.d(DEV, "Found video source: $src")
                }
            }
            
            // Ajouter tous les liens trouvés
            Log.d(DEV, "--- Adding ${foundUrls.size} unique URLs ---")
            foundUrls.forEach { url ->
                try {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = when {
                                url.contains(".m3u8") -> ExtractorLinkType.M3U8
                                else -> ExtractorLinkType.VIDEO
                            }
                        ).apply {
                            this.referer = cleanData
                        }
                    )
                    foundLinks++
                    Log.d(DEV, "Successfully added: $url")
                } catch (e: Exception) {
                    Log.e(DEV, "Failed to add: $url", e)
                }
            }
            
            // Si rien trouvé, afficher des infos de debug
            if (foundLinks == 0) {
                Log.d(DEV, "========================================")
                Log.d(DEV, "NO LINKS FOUND - DEBUG INFO")
                Log.d(DEV, "========================================")
                
                // Afficher le début du HTML
                Log.d(DEV, "HTML start (500 chars):")
                Log.d(DEV, html.take(500))
                
                // Chercher des mots-clés
                val keywords = listOf("player", "video", "source", "file", "m3u8", "mp4", "stream")
                keywords.forEach { keyword ->
                    val count = html.split(keyword, ignoreCase = true).size - 1
                    if (count > 0) {
                        Log.d(DEV, "Keyword '$keyword' appears $count times")
                        val index = html.indexOf(keyword, ignoreCase = true)
                        if (index >= 0) {
                            val start = maxOf(0, index - 50)
                            val end = minOf(html.length, index + 150)
                            Log.d(DEV, "Context: ...${html.substring(start, end)}...")
                        }
                    }
                }
            }
            
            Log.d(DEV, "========================================")
            Log.d(DEV, "RESULT: $foundLinks links added")
            Log.d(DEV, "========================================")
            
            foundLinks > 0
            
        } catch (e: Exception) {
            Log.e(DEV, "========================================")
            Log.e(DEV, "EXCEPTION in loadLinks", e)
            Log.e(DEV, "========================================")
            e.printStackTrace()
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
