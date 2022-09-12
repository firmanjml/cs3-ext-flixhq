package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URI
import java.net.URLDecoder


class AllAnimeProvider : MainAPI() {
    override var mainUrl = "https://allanime.site"
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
        @JsonProperty("description") val description: String?,
        @JsonProperty("status") val status: String?,
    )

    data class AiredStart(
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
        @JsonProperty("data") val data: DataRan? = DataRan()
    )

    data class DataRan(
        @JsonProperty("queryRandomRecommendation") val queryRandomRecommendation: ArrayList<QueryRandomRecommendation> = arrayListOf()
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
//            Pair(
//                "Top Anime",
//                """$mainUrl/graphql?variables={"type":"anime","size":30,"dateRange":30}&extensions={"persistedQuery":{"version":1,"sha256Hash":"276d52ba09ca48ce2b8beb3affb26d9d673b22f9d1fd4892aaa39524128bc745"}}"""
//            ),
            // "countryOrigin":"JP" for Japanese only
            Pair(
                "Recently updated",
                """$mainUrl/graphql?variables={"search":{"allowAdult":false,"allowUnknown":false},"limit":30,"page":1,"translationType":"dub","countryOrigin":"ALL"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"d2670e3e27ee109630991152c8484fce5ff5e280c523378001f9a23dc1839068"}}"""
            ),
        )

        val random =
            """$mainUrl/graphql?variables={"format":"anime"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"21ac672633498a3698e8f6a93ce6c2b3722b29a216dcca93363bf012c360cd54"}}"""
        val ranlink = app.get(random).text
        val jsonran = parseJson<RandomMain>(ranlink)
        val ranhome = jsonran.data?.queryRandomRecommendation?.map {
            newAnimeSearchResponse(it.name!!, "$mainUrl/anime/${it.Id}", fix = false) {
                this.posterUrl = it.thumbnail
                this.otherName = it.nativeName
            }
        }

        items.add(HomePageList("Random", ranhome!!))

        urls.apmap { (HomeName, url) ->
            val test = app.get(url).text
            val json = parseJson<AllAnimeQuery>(test)
            val home = ArrayList<SearchResponse>()
            val results = json.data.shows.edges.filter {
                // filtering in case there is an anime with 0 episodes available on the site.
                !(it.availableEpisodes?.raw == 0 && it.availableEpisodes.sub == 0 && it.availableEpisodes.dub == 0)
            }
            results.map {
                home.add(
                    newAnimeSearchResponse(it.name, "$mainUrl/anime/${it.Id}", fix = false) {
                        this.posterUrl = it.thumbnail
                        this.year = it.airedStart?.year
                        this.otherName = it.englishName
                        addDub(it.availableEpisodes?.dub)
                        addSub(it.availableEpisodes?.sub)
                    })
            }
            items.add(HomePageList(HomeName, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link =
            """	$mainUrl/graphql?variables={"search":{"allowAdult":false,"allowUnknown":false,"query":"$query"},"limit":26,"page":1,"translationType":"dub","countryOrigin":"ALL"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"d2670e3e27ee109630991152c8484fce5ff5e280c523378001f9a23dc1839068"}}"""
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

    data class PageDataRoot(
        @JsonProperty("data") val data: PageData? = PageData()
    )

    data class PageData(
        @JsonProperty("show") val show: Show? = Show()
    )

    data class Show(
        @JsonProperty("_id") val Id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("thumbnails") val thumbnails: ArrayList<String> = arrayListOf(),
//  @JsonProperty("lastEpisodeInfo"         ) val lastEpisodeInfo         : LastEpisodeInfo?         = LastEpisodeInfo(),
//  @JsonProperty("lastEpisodeDate"         ) val lastEpisodeDate         : LastEpisodeDate?         = LastEpisodeDate(),
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("genres") val genres: ArrayList<String> = arrayListOf(),
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("status") val status: String? = null,
//  @JsonProperty("season"                  ) val season                  : Season?                  = Season(),
        @JsonProperty("altNames") val altNames: ArrayList<String> = arrayListOf(),
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("episodeCount") val episodeCount: String? = null,
        @JsonProperty("episodeDuration") val episodeDuration: String? = null,
        @JsonProperty("broadcastInterval") val broadcastInterval: String? = null,
        @JsonProperty("banner") val banner: String? = null,
//  @JsonProperty("airedEnd"                ) val airedEnd                : AiredEnd?                = AiredEnd(),
        @JsonProperty("airedStart") val airedStart: AiredStart? = null,
        @JsonProperty("studios") val studios: ArrayList<String> = arrayListOf(),
        @JsonProperty("countryOfOrigin") val countryOfOrigin: String? = null,
        @JsonProperty("characters") val characters: ArrayList<Characters> = arrayListOf(),
//  @JsonProperty("availableEpisodesDetail" ) val availableEpisodesDetail : AvailableEpisodesDetail? = AvailableEpisodesDetail(),
        @JsonProperty("availableEpisodes") val availableEpisodes: AvailableEpisodes? = null,
        @JsonProperty("prevideos") val prevideos: ArrayList<String> = arrayListOf(),
        @JsonProperty("nameOnlyString") val nameOnlyString: String? = null,
//  @JsonProperty("relatedShows"            ) val relatedShows            : ArrayList<RelatedShows>  = arrayListOf(),
//  @JsonProperty("relatedMangas"           ) val relatedMangas           : ArrayList<RelatedMangas> = arrayListOf(),
//  @JsonProperty("musics"                  ) val musics                  : ArrayList<Musics>        = arrayListOf(),
        @JsonProperty("isAdult") val isAdult: Boolean? = null,
        @JsonProperty("tags") val tags: ArrayList<String> = arrayListOf(),
//  @JsonProperty("pageStatus"              ) val pageStatus              : PageStatus?              = PageStatus(),
        @JsonProperty("__typename") val _typename: String? = null
    )

    data class AvailableEpisodes(
        @JsonProperty("sub") val sub: Int,
        @JsonProperty("dub") val dub: Int,
        @JsonProperty("raw") val raw: Int
    )

    data class Characters(
        @JsonProperty("role") val role: String? = null,
        @JsonProperty("name") val name: Name? = Name(),
        @JsonProperty("image") val image: Image? = Image(),
        @JsonProperty("aniListId") val aniListId: Int? = null,
        @JsonProperty("voiceActors") val voiceActors: ArrayList<VoiceActors> = arrayListOf()
    )

    data class Name(
        @JsonProperty("full") val full: String? = null,
        @JsonProperty("native") val native: String? = null
    )

    data class Image(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null
    )

    data class VoiceActors(
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("aniListId") val aniListId: Int? = null
    )

    data class AllAnimeEpisode(
        val id: String,
        val episodeNumber: Int,
        val type: String
    ) {
        val url =
            """https://allanime.site/allanimeapi?variables={"showId":"$id","translationType":"$type","episodeString":"$episodeNumber"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"29f49ce1a69320b2ab11a475fd114e5c07b03a7dc683f77dd502ca42b26df232"}}"""
    }

    override suspend fun load(url: String): LoadResponse? {
        // Needs to be backwards compatible!
        val idRegex = Regex("""[^\/]*$""")
        val id = idRegex.find(url)?.value ?: throw RuntimeException("Unable to find ID from $url")
        val apiUrl =
            """https://allanime.site/allanimeapi?variables={"_id":"$id"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"af4b72c51f94ed3b1bd6405ab279881ad84b3ba519ebc2382a1736d34c3c1bf6"}}"""

        val showRoot = app.get(apiUrl).parsed<PageDataRoot>()
        val showData = showRoot.data?.show ?: throw RuntimeException("No data found for $showRoot")

        val title = showData.name
        val description = showData.description
        val poster = showData.thumbnail

        val episodes = showData.availableEpisodes.let {
            if (it == null) return@let Pair(null, null)
            Pair(if (it.sub != 0) ((1..it.sub).map { epNum ->
                Episode(
                    AllAnimeEpisode(id, epNum, "sub").url, episode = epNum
                )
            }) else null, if (it.dub != 0) ((1..it.dub).map { epNum ->
                Episode(
                    AllAnimeEpisode(id, epNum, "dub").url, episode = epNum
                )
            }) else null)
        }

        val characters = showData.characters.mapNotNull {
            val img = it.image?.large ?: it.image?.medium ?: return@mapNotNull null
            val name = it.name?.full ?: it.name?.native ?: return@mapNotNull null
            val role = when (it.role?.trim()) {
                "Main" -> ActorRole.Main
                "Supporting" -> ActorRole.Supporting
                "Background" -> ActorRole.Background
                else -> null
            }
            Pair(Actor(name, img), role)
        }

        // bruh, they use graphql
        //val recommendations = soup.select("#suggesction > div > div.p > .swipercard")?.mapNotNull {
        //    val recTitle = it?.selectFirst(".showname > a") ?: return@mapNotNull null
        //    val recName = recTitle.text() ?: return@mapNotNull null
        //    val href = fixUrlNull(recTitle.attr("href")) ?: return@mapNotNull null
        //    val img = it.selectFirst(".image > img").attr("src") ?: return@mapNotNull null
        //    AnimeSearchResponse(recName, href, this.name, TvType.Anime, img)
        //}

        return newAnimeLoadResponse(title ?: "", url, TvType.Anime) {
            posterUrl = poster
            year = showData.airedStart?.year

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

    // We support tracks now!
    private suspend fun getM3u8Qualities(
        m3u8Link: String,
        referer: String,
        sourceName: String,
        qualityString: String?,
    ): List<ExtractorLink> {
        return listOf(
            ExtractorLink(
                this.name,
                sourceName,
                m3u8Link,
                referer,
                getQualityFromName(qualityString),
                isM3u8 = true
            )
        )
//        return M3u8Helper.generateM3u8(
//            this.name,
//            m3u8Link,
//            referer,
//            name = "${this.name} - sourceName"
//        )
    }


    data class LinkData(
        @JsonProperty("data") val data: EpisodeData? = EpisodeData()
    )

    data class SourceUrls(
        @JsonProperty("sourceUrl") val sourceUrl: String? = null,
        @JsonProperty("priority") val priority: Double? = null,
        @JsonProperty("sourceName") val sourceName: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("className") val className: String? = null,
        @JsonProperty("streamerId") val streamerId: String? = null
    )

    data class EpisodeData(
        @JsonProperty("episode") val episode: Episode? = Episode()
    )

    data class Episode(
        @JsonProperty("episodeString") val episodeString: String? = null,
//  @JsonProperty("uploadDate"    ) val uploadDate    : UploadDate?           = UploadDate(),
        @JsonProperty("sourceUrls") val sourceUrls: ArrayList<SourceUrls> = arrayListOf(),
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("notes") val notes: String? = null,
        @JsonProperty("show") val show: Show? = Show(),
//  @JsonProperty("pageStatus"    ) val pageStatus    : PageStatus?           = PageStatus(),
        @JsonProperty("versionFix") val versionFix: String? = null,
        @JsonProperty("__typename") val _typename: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var apiEndPoint =
            parseJson<ApiEndPoint>(app.get("$mainUrl/getVersion").text).episodeIframeHead
        if (apiEndPoint.endsWith("/")) apiEndPoint =
            apiEndPoint.slice(0 until apiEndPoint.length - 1)

        val sources = app.get(data).parsed<LinkData>().data?.episode?.sourceUrls

        sources?.apmap {
            safeApiCall {
                var link = it.sourceUrl?.replace(" ", "%20") ?: return@safeApiCall
                if (URI(link).isAbsolute || link.startsWith("//")) {
                    if (link.startsWith("//")) link = "https:$it"

                    if (Regex("""streaming\.php\?""").matches(link)) {
                        // for now ignore
                    } else if (!embedIsBlacklisted(link)) {
                        if (URI(link).path.contains(".m3u")) {
                            getM3u8Qualities(
                                link,
                                data,
                                it.sourceName ?: URI(link).host,
                                null
                            ).forEach(callback)
                        } else {
                            callback(
                                ExtractorLink(
                                    "AllAnime - " + URI(link).host,
                                    it.sourceName ?: "",
                                    link,
                                    data,
                                    Qualities.P1080.value,
                                    false
                                )
                            )
                        }
                    }
                } else {
                    link = apiEndPoint + URI(link).path + ".json?" + URI(link).query
                    val response = app.get(link)

                    if (response.code < 400) {
                        val links = parseJson<AllAnimeVideoApiResponse>(response.text).links
                        links.forEach { server ->
                            if (server.hls != null && server.hls) {
                                getM3u8Qualities(
                                    server.link,
                                    "$apiEndPoint/player?uri=" + (if (URI(server.link).host.isNotEmpty()) server.link else apiEndPoint + URI(
                                        server.link
                                    ).path),
                                    it.sourceName ?: server.resolutionStr,
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
        }
        return true
    }

}
