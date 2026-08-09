package com.kuronime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

class KuronimeProvider : MainAPI() {
    override var mainUrl        = "https://kuronime.sbs"
    private var animekuUrl      = "https://animeku.org"
    override var name           = "Kuronime"
    override val hasQuickSearch = true
    override val hasMainPage    = true
    override var lang           = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null
        private const val KEY = "3&!Z0M,VIZ;dZW=="
        private val mapper    = ObjectMapper()
        private val yearRegex = Regex("""\b(19|20)\d{2}\b""")

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

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/%d/?status=ongoing&order=update"   to "Ongoing Anime",
        "$mainUrl/anime/page/%d/?status=completed&order=update" to "Completed Anime",
        "$mainUrl/anime/page/%d/?type=Movie&order=update"       to "Movies",
    )

    // ================== Homepage ==================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val url = request.data.replace("%d", page.toString())
        val req = app.get(url)
        mainUrl = getBaseUrl(req.url)
        val home = req.document.select(".listupd article").map { it.toSearchResult(mainUrl) }
        return newHomePageResponse(HomePageList(request.name, home), hasNext = home.isNotEmpty())
    }

    // ================== Search ==================

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val base = app.get(mainUrl).url
        return mapper.readTree(
            app.post(
                "$base/wp-admin/admin-ajax.php",
                data    = mapOf("action" to "ajaxy_sf", "sf_value" to query, "search" to "false"),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        )?.get("anime")?.get(0)?.get("all")?.mapNotNull { node ->
            val title = node.get("post_title")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link  = node.get("post_link")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = node.get("post_image")?.asText()
                addSub(node.get("post_latest")?.asText()?.toIntOrNull())
            }
        }
    }

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? {
        val doc         = app.get(url).document
        val currentBase = getBaseUrl(url)

        val title = doc.selectFirst(".entry-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val poster      = doc.selectFirst("div.l[itemprop=image] > img, .l > img")?.getImageAttr()
        val tags        = doc.select(".infodetail > ul > li:nth-child(2) > a").map { it.text() }
        val typeStr     = doc.selectFirst(".infodetail > ul > li:nth-child(7)")
                            ?.ownText()?.removePrefix(":")?.trim() ?: "tv"
        val type        = getType(typeStr)
        val trailer     = doc.selectFirst("div.tply iframe")?.attr("data-src")
        val year        = yearRegex.find(
                            doc.select(".infodetail > ul > li:nth-child(5)").text()
                          )?.groupValues?.get(1)?.toIntOrNull()
        val status      = getStatus(
                            doc.selectFirst(".infodetail > ul > li:nth-child(3)")
                               ?.ownText()?.replace(Regex("\\W"), "") ?: ""
                          )
        val description = doc.select("span.const > p").text()

        val tracker  = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val meta     = fetchAniZipMeta(tracker?.malId)
        val logoUrl  = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")
        val bgPoster = meta?.data?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = doc.select("div.bixbox.bxcl > ul > li").amap { el ->
            val link   = el.selectFirst("a")?.attr("href") ?: return@amap null
            val epName = el.selectFirst("a")?.text() ?: return@amap null
            var epNum  = Regex("""(\d+[.,]?\d*)""").find(epName)?.groupValues?.get(0)?.toIntOrNull()
            if (type == TvType.AnimeMovie && epNum == null) epNum = 1

            val aniEp = epNum?.let { meta?.data?.episodes?.get(it.toString()) }
            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie) {
                    meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: title
                } else {
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: epName
                }
                this.episode     = epNum
                this.score       = Score.from10(aniEp?.rating)
                this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url ?: ""
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                this.addDate(aniEp?.airDateUtc)
                this.runTime     = aniEp?.runtime
            }
        }.filterNotNull().reversed()

        val finalPlot = meta?.data?.description?.replace(Regex("<.*?>"), "")
            ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
            ?: description

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.engName             = meta?.data?.titles?.get("en") ?: title
            this.japName             = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = bgPoster
            runCatching { this.logoUrl = logoUrl }
            this.year       = year
            this.showStatus = status
            this.plot       = finalPlot
            this.tags       = tags
            addEpisodes(DubStatus.Subbed, episodes)
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
        val req         = app.get(data)
        val doc         = req.document
        val currentBase = getBaseUrl(req.url)

        val scriptData = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("_0xa100d42aa") }

        if (scriptData != null) {
            val id      = scriptData.substringAfter("_0xa100d42aa = \"").substringBefore("\";")
            val servers = app.post(
                "$animekuUrl/api/v9/sources",
                requestBody = """{"id":"$id"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
                referer     = "$currentBase/"
            ).parsedSafe<Servers>()

            runAllAsync(
                {
                    val decrypt = AesHelper.cryptoAESHandler(
                        base64Decode(servers?.src ?: return@runAllAsync),
                        KEY.toByteArray(), false, false
                    )
                    val source = tryParseJson<Sources>(decrypt?.toJsonFormat())?.src?.replace("\\", "")
                    M3u8Helper.generateM3u8(
                        name, source ?: return@runAllAsync, "$animekuUrl/",
                        headers = mapOf("Origin" to animekuUrl)
                    ).forEach(callback)
                },
                {
                    val decrypt = AesHelper.cryptoAESHandler(
                        base64Decode(servers?.mirror ?: return@runAllAsync),
                        KEY.toByteArray(), false, false
                    )
                    val mirrors = tryParseJson<Mirrors>(decrypt) ?: return@runAllAsync

                    mirrors.embed.forEach { (qualityKey, links) ->
                        links.values.filterNotNull().forEach { url ->
                            loadFixedExtractor(url, qualityKey.removePrefix("v"), "$currentBase/", subtitleCallback, callback)
                        }
                    }

                    mirrors.download.forEach { (qualityKey, links) ->
                        links.values.filterNotNull().forEach { url ->
                            loadFixedExtractor(url, qualityKey.removePrefix("v"), "$currentBase/", subtitleCallback, callback)
                        }
                    }
                }
            )
        }

        return true
    }

    // ================== Helpers ==================

    private suspend fun loadFixedExtractor(
        url: String,
        qualityHint: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback(newExtractorLink(link.name, link.name, link.url, link.type) {
                    this.referer       = link.referer
                    this.headers       = link.headers
                    this.extractorData = link.extractorData
                    this.quality       = link.quality.takeIf { it != Qualities.Unknown.value }
                        ?: parseQualityLabel(qualityHint)
                })
            }
        }
    }

    private fun parseQualityLabel(s: String): Int =
        Regex("""(\d{3,4})""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private fun String.toJsonFormat(): String =
        if (startsWith("\"")) substringAfter("\"").substringBeforeLast("\"").replace("\\\"", "\"")
        else this

    private fun Element.getImageAttr(): String? = when {
        hasAttr("data-src")      -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset")        -> attr("abs:srcset").substringBefore(" ")
        else                     -> attr("abs:src")
    }

    private fun Element.toSearchResult(baseUrl: String): AnimeSearchResponse {
        val href      = getProperAnimeLink(fixUrlNull(selectFirst("a")?.attr("href")).toString(), baseUrl)
        val title     = selectFirst("h2, .bsuxtt, .tt > h4, .entry-title")?.text()?.trim() ?: "Unknown"
        val posterUrl = fixUrlNull((selectFirst("img[itemprop=image]") ?: select("img").lastOrNull())?.getImageAttr())
        val epNum     = select(".ep").text().replace(Regex("\\D"), "").trim().toIntOrNull()
        val tvType    = getType(selectFirst(".bt > span, .bt > .type")?.text().toString())
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun getProperAnimeLink(uri: String, baseUrl: String): String {
        if (uri.contains("/anime/")) return uri
        val slug  = uri.trimEnd('/').substringAfterLast("/")
        val title = when {
            slug.contains("-episode") && !slug.contains("-movie") ->
                Regex("nonton-(.+)-episode").find(slug)?.groupValues?.get(1) ?: slug
            slug.contains("-movie") ->
                Regex("nonton-(.+)-movie").find(slug)?.groupValues?.get(1) ?: slug
            else -> slug
        }
        return "$baseUrl/anime/$title"
    }

    // ================== Metadata ==================

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
        fun better(a: JSONObject?, b: JSONObject) = a == null
            || score(b) > score(a) || (score(b) == score(a) && count(b) > count(a))

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

    data class Mirrors(
        @JsonProperty("embed")     val embed:    Map<String, Map<String, String?>> = emptyMap(),
        @JsonProperty("download")  val download: Map<String, Map<String, String?>> = emptyMap(),
        @JsonProperty("filelions") val filelions: String?                          = null,
    )

    data class Sources(
        @JsonProperty("src") val src: String? = null
    )

    data class Servers(
        @JsonProperty("src")    val src:    String? = null,
        @JsonProperty("mirror") val mirror: String? = null
    )
}
