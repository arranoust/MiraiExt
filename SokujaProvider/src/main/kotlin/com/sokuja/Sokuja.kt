package com.sokuja

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLDecoder

class SokujaProvider : MainAPI() {
    override var mainUrl            = "https://x6.sokuja.uk"
    override var name               = "Sokuja"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null
        private const val UA        = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val yearRegex       = Regex("""\b(19|20)\d{2}\b""")
        private val nextImageRegex  = Regex("""[?&]url=([^&]+)""")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=ongoing&order=update&page=%d"   to "Ongoing Anime",
        "$mainUrl/anime/?status=completed&order=update&page=%d" to "Completed Anime",
        "$mainUrl/anime/?type=Movie&order=update&page=%d"       to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val items = app.get(request.data.replace("%d", page.toString()), headers = mapOf("User-Agent" to UA))
            .document.select("a.group.block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href  = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h3")?.text()?.trim() ?: return null
        return newAnimeSearchResponse(title, fixUrl(href), getType(selectFirst("span.absolute.left-2")?.text() ?: "")) {
            this.posterUrl = selectFirst("img")?.extractNextImageUrl()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/", params = mapOf("s" to query), headers = mapOf("User-Agent" to UA))
            .document.select("a.group.block").mapNotNull { it.toSearchResult() }

    override suspend fun load(url: String): LoadResponse? {
        val rscText = app.get(url, headers = mapOf("User-Agent" to UA, "RSC" to "1")).text
        val doc     = app.get(url, headers = mapOf("User-Agent" to UA)).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(Regex("(?i)\\s*Sub(?:title)?\\s*Indo(?:nesia)?"), "").trim()

        val infoMap  = buildInfoMap(doc)
        val type     = getType(infoMap["Tipe"] ?: infoMap["Type"] ?: "")
        val year     = yearRegex.find(infoMap["Tahun"] ?: infoMap["Year"] ?: "")?.value?.toIntOrNull()

        val tracker  = APIHolder.getTracker(
            listOf(title, rawTitle, title.replace(Regex("(?i)\\s*Season\\s*\\d+:?"), "").trim()),
            TrackerType.getTypes(type), year, true
        )
        val meta     = fetchAniZipMeta(tracker?.malId)
        val logoUrl  = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")

        return newAnimeLoadResponse(title, url, type) {
            this.engName             = meta?.data?.titles?.get("en") ?: title
            this.japName             = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: doc.selectFirst("img.object-cover")?.extractNextImageUrl()
            this.backgroundPosterUrl = meta?.data?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover
            runCatching { this.logoUrl = logoUrl }
            this.year       = year
            this.plot       = meta?.data?.description?.replace(Regex("<.*?>"), "")
                           ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                           ?: doc.selectFirst("div.prose")?.text()?.trim()
            this.tags       = doc.select("a[href*='/genre/']").map { it.text() }
            this.showStatus = getStatus(infoMap["Status"] ?: "")
            addEpisodes(DubStatus.Subbed, parseEpisodesFromRsc(rscText, type, meta))
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            runCatching { addKitsuId(meta?.kitsuId) }
        }
    }

    private fun parseEpisodesFromRsc(rscText: String, type: TvType, meta: AniZipMeta?): List<Episode> {
        val start = rscText.indexOf("\"episodes\":[")
            if (start == -1) return emptyList()

            // Extract the JSON array by bracket matching
            var depth = 0
            var end   = start + 12
            for (i in (start + 11) until rscText.length) {
                when (rscText[i]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { end = i + 1; break } }
                }
            }
            val arrayJson = rscText.substring(start + 11, end)

        return runCatching {
            mapper.readTree(arrayJson).mapNotNull { node ->
                val epNum = node.get("episodeNumber")?.asInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val slug  = node.get("slug")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id    = node.get("id")?.asInt() ?: return@mapNotNull null
                val aniEp = meta?.data?.episodes?.get(epNum.toString())
                newEpisode("$mainUrl/$slug/?eid=$id") {
                    this.name = if (type == TvType.AnimeMovie)
                        meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: slug
                    else
                        aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: node.get("title")?.asText() ?: slug
                    this.episode     = epNum
                    this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url
                    this.description = aniEp?.overview?.takeIf { it.isNotBlank() } ?: "Synopsis not yet available."
                    this.runTime     = aniEp?.runtime
                    this.addDate(aniEp?.airDateUtc)
                }
            }.reversed()
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = Regex("""[?&]eid=(\d+)""").find(data)?.groupValues?.get(1)
            ?: run {
                val baseUrl = data.substringBefore("?")
                val slug    = baseUrl.trimEnd('/').substringAfterLast("/")
                val rsc     = app.get(baseUrl, headers = mapOf("User-Agent" to UA, "RSC" to "1")).text
                // match id before slug to avoid ambiguity
                Regex(""""id"\s*:\s*(\d+)\s*,\s*"slug"\s*:\s*"${Regex.escape(slug)}"""")
                    .find(rsc)?.groupValues?.get(1)
            } ?: return false

        val mirrors = mapper.readValue(
            app.get("$mainUrl/api/video-mirrors", params = mapOf("e" to episodeId),
                headers = mapOf("User-Agent" to UA, "Referer" to data)).text,
            VideoMirrorsResponse::class.java
        ).mirrors

        mirrors.forEach { mirror ->
            callback(newExtractorLink(
                mirror.serverName, mirror.serverName, mirror.embedUrl,
                if (mirror.embedUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8
                else ExtractorLinkType.VIDEO
            ) {
                this.referer = data
                this.quality = parseQuality(mirror.quality)
            })
        }
        return mirrors.isNotEmpty()
    }

    private fun Element.extractNextImageUrl(): String? {
        val src = attr("abs:src").ifBlank { attr("src") }.takeIf { it.isNotBlank() } ?: return null
        if (!src.contains("/_next/image/")) return src
        val encoded = nextImageRegex.find(src)?.groupValues?.get(1) ?: return src
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return src
        return if (decoded.startsWith("http")) decoded else "$mainUrl$decoded"
    }

    private fun buildInfoMap(doc: org.jsoup.nodes.Document): Map<String, String> =
        doc.select("div.flex.gap-3").mapNotNull { row ->
            val k = row.selectFirst("dt")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val v = row.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            k to v
        }.toMap()

    private fun parseQuality(q: String?): Int = when {
        q == null                               -> Qualities.Unknown.value
        q.contains("2160") || q.contains("4K") -> Qualities.P2160.value
        q.contains("1080")                      -> Qualities.P1080.value
        q.contains("720")                       -> Qualities.P720.value
        q.contains("480")                       -> Qualities.P480.value
        q.contains("360")                       -> Qualities.P360.value
        else -> Regex("""(\d{3,4})""").find(q)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
    }

    private fun getType(t: String): TvType = when {
        t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
        t.contains("Movie", true)                              -> TvType.AnimeMovie
        else                                                   -> TvType.Anime
    }

    private fun getStatus(t: String): ShowStatus = when {
        t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
        else                                                         -> ShowStatus.Ongoing
    }

    private data class AniZipMeta(val data: AniZipData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            val tree = mapper.readTree(json)
            AniZipMeta(
                data    = mapper.readValue(json, AniZipData::class.java),
                tmdbId  = tree?.get("mappings")?.get("themoviedb_id")?.asInt()?.takeIf { it != 0 },
                kitsuId = tree?.get("mappings")?.get("kitsu_id")?.asText()?.takeIf { it.isNotBlank() }
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
            else             { if (better(best, logo))    best    = logo }
        }
        return best?.let { urlOf(it) } ?: bestSvg?.let { urlOf(it) }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoMirror(
        @JsonProperty("id")         val id:         Int?,
        @JsonProperty("serverName") val serverName: String,
        @JsonProperty("embedUrl")   val embedUrl:   String,
        @JsonProperty("embedType")  val embedType:  String?,
        @JsonProperty("quality")    val quality:    String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VideoMirrorsResponse(
        @JsonProperty("mirrors") val mirrors: List<VideoMirror> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url")       val url:       String?
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
    data class AniZipData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<AniZipImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, AniZipEpisode>?,
    )
}
