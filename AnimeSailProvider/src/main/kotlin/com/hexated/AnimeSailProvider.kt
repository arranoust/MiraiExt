package com.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.text.Regex

class AnimeSail : MainAPI() {

    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
    return app.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (ref ?: mainUrl)
        ),
        cookies = mapOf("_as_ipin_ct" to "ID"),
        timeout = 20_000
    )
}
    
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/rilisan-anime-terbaru/page/" to "Anime Terbaru",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Donghua Terbaru",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = request(request.data + page).document
        val home = document.select("article").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        if (uri.contains("/anime/")) return uri

        var title = uri.substringAfter("$mainUrl/")
        title = when {
            title.contains("-episode") -> title.substringBefore("-episode")
            title.contains("-movie") -> title.substringBefore("-movie")
            else -> title
        }

        return "$mainUrl/anime/$title"
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")?.attr("href") ?: ""))
        val title = this.select(".tt > h2")
            .text()
            .replace("Subtitle Indonesia", "", true)
            .replace(Regex("Episode\\s?\\d+", RegexOption.IGNORE_CASE), "")
            .trim()
        val posterUrl = fixUrl(this.selectFirst("div.limit img")?.attr("src") ?: "")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = request("$mainUrl/?s=$query").document
        return document.select("div.listupd article").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.replace("Subtitle Indonesia", "", true)
            ?.trim()
            ?: ""

        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type =
            getType(document.select("tbody th:contains(Tipe)").next().text())
        val year =
            document.select("tbody th:contains(Dirilis)").next().text().toIntOrNull()

        val tracker =
            APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        val episodes =
            document.select("ul.daftar > li")
                .mapNotNull { li ->
                    val anchor = li.selectFirst("a") ?: return@mapNotNull null
                    val link = fixUrl(anchor.attr("href"))
                    val text = anchor.text()

                    val episodeNumber =
                        Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE)
                            .find(text)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    newEpisode(link) {
                        this.name = episodeNumber?.let { "Episode $it" } ?: "Episode"
                        this.episode = episodeNumber
                        this.posterUrl = tracker?.image ?: poster
                    }
                }
                .reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            plot = document.selectFirst("div.entry-content > p")?.text()
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }

            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = request(data).document

        coroutineScope {
            document.select(".mobius > .mirror > option").map { option ->
                async {
                    safeApiCall {
                        val iframe =
                            fixUrl(
                                Jsoup.parse(base64Decode(option.attr("data-em")))
                                    .select("iframe")
                                    .attr("src")
                            )

                        val quality = getIndexQuality(option.text())

                        loadExtractor(iframe, data, subtitleCallback) { link ->
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = name,
                                    url = link.url,
                                    referer = link.referer,
                                    quality = quality,
                                    type = link.type,
                                    extractorData = link.extractorData,
                                    headers = link.headers
                                )
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}