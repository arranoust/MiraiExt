package com.samehadaku

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

class SamehadakuProvider : MainAPI() {
    override var mainUrl            = "https://v2.samehadaku.how"
    override var name               = "Samehadaku"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null

        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s?(\d+)""")
        private val yearRegex       = Regex("""\b(19|20)\d{2}\b""")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true)                              -> TvType.AnimeMovie
            else                                                   -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing"   -> ShowStatus.Ongoing
            else        -> ShowStatus.Completed
        }
    }

    // ================== Homepage ==================

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/"                                          to "New Episodes",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest"   to "Ongoing Anime",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=latest"    to "Completed Anime",
        "daftar-anime-2/page/%d/?type=Movie&order=latest"                 to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val doc      = app.get("$mainUrl/${request.data.format(page)}").document
        val isLatest = request.data.contains("anime-terbaru")
        val homeList = if (isLatest) {
            doc.select("li[itemtype='http://schema.org/CreativeWork']").mapNotNull { it.toLatestAnimeResult() }
        } else {
            doc.select("div.animposx").mapNotNull { it.toSearchResult() }
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList, isHorizontalImages = isLatest)),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a         = selectFirst("a") ?: return null
        val title     = selectFirst("div.data div.title h2")?.text()?.trim()
                     ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(selectFirst("div.content-thumb img")?.attr("src"))
        val statusText = selectFirst("div.data > div.type")?.text()?.trim() ?: ""
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(statusText)
        }
    }

    private fun Element.toLatestAnimeResult(): AnimeSearchResponse? {
        val a         = selectFirst("div.thumb a") ?: return null
        val title     = selectFirst("h2.entry-title a")?.text()?.trim()
                     ?: a.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val posterUrl = fixUrlNull(a.selectFirst("img")?.attr("src"))
        val epNum     = selectFirst("div.dtla span author[itemprop=name]")?.text()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document
            .select("div.animposx")
            .mapNotNull { it.toSearchResult() }
    }

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl = if (url.contains("/anime/")) url
                     else app.get(url).document.selectFirst("div.nvs.nvsc a")?.attr("href") ?: url
        val doc    = app.get(fixUrl).document

        val rawTitle = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val title    = rawTitle
            .replace(Regex("(?i)(Nonton|Anime|Subtitle\\s+Indonesia|Sub\\s+Indo|Lengkap|Batch)"), "")
            .trim()

        val poster      = doc.selectFirst("div.thumb > img")?.attr("src")
        val tags        = doc.select("div.genre-info > a").map { it.text() }
        val year        = yearRegex.find(doc.select("div.spe > span:contains(Rilis)").text())
                            ?.groupValues?.get(1)?.toIntOrNull()
        val statusStr   = doc.selectFirst("div.spe > span:contains(Status)")
                            ?.ownText()?.replace(":", "")?.trim() ?: "Completed"
        val type        = getType(
            doc.selectFirst("div.spe > span:contains(Type)")
                ?.ownText()?.replace(":", "")?.trim()?.lowercase() ?: "tv"
        )
        val rating      = doc.selectFirst("span.ratingValue, div.rating strong")
                            ?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()
        val description = doc.select("div.desc p, div.entry-content p").text().trim()
        val trailer     = doc.selectFirst("div.trailer-anime iframe")?.attr("src")

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val meta    = fetchAniZipMeta(tracker?.malId)
        val logoUrl = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")

        val backgroundPoster = meta?.data?.images?.find { it.coverType == "Fanart" }?.url
                            ?: tracker?.cover

        val episodes = doc.select("div.lstepsiode.listeps ul li").amap { el ->
            val header = el.selectFirst("span.lchx > a") ?: return@amap null
            val name   = header.text()
            var epNum  = episodeNumRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (type == TvType.AnimeMovie && epNum == null) epNum = 1

            val link  = fixUrl(header.attr("href"))
            val aniEp = epNum?.let { meta?.data?.episodes?.get(it.toString()) }

            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: title
                } else {
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: name
                }
                this.episode     = epNum
                this.score       = Score.from10(aniEp?.rating)
                this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                this.addDate(aniEp?.airDateUtc)
                this.runTime     = aniEp?.runtime
            }
        }.filterNotNull().reversed()

        val recommendations = doc.select("ul li a.series").mapNotNull { a ->
            val recTitle  = a.selectFirst("span.judul")?.text()?.trim() ?: return@mapNotNull null
            val recHref   = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val recPoster = fixUrlNull(a.selectFirst("img")?.attr("src"))
            val recEpNum  = Regex("""\d+""").find(a.selectFirst("span.episode")?.text() ?: "")
                                ?.value?.toIntOrNull()
            newAnimeSearchResponse(recTitle, recHref, TvType.Anime) {
                this.posterUrl = recPoster
                addSub(recEpNum)
            }
        }

        val finalPlot = meta?.data?.description?.replace(Regex("<.*?>"), "")
                     ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                     ?: description

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName             = meta?.data?.titles?.get("en") ?: title
            this.japName             = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = backgroundPoster
            runCatching { this.logoUrl = logoUrl }
            this.year                = year
            addEpisodes(DubStatus.Subbed, episodes)
            this.showStatus          = getStatus(statusStr)
            this.score               = rating?.let { Score.from10(it) }
                                    ?: Score.from10(meta?.data?.episodes?.get("1")?.rating)
            this.plot                = finalPlot
            addTrailer(trailer)
            this.tags                = tags
            this.recommendations     = recommendations
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
        val doc = app.get(data).document

        // Downloads
        doc.select("div#downloadb li").amap { el ->
            val quality = el.select("strong").text()
            el.select("a").amap {
                loadFixedExtractor(fixUrl(it.attr("href")), quality, "$mainUrl/", subtitleCallback, callback)
            }
        }

        // Streams
        doc.select("div.east_player_option[data-post][data-nume]").amap { btn ->
            val postId = btn.attr("data-post").takeIf { it.isNotBlank() } ?: return@amap
            val nume   = btn.attr("data-nume").takeIf { it.isNotBlank() } ?: return@amap
            val type   = btn.attr("data-type").takeIf { it.isNotBlank() } ?: return@amap
            val label  = btn.selectFirst("span")?.text()?.trim() ?: "Mirror $nume"

            val iframeUrl = fetchStreamIframe(postId, nume, type) ?: return@amap

            if (isDirectVideoUrl(iframeUrl) || iframeUrl.contains("wibufile.com", ignoreCase = true)) {
                callback(
                    newExtractorLink(label, label, iframeUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.quality = label.parseQualityLabel()
                    }
                )
            } else {
                loadFixedExtractor(iframeUrl, label, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    // ================== Helpers ==================

    private suspend fun fetchStreamIframe(postId: String, nume: String, type: String): String? =
        runCatching {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data    = mapOf("action" to "player_ajax", "post" to postId, "nume" to nume, "type" to type),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to mainUrl)
            ).text
            val raw   = runCatching { mapper.readTree(response)?.get("embed")?.asText() }.getOrNull() ?: response
            val embed = raw.replace("\\\"", "\"").replace("\\/", "/")
            Regex("""src=["']([^"']+)["']""").find(embed)?.groupValues?.get(1)
        }.getOrNull()

    private suspend fun loadFixedExtractor(
        url: String,
        qualityHint: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(
                    newExtractorLink(link.name, link.name, link.url, link.type) {
                        this.referer       = link.referer
                        this.quality       = link.quality.takeIf { it != Qualities.Unknown.value }
                                         ?: qualityHint.parseQualityLabel()
                        this.headers       = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".mp4") || l.contains(".mkv") || l.contains(".webm") || l.contains(".m3u8")
    }

    private fun String.parseQualityLabel(): Int {
        val u = uppercase()
        return when {
            u.contains("4K")     -> Qualities.P2160.value
            u.contains("FULLHD") -> Qualities.P1080.value
            u.contains("MP4HD")  -> Qualities.P720.value
            else -> Regex("""(\d{3,4})[pP]?""").findAll(this)
                        .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
        }
    }

    // ================== Metadata Fetchers ==================

    private data class AniZipMeta(val data: MetaAnimeData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val data = mapper.readValue(
                app.get("https://api.ani.zip/mappings?mal_id=$malId").text,
                MetaAnimeData::class.java
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
        fun better(a: JSONObject?, b: JSONObject) = a == null || score(b) > score(a)
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
    data class MetaImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaEpisode(
        @JsonProperty("episode")    val episode:    String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?,
        @JsonProperty("runtime")    val runtime:    Int?,
        @JsonProperty("image")      val image:      String?,
        @JsonProperty("title")      val title:      Map<String, String>?,
        @JsonProperty("overview")   val overview:   String?,
        @JsonProperty("rating")     val rating:     String?,
        @JsonProperty("finaleType") val finaleType: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaMappings(
        @JsonProperty("themoviedb_id") val themoviedbId: Int?    = null,
        @JsonProperty("kitsu_id")      val kitsuId:      String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MetaAnimeData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<MetaImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, MetaEpisode>?,
        @JsonProperty("mappings")    val mappings:    MetaMappings? = null
    )
}
