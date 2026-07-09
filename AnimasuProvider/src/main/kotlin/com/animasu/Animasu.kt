package com.animasu

import android.util.Base64
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

class AnimasuProvider : MainAPI() {
    override var mainUrl            = "https://v1.animasu.work"
    override var name               = "Animasu"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var context: android.content.Context? = null

        private val mapper          = ObjectMapper()
        private val episodeNumRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val qualityRegex    = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val yearRegex       = Regex("""\b(19|20)\d{2}\b""")

        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Spesial", true) || t.contains("Special", true) -> TvType.OVA
            t.contains("Movie", true) || t.contains("Film", true)                                 -> TvType.AnimeMovie
            else                                                                                   -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Selesai", true) || t.contains("Completed", true) -> ShowStatus.Completed
            else                                                           -> ShowStatus.Ongoing
        }
    }

    // ================== Homepage ==================

    override val mainPage = mainPageOf(
        "$mainUrl/pencarian/?status=ongoing&tipe=&urutan=update&halaman=%d"   to "Sedang Tayang",
        "$mainUrl/pencarian/?status=completed&tipe=&urutan=update&halaman=%d" to "Selesai Tayang",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        context?.let { PopupHelper.showPopupIfNeeded(it) }
        val url   = request.data.replace("%d", page.toString())
        val items = app.get(url).document.select("div.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val a         = selectFirst("a") ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val title     = selectFirst("div.tt")?.text()?.trim()
                     ?: a.attr("title").removePrefix("Nonton Anime ").trim()
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val epNum     = episodeNumRegex.find(selectFirst("span.epx")?.text() ?: "")
                            ?.groupValues?.get(1)?.toIntOrNull()
        val typeText  = selectFirst("div.typez")?.text() ?: ""
        return newAnimeSearchResponse(title, href, getType(typeText)) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    // ================== Search ==================

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
            .select("div.bs")
            .mapNotNull { it.toSearchResult() }

    // ================== Load ==================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("div.infox h1")?.text() ?: return null
        val title    = rawTitle
            .replace(Regex("(?i)\\s*Sub(?:title)?\\s*Indo(?:nesia)?"), "")
            .trim()

        val poster    = doc.selectFirst("div.bigcontent div.thumb img")?.attr("src")
        val synopsis  = doc.selectFirst("span.desc")?.text()?.trim()
        val tags      = doc.select("div.spe span:contains(Genre) a").map { it.text() }
        val typeText  = doc.selectFirst("div.spe span:contains(Jenis)")?.ownText()
                            ?.replace(":", "")?.trim() ?: ""
        val type      = getType(typeText)
        val status    = getStatus(doc.selectFirst("div.spe span:contains(Status)")?.text() ?: "")
        val year      = yearRegex.find(
            doc.select("div.spe span:contains(Musim), div.spe span:contains(Rilis)").text()
        )?.value?.toIntOrNull()
        val trailer   = doc.selectFirst("iframe[src*=youtube]")?.attr("src")
        val ratingRaw = doc.selectFirst("div.rating strong")?.text()
                            ?.replace(Regex("[^\\d.]"), "")?.toDoubleOrNull()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        val meta    = fetchAniZipMeta(tracker?.malId)
        val logoUrl = fetchTmdbLogoUrl(type, meta?.tmdbId, "en")
        val bgPoster = meta?.data?.images?.find { it.coverType == "Fanart" }?.url ?: tracker?.cover

        val episodes = doc.select("ul#daftarepisode li").mapNotNull { el ->
            val a      = el.selectFirst("span.lchx a") ?: return@mapNotNull null
            val epName = a.text().trim()
            val epNum  = episodeNumRegex.find(epName)?.groupValues?.get(1)?.toIntOrNull()
                      ?: if (type == TvType.AnimeMovie) 1 else null
            val link   = fixUrl(a.attr("href"))
            val aniEp  = epNum?.let { meta?.data?.episodes?.get(it.toString()) }
            newEpisode(link) {
                this.name = if (type == TvType.AnimeMovie)
                    meta?.data?.titles?.get("en") ?: meta?.data?.titles?.get("ja") ?: title
                else
                    aniEp?.title?.get("en") ?: aniEp?.title?.get("ja") ?: epName
                this.episode     = epNum
                this.posterUrl   = aniEp?.image ?: meta?.data?.images?.firstOrNull()?.url
                this.description = aniEp?.overview?.takeIf { it.isNotBlank() }
                                ?: "Synopsis not yet available."
                this.runTime     = aniEp?.runtime
                this.addDate(aniEp?.airDateUtc)
            }
        }.reversed()

        val finalPlot = meta?.data?.description?.replace(Regex("<.*?>"), "")
                     ?: meta?.data?.episodes?.get("1")?.overview?.takeIf { it.isNotBlank() }
                     ?: synopsis

        return newAnimeLoadResponse(title, url, type) {
            this.engName             = meta?.data?.titles?.get("en") ?: title
            this.japName             = meta?.data?.titles?.get("ja") ?: meta?.data?.titles?.get("x-jat")
            this.posterUrl           = tracker?.image ?: poster
            this.backgroundPosterUrl = bgPoster
            runCatching { this.logoUrl = logoUrl }
            this.year                = year
            this.plot                = finalPlot
            this.tags                = tags
            this.showStatus          = status
            this.score               = Score.from10(ratingRaw)
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
        val doc = app.get(data).document
        doc.select("select.mirror option[value]").forEach { option ->
            val b64   = option.attr("value").trim().takeIf { it.isNotBlank() } ?: return@forEach
            val label = option.text().trim()
            val quality = qualityRegex.find(label)?.groupValues?.get(1)?.toIntOrNull()
                       ?: Qualities.Unknown.value

            val iframeHtml = runCatching {
                Base64.decode(b64, Base64.DEFAULT).toString(Charsets.UTF_8)
            }.getOrNull() ?: return@forEach

            val embedUrl = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(iframeHtml)?.groupValues?.get(1)?.trim()
                ?: return@forEach

            loadExtractor(embedUrl, data, subtitleCallback) { link ->
                runBlocking {
                    callback(
                        newExtractorLink(link.name, link.name, link.url, link.type) {
                            this.referer       = link.referer
                            this.quality       = link.quality.takeIf { it != Qualities.Unknown.value }
                                             ?: quality
                            this.headers       = link.headers
                            this.extractorData = link.extractorData
                        }
                    )
                }
            }
        }
        return true
    }

    // ================== Metadata ==================

    private data class AniZipMeta(val data: AniZipData, val tmdbId: Int?, val kitsuId: String?)

    private suspend fun fetchAniZipMeta(malId: Int?): AniZipMeta? {
        malId ?: return null
        return runCatching {
            val json = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            AniZipMeta(
                data    = mapper.readValue(json, AniZipData::class.java),
                tmdbId  = mapper.readTree(json)?.get("mappings")?.get("themoviedb_id")?.asInt()?.takeIf { it != 0 },
                kitsuId = mapper.readTree(json)?.get("mappings")?.get("kitsu_id")?.asText()?.takeIf { it.isNotBlank() }
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
    data class AniZipData(
        @JsonProperty("titles")      val titles:      Map<String, String>?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("images")      val images:      List<AniZipImage>?,
        @JsonProperty("episodes")    val episodes:    Map<String, AniZipEpisode>?,
    )
}
