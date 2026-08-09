package com.otakudesu

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element

class OtakudesuProvider : MainAPI() {
    override var mainUrl            = "https://otakudesu.blog"
    override var name               = "Otakudesu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null

        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s?(\d+)""", RegexOption.IGNORE_CASE)
        private val yearRegex       = Regex("""\b(19|20)\d{2}\b""")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true)                              -> TvType.AnimeMovie
            else                                                   -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
            else                                                         -> ShowStatus.Ongoing
        }
    }

    // ================== Homepage ==================

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/"  to "Ongoing Anime",
        "$mainUrl/complete-anime/page/" to "Completed Anime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val items = app.get(request.data + page).document
            .select("div.venz > ul > li")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title     = selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href      = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("div.thumbz > img")?.attr("src")
        val epNum     = selectFirst("div.epz")?.ownText()
            ?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================

    // fix #5: null-safe search results
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type=anime").document
            .select("ul.chivsrc > li")
            .mapNotNull {
                val title     = it.selectFirst("h2 > a")?.ownText()?.trim() ?: return@mapNotNull null
                val href      = it.selectFirst("h2 > a")?.attr("href") ?: return@mapNotNull null
                val posterUrl = it.selectFirst("img")?.attr("src")
                newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
            }
    }

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title     = doc.selectFirst("div.infozingle > p:nth-child(1) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: return null
        val poster    = doc.selectFirst("div.fotoanime > img")?.attr("src")
        val synopsis  = doc.select("div.sinopc > p").text()
        val tags      = doc.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type      = getType(
            doc.selectFirst("div.infozingle > p:nth-child(5) > span")
                ?.ownText()?.replace(":", "")?.trim() ?: "tv"
        )
        val statusStr = doc.selectFirst("div.infozingle > p:nth-child(6) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: ""
        val year      = yearRegex.find(
            doc.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val trailer   = doc.selectFirst("div.trailer-anime iframe, #trailer iframe")?.attr("src")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val meta    = fetchAniZipMeta(tracker?.malId)
        val logoUrl = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")

        val backgroundPoster = meta?.data?.images?.find { it.coverType == "Fanart" }?.url
            ?: tracker?.cover

        val episodeListEl = doc.select("div.episodelist").getOrNull(1)
        val episodes = episodeListEl?.select("ul > li")?.amap { el ->
            val epName = el.selectFirst("a")?.text() ?: return@amap null
            var epNum  = episodeNumRegex.find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val link   = fixUrl(el.selectFirst("a")?.attr("href") ?: return@amap null)

            if (type == TvType.AnimeMovie && epNum == null) epNum = 1
            val aniEp = epNum?.let { meta?.data?.episodes?.get(it.toString()) }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: title
                } else {
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: epName
                }
                this.episode     = epNum
                this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() }
                    ?: "Synopsis not yet available."
                this.runTime     = aniEp?.runtime
                this.addDate(aniEp?.airDateUtc)
            }
        }?.filterNotNull()?.reversed() ?: emptyList()

        val recommendations = doc.select("div.isi-recommend-anime-series > div.isi-konten").mapNotNull {
            val recName   = it.selectFirst("span.judul-anime > a")?.text() ?: return@mapNotNull null
            val recHref   = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val recPoster = it.selectFirst("a > img")?.attr("src")
            newAnimeSearchResponse(recName, recHref, TvType.Anime) { this.posterUrl = recPoster }
        }

        val finalPlot = meta?.data?.description?.replace(Regex("<.*?>"), "")
            ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: synopsis

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName             = meta?.data?.titles?.get("en") ?: title
            this.japName             = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            runCatching { this.logoUrl = logoUrl }
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.plot                = finalPlot
            this.tags                = tags
            this.recommendations     = recommendations
            addTrailer(trailer)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(meta?.kitsuId) }
        }
    }

    // ================== Load Links ==================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc        = app.get(data).document
        val scriptData = doc.select("script")
            .firstOrNull { it.data().contains("admin-ajax") && it.data().contains("action") }
            ?.data() ?: ""

        val nonceAction = Regex("""data\s*:\s*\{action\s*:\s*["']([^"']+)["']""").find(scriptData)
            ?.groupValues?.get(1)
            ?: Regex("""action\s*:\s*["']([a-f0-9]{32})["']""").find(scriptData)?.groupValues?.get(1)
        val embedAction = Regex("""nonce[^,]*,\s*action\s*:\s*["']([a-f0-9]{32})["']""")
            .find(scriptData)?.groupValues?.get(1)

        // Mirror streams
        if (nonceAction != null && embedAction != null) {
            val nonce = fetchNonce(nonceAction, data)
            if (nonce != null) {
                doc.select(".mirrorstream ul li a[data-content]").amap { btn ->
                    resolveStream(btn, nonce, embedAction, data, subtitleCallback, callback)
                }
            }
        }

        // Downloads
        doc.select(".download ul li").amap { li ->
            val quality = li.selectFirst("strong")?.text()?.trim() ?: ""
            li.select("a[href]").amap { a ->
                val href = a.attr("href").trim().takeIf { it.startsWith("http") } ?: return@amap
                val finalUrl = runCatching { app.get(href, referer = "$mainUrl/").url }
                    .getOrDefault(href)
                loadFixedExtractor(finalUrl, quality, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ================== Helpers ==================

    private fun ajaxHeaders(referer: String) = mapOf(
        "Content-Type"     to "application/x-www-form-urlencoded",
        "User-Agent"       to UA,
        "Referer"          to referer,
        "X-Requested-With" to "XMLHttpRequest"
    )

    private suspend fun fetchNonce(nonceAction: String, referer: String): String? =
        runCatching {
            mapper.readTree(
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders(referer),
                    data    = mapOf("action" to nonceAction)
                ).text
            )?.get("data")?.asText()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private suspend fun resolveStream(
        btn: org.jsoup.nodes.Element,
        nonce: String,
        embedAction: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val b64 = btn.attr("data-content").trim()
            .takeIf { it.isNotBlank() && it != "#" } ?: return
        val token = runCatching {
            mapper.readTree(
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            )
        }.getOrNull() ?: return

        val id = token.get("id")?.asText() ?: return
        val i  = token.get("i")?.asText() ?: ""
        val q  = token.get("q")?.asText() ?: ""

        val embedB64 = runCatching {
            mapper.readTree(
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = ajaxHeaders(referer),
                    data    = mapOf("id" to id, "i" to i, "q" to q, "nonce" to nonce, "action" to embedAction)
                ).text
            )?.get("data")?.asText()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        val iframeHtml = runCatching {
            android.util.Base64.decode(embedB64, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrNull() ?: return

        val embedUrl = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(iframeHtml)?.groupValues?.get(1)
            ?: iframeHtml.trim().takeIf { it.startsWith("http") }
            ?: return

        loadFixedExtractor(embedUrl, q, referer, subtitleCallback, callback)
    }

    private suspend fun loadFixedExtractor(
        url: String,
        qualityHint: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer       = link.referer
                        this.quality       = link.quality.takeIf { it != Qualities.Unknown.value }
                            ?: parseQuality(qualityHint, url)
                        this.headers       = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    // ================== Metadata Fetchers ==================

    private data class AniZipMeta(
        val data: AniZipData,
        val tmdbId: Int?,
        val kitsuId: String?
    )

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val data = mapper.readValue(
                app.get("https://api.ani.zip/mappings?mal_id=$malId").text,
                AniZipData::class.java
            )
            AniZipMeta(
                data    = data,
                tmdbId  = data.mappings?.themoviedbId?.takeIf { it != 0 },
                kitsuId = data.mappings?.kitsuId?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private suspend fun fetchTmdbLogoUrl(type: TvType, tmdbId: Int?, langCode: String?): String? {
        tmdbId ?: return null
        val segment = if (type == TvType.AnimeMovie) "movie" else "tv"
        val logos   = runCatching {
            JSONObject(
                app.get("https://api.themoviedb.org/3/$segment/$tmdbId/images?api_key=98ae14df2b8d8f8f8136499daf79f0e0").text
            ).optJSONArray("logos")
        }.getOrNull()?.takeIf { it.length() > 0 } ?: return null

        val lang = langCode?.trim()?.lowercase()
        fun path(o: JSONObject)  = o.optString("file_path")
        fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
        fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"
        fun score(o: JSONObject) = o.optDouble("vote_average", 0.0)
        fun count(o: JSONObject) = o.optInt("vote_count", 0)
        fun voted(o: JSONObject) = score(o) > 0 && count(o) > 0
        fun better(a: JSONObject?, b: JSONObject) = a == null
            || score(b) > score(a)
            || (score(b) == score(a) && count(b) > count(a))

        var svgFallback: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (path(logo).isBlank() || logo.optString("iso_639_1").trim().lowercase() != lang) continue
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
        svgFallback?.let { return urlOf(it) }

        var best: JSONObject? = null; var bestSvg: JSONObject? = null
        for (i in 0 until logos.length()) {
            val logo = logos.optJSONObject(i) ?: continue
            if (!voted(logo)) continue
            if (isSvg(logo)) { if (better(bestSvg, logo)) bestSvg = logo }
            else             { if (better(best,    logo)) best    = logo }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

    // ================== Data Classes ==================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipEpisode(
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime")    val runtime:    Int?,
        @JsonProperty("image")      val image:      String?,
        @JsonProperty("title")      val title:      Map<String, String>?,
        @JsonProperty("overview")   val overview:   String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int?    = null,
        @JsonProperty("kitsu_id")      val kitsuId:      String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<AniZipImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, AniZipEpisode>?,
        @JsonProperty("mappings")    val mappings:    AniZipMappings? = null
    )
}
