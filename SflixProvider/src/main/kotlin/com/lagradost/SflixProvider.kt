package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.delay
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class SflixProvider : MainAPI() {
    override var mainUrl = "https://sflix.to"
    override var name = "Sflix.to"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.None

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val document = Jsoup.parse(html)

        val all = ArrayList<HomePageList>()

        val map = mapOf(
            "Trending Movies" to "div#trending-movies",
            "Trending TV Shows" to "div#trending-tv",
        )
        map.forEach {
            all.add(HomePageList(
                it.key,
                document.select(it.value).select("div.flw-item").map { element ->
                    element.toSearchResult()
                }
            ))
        }

        document.select("section.block_area.block_area_home.section-id-02").forEach {
            val title = it.select("h2.cat-heading").text().trim()
            val elements = it.select("div.flw-item").map { element ->
                element.toSearchResult()
            }
            all.add(HomePageList(title, elements))
        }

        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select("div.flw-item").map {
            val title = it.select("h2.film-name").text()
            val href = fixUrl(it.select("a").attr("href"))
            val year = it.select("span.fdi-item").text().toIntOrNull()
            val image = it.select("img").attr("data-src")
            val isMovie = href.contains("/movie/")

            val metaInfo = it.select("div.fd-infor > span.fdi-item")
            // val rating = metaInfo[0].text()
            val quality = getQualityFromString(metaInfo.getOrNull(1)?.text())

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    year,
                    quality = quality
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    year,
                    null,
                    quality = quality
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val details = document.select("div.detail_page-watch")
        val img = details.select("img.film-poster-img")
        val posterUrl = img.attr("src")
        val title = img.attr("title") ?: throw ErrorLoadingException("No Title")

        /*
        val year = Regex("""[Rr]eleased:\s*(\d{4})""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val duration = Regex("""[Dd]uration:\s*(\d*)""").find(
            document.select("div.elements").text()
        )?.groupValues?.get(1)?.trim()?.plus(" min")*/
        var duration = document.selectFirst(".fs-item > .duration")?.text()?.trim()
        var year: Int? = null
        var tags: List<String>? = null
        var cast: List<String>? = null
        val youtubeTrailer = document.selectFirst("iframe#iframe-trailer")?.attr("data-src")
        val rating = document.selectFirst(".fs-item > .imdb")?.text()?.trim()
            ?.removePrefix("IMDB:")?.toRatingInt()

        document.select("div.elements > .row > div > .row-line").forEach { element ->
            val type = element?.select(".type")?.text() ?: return@forEach
            when {
                type.contains("Released") -> {
                    year = Regex("\\d+").find(
                        element.ownText() ?: return@forEach
                    )?.groupValues?.firstOrNull()?.toIntOrNull()
                }
                type.contains("Genre") -> {
                    tags = element.select("a").mapNotNull { it.text() }
                }
                type.contains("Cast") -> {
                    cast = element.select("a").mapNotNull { it.text() }
                }
                type.contains("Duration") -> {
                    duration = duration ?: element.ownText().trim()
                }
            }
        }
        val plot = details.select("div.description").text().replace("Overview:", "").trim()

        val isMovie = url.contains("/movie/")

        // https://sflix.to/movie/free-never-say-never-again-hd-18317 -> 18317
        val idRegex = Regex(""".*-(\d+)""")
        val dataId = details.attr("data-id")
        val id = if (dataId.isNullOrEmpty())
            idRegex.find(url)?.groupValues?.get(1)
                ?: throw ErrorLoadingException("Unable to get id from '$url'")
        else dataId

        val recommendations =
            document.select("div.film_list-wrap > div.flw-item").mapNotNull { element ->
                val titleHeader =
                    element.select("div.film-detail > .film-name > a") ?: return@mapNotNull null
                val recUrl = fixUrlNull(titleHeader.attr("href")) ?: return@mapNotNull null
                val recTitle = titleHeader.text() ?: return@mapNotNull null
                val poster = element.select("div.film-poster > img").attr("data-src")
                MovieSearchResponse(
                    recTitle,
                    recUrl,
                    this.name,
                    if (recUrl.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                    poster,
                    year = null
                )
            }

        if (isMovie) {
            // Movies
            val episodesUrl = "$mainUrl/ajax/movie/episodes/$id"
            val episodes = app.get(episodesUrl).text

            // Supported streams, they're identical
            val sourceIds = Jsoup.parse(episodes).select("a").mapNotNull { element ->
                var sourceId = element.attr("data-id")
                if (sourceId.isNullOrEmpty())
                    sourceId = element.attr("data-linkid")

                if (element.select("span").text().trim().isValidServer()) {
                    if (sourceId.isNullOrEmpty()) {
                        fixUrlNull(element.attr("href"))
                    } else {
                        "$url.$sourceId".replace("/movie/", "/watch-movie/")
                    }
                } else {
                    null
                }
            }

            val comingSoon = sourceIds.isEmpty()

            return newMovieLoadResponse(title, url, TvType.Movie, sourceIds) {
                this.year = year
                this.posterUrl = posterUrl
                this.plot = plot
                addDuration(duration)
                addActors(cast)
                this.tags = tags
                this.recommendations = recommendations
                this.comingSoon = comingSoon
                addTrailer(youtubeTrailer)
                this.rating = rating
            }
        } else {
            val seasonsDocument = app.get("$mainUrl/ajax/v2/tv/seasons/$id").document
            val episodes = arrayListOf<Episode>()
            var seasonItems = seasonsDocument.select("div.dropdown-menu.dropdown-menu-model > a")
            if (seasonItems.isNullOrEmpty())
                seasonItems = seasonsDocument.select("div.dropdown-menu > a.dropdown-item")
            seasonItems.apmapIndexed { season, element ->
                val seasonId = element.attr("data-id")
                if (seasonId.isNullOrBlank()) return@apmapIndexed

                var episode = 0
                val seasonEpisodes = app.get("$mainUrl/ajax/v2/season/episodes/$seasonId").document
                var seasonEpisodesItems =
                    seasonEpisodes.select("div.flw-item.film_single-item.episode-item.eps-item")
                if (seasonEpisodesItems.isNullOrEmpty()) {
                    seasonEpisodesItems =
                        seasonEpisodes.select("ul > li > a")
                }
                seasonEpisodesItems.forEach {
                    val episodeImg = it?.select("img")
                    val episodeTitle = episodeImg?.attr("title") ?: it.ownText()
                    val episodePosterUrl = episodeImg?.attr("src")
                    val episodeData = it.attr("data-id") ?: return@forEach

                    episode++

                    val episodeNum =
                        (it.select("div.episode-number").text()
                            ?: episodeTitle).let { str ->
                            Regex("""\d+""").find(str)?.groupValues?.firstOrNull()
                                ?.toIntOrNull()
                        } ?: episode

                    episodes.add(
                        newEpisode(Pair(url, episodeData)) {
                            this.posterUrl = fixUrlNull(episodePosterUrl)
                            this.name = episodeTitle?.removePrefix("Episode $episodeNum: ")
                            this.season = season + 1
                            this.episode = episodeNum
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                addDuration(duration)
                addActors(cast)
                this.tags = tags
                this.recommendations = recommendations
                addTrailer(youtubeTrailer)
                this.rating = rating
            }
        }
    }

    data class Tracks(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )

    data class SourceObject(
        @JsonProperty("sources") val sources: List<Sources?>? = null,
        @JsonProperty("sources_1") val sources1: List<Sources?>? = null,
        @JsonProperty("sources_2") val sources2: List<Sources?>? = null,
        @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>? = null,
        @JsonProperty("tracks") val tracks: List<Tracks?>? = null
    )

    data class SourceObjectEncrypted(
        @JsonProperty("sources") val sources: String?,
        @JsonProperty("encrypted") val encrypted: Boolean?,
        @JsonProperty("sources_1") val sources1: String?,
        @JsonProperty("sources_2") val sources2: String?,
        @JsonProperty("sourcesBackup") val sourcesBackup: String?,
        @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

    data class IframeJson(
//        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
//        @JsonProperty("sources") val sources: ArrayList<String> = arrayListOf(),
//        @JsonProperty("tracks") val tracks: ArrayList<String> = arrayListOf(),
//        @JsonProperty("title") val title: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = (tryParseJson<Pair<String, String>>(data)?.let { (prefix, server) ->
            val episodesUrl = "$mainUrl/ajax/v2/episode/servers/$server"

            // Supported streams, they're identical
            app.get(episodesUrl).document.select("a").mapNotNull { element ->
                val id = element?.attr("data-id") ?: return@mapNotNull null
                if (element.select("span").text().trim().isValidServer()) {
                    "$prefix.$id".replace("/tv/", "/watch-tv/")
                } else {
                    null
                }
            }
        } ?: tryParseJson<List<String>>(data))?.distinct()

        urls?.apmap { url ->
            suspendSafeApiCall {
                // Possible without token

//                val response = app.get(url)
//                val key =
//                    response.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
//                        .attr("src").substringAfter("render=")
//                val token = getCaptchaToken(mainUrl, key) ?: return@suspendSafeApiCall

                val serverId = url.substringAfterLast(".")
                val iframeLink =
                    app.get("${this.mainUrl}/ajax/get_link/$serverId").parsed<IframeJson>().link
                        ?: return@suspendSafeApiCall

                // Some smarter ws11 or w10 selection might be required in the future.
//                val extractorData =
//                    "https://ws11.rabbitstream.net/socket.io/?EIO=4&transport=polling"

                val hasLoadedExtractor = loadExtractor(iframeLink, null, subtitleCallback, callback)
                if (!hasLoadedExtractor) {
                    extractRabbitStream(
                        iframeLink,
                        subtitleCallback,
                        callback,
                    ) { it }
                }
            }
        }

        return !urls.isNullOrEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse {
        val inner = this.selectFirst("div.film-poster")
        val img = inner!!.select("img")
        val title = img.attr("title")
        val posterUrl = img.attr("data-src") ?: img.attr("src")
        val href = fixUrl(inner.select("a").attr("href"))
        val isMovie = href.contains("/movie/")
        val otherInfo =
            this.selectFirst("div.film-detail > div.fd-infor")?.select("span")?.toList() ?: listOf()
        //var rating: Int? = null
        var year: Int? = null
        var quality: SearchQuality? = null
        when (otherInfo.size) {
            1 -> {
                year = otherInfo[0]?.text()?.trim()?.toIntOrNull()
            }
            2 -> {
                year = otherInfo[0]?.text()?.trim()?.toIntOrNull()
            }
            3 -> {
                //rating = otherInfo[0]?.text()?.toRatingInt()
                quality = getQualityFromString(otherInfo[1]?.text())
                year = otherInfo[2]?.text()?.trim()?.toIntOrNull()
            }
        }

        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                this@SflixProvider.name,
                TvType.Movie,
                posterUrl = posterUrl,
                year = year,
                quality = quality,
            )
        } else {
            TvSeriesSearchResponse(
                title,
                href,
                this@SflixProvider.name,
                TvType.Movie,
                posterUrl,
                year = year,
                episodes = null,
                quality = quality,
            )
        }
    }

    companion object {
        data class PollingData(
            @JsonProperty("sid") val sid: String? = null,
            @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
            @JsonProperty("pingInterval") val pingInterval: Int? = null,
            @JsonProperty("pingTimeout") val pingTimeout: Int? = null
        )

        /*
        # python code to figure out the time offset based on code if necessary
        chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        code = "Nxa_-bM"
        total = 0
        for i, char in enumerate(code[::-1]):
            index = chars.index(char)
            value = index * 64**i
            total += value
        print(f"total {total}")
        */
        private fun generateTimeStamp(): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
            var code = ""
            var time = unixTimeMS
            while (time > 0) {
                code += chars[(time % (chars.length)).toInt()]
                time /= chars.length
            }
            return code.reversed()
        }

        fun getSourceObject(responseJson: String?, decryptKey: String?): SourceObject? {
            if (responseJson == null) return null
            return if (decryptKey != null) {
                val encryptedMap = tryParseJson<SourceObjectEncrypted>(responseJson)
                val sources = encryptedMap?.sources

                if (sources == null || encryptedMap.encrypted == false) {
                    tryParseJson(responseJson)
                } else {
                    val decrypted = decryptMapped<List<Sources>>(sources, decryptKey)
                    SourceObject(
                        sources = decrypted,
                        tracks = encryptedMap.tracks
                    )
                }
            } else {
                tryParseJson(responseJson)
            }
        }

        private fun getSources(
            socketUrl: String,
            id: String,
            callback: suspend (Resource<SourceObject>) -> Unit
        ) {
            app.baseClient.newWebSocket(
                requestCreator("GET", socketUrl),
                object : WebSocketListener() {
                    val sidRegex = Regex("""sid.*"(.*?)"""")
                    val sourceRegex = Regex("""\{.*\}""")
                    val codeRegex = Regex("""^\d*""")

                    var key: String? = null

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        ioSafe {
                            callback(Resource.Failure(false, code, null, reason))
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("getSources", "onMessage $text")
                        val code = codeRegex.find(text)?.value?.toIntOrNull() ?: return

                        when (code) {
                            0 -> webSocket.send("40")
                            40 -> {
                                key = sidRegex.find(text)?.groupValues?.get(1)
                                webSocket.send("""42["getSources",{"id":"$id"}]""")
                            }
                            42 -> {
                                val response = sourceRegex.find(text)?.value
                                val sourceObject = getSourceObject(response, key)
                                val resource = if (sourceObject == null)
                                    Resource.Failure(false, null, null, response ?: "")
                                else Resource.Success(sourceObject)
                                ioSafe { callback(resource) }
                                webSocket.close(1005, "41")
                            }
                        }
                    }
                }
            )
        }

        // Only scrape servers with these names
        fun String?.isValidServer(): Boolean {
            val list = listOf("upcloud", "vidcloud", "streamlare")
            return list.contains(this?.lowercase(Locale.ROOT))
        }

        // For re-use in Zoro
        private suspend
        fun Sources.toExtractorLink(
            caller: MainAPI,
            name: String,
            extractorData: String? = null,
        ): List<ExtractorLink>? {
            return this.file?.let { file ->
                //println("FILE::: $file")
                val isM3u8 = URI(this.file).path.endsWith(".m3u8") || this.type.equals(
                    "hls",
                    ignoreCase = true
                )
                return if (isM3u8) {
                    suspendSafeApiCall {
                        M3u8Helper().m3u8Generation(
                            M3u8Helper.M3u8Stream(
                                this.file,
                                null,
                                mapOf("Referer" to "https://mzzcloud.life/")
                            ), false
                        )
                            .map { stream ->
                                ExtractorLink(
                                    caller.name,
                                    "${caller.name} $name",
                                    stream.streamUrl,
                                    caller.mainUrl,
                                    getQualityFromName(stream.quality?.toString()),
                                    true,
                                    extractorData = extractorData
                                )
                            }
                    }.takeIf { !it.isNullOrEmpty() } ?: listOf(
                        // Fallback if m3u8 extractor fails
                        ExtractorLink(
                            caller.name,
                            "${caller.name} $name",
                            this.file,
                            caller.mainUrl,
                            getQualityFromName(this.label),
                            isM3u8,
                            extractorData = extractorData
                        )
                    )
                } else {
                    listOf(
                        ExtractorLink(
                            caller.name,
                            caller.name,
                            file,
                            caller.mainUrl,
                            getQualityFromName(this.label),
                            false,
                            extractorData = extractorData
                        )
                    )
                }
            }
        }

        private fun Tracks.toSubtitleFile(): SubtitleFile? {
            return this.file?.let {
                SubtitleFile(
                    this.label ?: "Unknown",
                    it
                )
            }
        }

        private fun md5(input: ByteArray): ByteArray {
            return MessageDigest.getInstance("MD5").digest(input)
        }

        private fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
            var key = md5(secret + salt)
            var currentKey = key
            while (currentKey.size < 48) {
                key = md5(key + secret + salt)
                currentKey += key
            }
            return currentKey
        }

        private fun decryptSourceUrl(
            decryptionKey: ByteArray,
            sourceUrl: String
        ): String {
            val cipherData = base64DecodeArray(sourceUrl)
            val encrypted = cipherData.copyOfRange(16, cipherData.size)
            val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")

            Objects.requireNonNull(aesCBC).init(
                Cipher.DECRYPT_MODE, SecretKeySpec(
                    decryptionKey.copyOfRange(0, 32),
                    "AES"
                ),
                IvParameterSpec(decryptionKey.copyOfRange(32, decryptionKey.size))
            )
            val decryptedData = aesCBC!!.doFinal(encrypted)
            return String(decryptedData, StandardCharsets.UTF_8)
        }

        private inline
        fun <reified T> decryptMapped(input: String, key: String): T? {
            return tryParseJson(decrypt(input, key))
        }

        private fun decrypt(input: String, key: String): String {
            return decryptSourceUrl(
                generateKey(
                    base64DecodeArray(input).copyOfRange(8, 16),
                    key.toByteArray()
                ), input
            )
        }

        suspend fun MainAPI.extractRabbitStream(
            url: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            nameTransformer: (String) -> String,
        ) = suspendSafeApiCall {
            // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
//            val mainIframeUrl =
//                url.substringBeforeLast("/")

            val mainIframeId = url.substringAfterLast("/")
                .substringBefore("?") // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT

            var isDone = false

            // Hardcoded for now, does not support Zoro yet.
            getSources(
                "wss://wsx.dokicloud.one/socket.io/?EIO=4&transport=websocket",
                mainIframeId
            ) { sourceResource ->
                if (sourceResource !is Resource.Success) {
                    isDone = true
                    return@getSources
                }

                val sourceObject = sourceResource.value

                sourceObject.tracks?.forEach { track ->
                    track?.toSubtitleFile()?.let { subtitleFile ->
                        subtitleCallback.invoke(subtitleFile)
                    }
                }

                val list = listOf(
                    sourceObject.sources to "source 1",
                    sourceObject.sources1 to "source 2",
                    sourceObject.sources2 to "source 3",
                    sourceObject.sourcesBackup to "source backup"
                )

                list.forEach { subList ->
                    subList.first?.forEach { source ->
                        source?.toExtractorLink(
                            this,
                            nameTransformer(subList.second),
                        )?.forEach(callback)
                    }
                }
                isDone = true
            }

            var elapsedTime = 0
            val maxTime = 30

            while (elapsedTime < maxTime && !isDone) {
                elapsedTime++
                delay(1_000)
            }

////            val iframe = app.get(url, referer = mainUrl)
////            val iframeKey =
////                iframe.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
////                    .attr("src").substringAfter("render=")
////            val iframeToken = getCaptchaToken(url, iframeKey)
////            val number =
////                Regex("""recaptchaNumber = '(.*?)'""").find(iframe.text)?.groupValues?.get(1)
//
//            val sid = null
//            val getSourcesUrl = "${
//                mainIframeUrl.replace(
//                    "/embed",
//                    "/ajax/embed"
//                )
//            }/getSources?id=$mainIframeId${sid?.let { "$&sId=$it" } ?: ""}"
//            val response = app.get(
//                getSourcesUrl,
//                referer = mainUrl,
//                headers = mapOf(
//                    "X-Requested-With" to "XMLHttpRequest",
//                    "Accept" to "*/*",
//                    "Accept-Language" to "en-US,en;q=0.5",
//                    "Connection" to "keep-alive",
//                    "TE" to "trailers"
//                )
//            )
//
//            println("Sflix response: $response")
        }
    }
}

