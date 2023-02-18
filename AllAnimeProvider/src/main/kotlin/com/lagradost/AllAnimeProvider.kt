package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.mainWork
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URI


class AllAnimeProvider : MainAPI() {
    override var mainUrl = "https://allanime.to"
    private val apiUrl = "https://api.allanime.co"
    override var name = "AllAnime"
    override val hasQuickSearch = false
    override val hasMainPage = true

    private fun getStatus(t: String): ShowStatus {
        return when (t) {
            "Finished" -> ShowStatus.Completed
            "Releasing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private data class Data(
        @JsonProperty("shows") val shows: Shows
    )

    private data class Shows(
        @JsonProperty("pageInfo") val pageInfo: PageInfo,
        @JsonProperty("edges") val edges: List<Edges>,
        @JsonProperty("__typename") val _typename: String
    )

    private data class Edges(
        @JsonProperty("_id") val Id: String?,
        @JsonProperty("name") val name: String,
        @JsonProperty("englishName") val englishName: String?,
        @JsonProperty("nativeName") val nativeName: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("season") val season: Season?,
        @JsonProperty("score") val score: Double?,
        @JsonProperty("airedStart") val airedStart: AiredStart?,
        @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes?,
        @JsonProperty("availableEpisodesDetail") val availableEpisodesDetail: AvailableEpisodesDetail?,
        @JsonProperty("studios") val studios: List<String>?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("averageScore") val averageScore: Int?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("banner") val banner: String?,
        @JsonProperty("episodeDuration") val episodeDuration: Int?,
        @JsonProperty("prevideos") val prevideos: List<String> = emptyList(),
    )

    private data class AvailableEpisodes(
        @JsonProperty("sub") val sub: Int,
        @JsonProperty("dub") val dub: Int,
        @JsonProperty("raw") val raw: Int
    )

    private data class AiredStart(
        @JsonProperty("year") val year: Int,
        @JsonProperty("month") val month: Int,
        @JsonProperty("date") val date: Int
    )

    private data class Season(
        @JsonProperty("quarter") val quarter: String,
        @JsonProperty("year") val year: Int
    )

    private data class PageInfo(
        @JsonProperty("total") val total: Int,
        @JsonProperty("__typename") val _typename: String
    )

    private data class AllAnimeQuery(
        @JsonProperty("data") val data: Data
    )

    data class RandomMain(
        @JsonProperty("data") var data: DataRan? = DataRan()
    )

    data class DataRan(
        @JsonProperty("queryRandomRecommendation") var queryRandomRecommendation: ArrayList<QueryRandomRecommendation> = arrayListOf()
    )

    data class QueryRandomRecommendation(
        @JsonProperty("_id") val Id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("englishName") val englishName: String? = null,
        @JsonProperty("nativeName") val nativeName: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("airedStart") val airedStart: String? = null,
        @JsonProperty("availableChapters") val availableChapters: String? = null,
        @JsonProperty("availableEpisodes") val availableEpisodes: String? = null,
        @JsonProperty("__typename") val _typename: String? = null
    )

    private val popularTitle = "Popular"
    private val recentTitle = "Recently updated"
    override val mainPage = listOf(
        MainPageData(
            recentTitle,
            """$mainUrl/allanimeapi?variables={"search":{"sortBy":"Recent","allowAdult":${settingsForProvider.enableAdult},"allowUnknown":false},"limit":26,"page":%d,"translationType":"sub","countryOrigin":"ALL"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"9c7a8bc1e095a34f2972699e8105f7aaf9082c6e1ccd56eab99c2f1a971152c6"}}"""
        ),
        MainPageData(
            popularTitle,
            """$mainUrl/allanimeapi?variables={"type":"anime","size":30,"dateRange":1,"page":%d,"allowAdult":${settingsForProvider.enableAdult},"allowUnknown":false}&extensions={"persistedQuery":{"version":1,"sha256Hash":"6f6fe5663e3e9ea60bdfa693f878499badab83e7f18b56acdba5f8e8662002aa"}}"""
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val test = app.get(url).text

        val home = when (request.name) {
            recentTitle -> {
                val json = parseJson<AllAnimeQuery>(test)
                val results = json.data.shows.edges.filter {
                    // filtering in case there is an anime with 0 episodes available on the site.
                    !(it.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
                }

                results.map {
                    newAnimeSearchResponse(it.name, "$mainUrl/anime/${it.Id}", fix = false) {
                        this.posterUrl = it.thumbnail
                        this.year = it.airedStart?.year
                        this.otherName = it.englishName
                        addDub(it.availableEpisodes?.dub)
                        addSub(it.availableEpisodes?.sub)
                    }
                }
            }
            popularTitle -> {
                val json = parseJson<PopularQuery>(test)
                val results = json.data?.queryPopular?.recommendations?.filter {
                    // filtering in case there is an anime with 0 episodes available on the site.
                    !(it.anyCard?.availableEpisodes?.raw == 0 && it.anyCard.availableEpisodes.sub == 0 && it.anyCard.availableEpisodes.dub == 0)
                }
                results?.mapNotNull {
                    newAnimeSearchResponse(
                        it.anyCard?.name ?: return@mapNotNull null,
                        "$mainUrl/anime/${it.anyCard.Id ?: it.pageStatus?.Id}",
                        fix = false
                    ) {
                        this.posterUrl = it.anyCard.thumbnail
                        this.otherName = it.anyCard.englishName
                        addDub(it.anyCard.availableEpisodes?.dub)
                        addSub(it.anyCard.availableEpisodes?.sub)
                    }
                } ?: emptyList()
            }
            else -> emptyList()
        }



        return HomePageResponse(
            listOf(
                HomePageList(request.name, home)
            ), hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link =
            """$mainUrl/allanimeapi?variables={"search":{"allowAdult":false,"allowUnknown":false,"query":"$query"},"limit":26,"page":1,"translationType":"sub","countryOrigin":"ALL"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"9c7a8bc1e095a34f2972699e8105f7aaf9082c6e1ccd56eab99c2f1a971152c6"}}"""
        var res = app.get(link).text
        if (res.contains("PERSISTED_QUERY_NOT_FOUND")) {
            res = app.get(link).text
            if (res.contains("PERSISTED_QUERY_NOT_FOUND")) return emptyList()
        }
        val response = parseJson<AllAnimeQuery>(res)

        val results = response.data.shows.edges.filter {
            // filtering in case there is an anime with 0 episodes available on the site.
            !(it.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
        }

        return results.map {
            newAnimeSearchResponse(it.name, "$mainUrl/anime/${it.Id}", fix = false) {
                this.posterUrl = it.thumbnail
                this.year = it.airedStart?.year
                this.otherName = it.englishName
                addDub(it.availableEpisodes?.dub)
                addSub(it.availableEpisodes?.sub)
            }
        }
    }

    private data class AvailableEpisodesDetail(
        @JsonProperty("sub") val sub: List<String>,
        @JsonProperty("dub") val dub: List<String>,
        @JsonProperty("raw") val raw: List<String>
    )

    override suspend fun load(url: String): LoadResponse? {
        val (rhino, scope) = mainWork {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            rhino to scope
        }

        val html = app.get(url).text
        val soup = Jsoup.parse(html)

        val script = soup.select("script").firstOrNull {
            it.html().contains("window.__NUXT__")
        } ?: return null

        val js = """
            const window = {}
            ${script.html()}
            const returnValue = JSON.stringify(window.__NUXT__.fetch[0].show)
        """.trimIndent()

        val jsEval = mainWork {
            rhino.evaluateString(scope, js, "JavaScript", 1, null)
            scope.get("returnValue", scope) ?: return@mainWork null
        } ?: return null

        val showData = parseJson<Edges>(jsEval as String)

        val title = showData.name
        val description = showData.description
        val poster = showData.thumbnail

        val episodes = showData.availableEpisodes.let {
            if (it == null) return@let Pair(null, null)
            if (showData.Id == null) return@let Pair(null, null)

            Pair(if (it.sub != 0) ((1..it.sub).map { epNum ->
                Episode(
                    AllAnimeLoadData(showData.Id, "sub", epNum).toJson(), episode = epNum
                )
            }) else null, if (it.dub != 0) ((1..it.dub).map { epNum ->
                Episode(
                    AllAnimeLoadData(showData.Id, "dub", epNum).toJson(), episode = epNum
                )
            }) else null)
        }

        val characters = soup.select("div.character > div.card-character-box").mapNotNull {
            val img = it?.selectFirst("img")?.attr("src") ?: return@mapNotNull null
            val name = it.selectFirst("div > a")?.ownText() ?: return@mapNotNull null
            val role = when (it.selectFirst("div > .text-secondary")?.text()?.trim()) {
                "Main" -> ActorRole.Main
                "Supporting" -> ActorRole.Supporting
                "Background" -> ActorRole.Background
                else -> null
            }
            Pair(Actor(name, img), role)
        }

        // bruh, they use graphql and bruh it is fucked
        //val recommendations = soup.select("#suggesction > div > div.p > .swipercard")?.mapNotNull {
        //    val recTitle = it?.selectFirst(".showname > a") ?: return@mapNotNull null
        //    val recName = recTitle.text() ?: return@mapNotNull null
        //    val href = fixUrlNull(recTitle.attr("href")) ?: return@mapNotNull null
        //    val img = it.selectFirst(".image > img").attr("src") ?: return@mapNotNull null
        //    AnimeSearchResponse(recName, href, this.name, TvType.Anime, img)
        //}

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = showData.banner
            rating = showData.averageScore?.times(100)
            tags = showData.genres
            year = showData.airedStart?.year
            duration = showData.episodeDuration?.div(60_000)
            addTrailer(showData.prevideos.filter { it.isNotBlank() }
                .map { "https://www.youtube.com/watch?v=$it" })

            addEpisodes(DubStatus.Subbed, episodes.first)
            addEpisodes(DubStatus.Dubbed, episodes.second)
            addActors(characters)
            //this.recommendations = recommendations

            showStatus = getStatus(showData.status.toString())

            plot = description?.replace(Regex("""<(.*?)>"""), "")
        }
    }

    private val embedBlackList = listOf(
        "https://mp4upload.com/",
        "https://streamsb.net/",
        "https://dood.to/",
        "https://videobin.co/",
        "https://ok.ru",
        "https://streamlare.com",
        "streaming.php",
    )

    private fun embedIsBlacklisted(url: String): Boolean {
        embedBlackList.forEach {
            if (it.javaClass.name == "kotlin.text.Regex") {
                if ((it as Regex).matches(url)) {
                    return true
                }
            } else {
                if (url.contains(it)) {
                    return true
                }
            }
        }
        return false
    }

    private fun String.sanitize(): String {
        var out = this
        listOf(Pair("\\u002F", "/")).forEach {
            out = out.replace(it.first, it.second)
        }
        return out
    }

    private data class Links(
        @JsonProperty("link") val link: String,
        @JsonProperty("hls") val hls: Boolean?,
        @JsonProperty("resolutionStr") val resolutionStr: String,
        @JsonProperty("src") val src: String?
    )

    private data class AllAnimeVideoApiResponse(
        @JsonProperty("links") val links: List<Links>
    )

    private data class ApiEndPoint(
        @JsonProperty("episodeIframeHead") val episodeIframeHead: String
    )

    private suspend fun getM3u8Qualities(
        m3u8Link: String,
        referer: String,
        qualityName: String,
    ): List<ExtractorLink> {
        return M3u8Helper.generateM3u8(
            this.name,
            m3u8Link,
            referer,
            name = "${this.name} - $qualityName"
        )
    }

    data class AllAnimeLoadData(
        val hash: String,
        val dubStatus: String,
        val episode: Int
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<AllAnimeLoadData>(data)
        var apiEndPoint =
            parseJson<ApiEndPoint>(app.get("$mainUrl/getVersion").text).episodeIframeHead
        if (apiEndPoint.endsWith("/")) apiEndPoint =
            apiEndPoint.slice(0 until apiEndPoint.length - 1)

        val apiUrl =
            """$apiUrl/allanimeapi?variables={"showId":"${loadData.hash}","translationType":"${loadData.dubStatus}","episodeString":"${loadData.episode}"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"1f0a5d6c9ce6cd3127ee4efd304349345b0737fbf5ec33a60bbc3d18e3bb7c61"}}"""
        val apiResponse = app.get(apiUrl).parsed<LinksQuery>()

        apiResponse.data?.episode?.sourceUrls?.apmap { source ->
            safeApiCall {
                val link = source.sourceUrl?.replace(" ", "%20") ?: return@safeApiCall
                if (URI(link).isAbsolute || link.startsWith("//")) {
                    val fixedLink = if (link.startsWith("//")) "https:$link" else link
                    val sourceName = source.sourceName ?: URI(link).host

                    if (embedIsBlacklisted(fixedLink)) {
                        loadExtractor(fixedLink, subtitleCallback, callback)
                    } else if (URI(fixedLink).path.contains(".m3u")) {
                        getM3u8Qualities(fixedLink, mainUrl, sourceName).forEach(callback)
                    } else {
                        callback(
                            ExtractorLink(
                                name,
                                sourceName,
                                fixedLink,
                                mainUrl,
                                Qualities.P1080.value,
                                false
                            )
                        )
                    }
                } else {
                    val fixedLink = apiEndPoint + URI(link).path + ".json?" + URI(link).query
                    val links = app.get(fixedLink).parsedSafe<AllAnimeVideoApiResponse>()?.links
                        ?: emptyList()
                    links.forEach { server ->
                        if (server.hls != null && server.hls) {
                            getM3u8Qualities(
                                server.link,
                                "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                    server.link
                                ).path),
                                server.resolutionStr
                            ).forEach(callback)
                        } else {
                            callback(
                                ExtractorLink(
                                    "AllAnime - " + URI(server.link).host,
                                    server.resolutionStr,
                                    server.link,
                                    "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                        server.link
                                    ).path),
                                    Qualities.P1080.value,
                                    false
                                )
                            )
                        }
                    }
                }
            }
        }
        return true
    }

}
