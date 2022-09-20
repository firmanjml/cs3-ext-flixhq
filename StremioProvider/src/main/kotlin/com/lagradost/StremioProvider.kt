package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

class StremioProvider : MainAPI() {
    override var mainUrl = "https://stremio.github.io/stremio-static-addon-example"
    override var name = "Stremio example"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val res = tryParseJson<Manifest>(app.get("${mainUrl}/manifest.json").text) ?: return null
        val lists = mutableListOf<HomePageList>()
        res.catalogs.forEach { catalog ->
            catalog.toHomePageList(this)?.let {
                lists.add(it)
            }
        }
        return HomePageResponse(
            lists,
            false
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = tryParseJson<CatalogEntry>(url) ?: throw RuntimeException(url)
        return res.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = tryParseJson<StreamsResponse>(app.get(data).text) ?: return false
        res.streams.forEach { stream ->
            stream.runCallback(subtitleCallback, callback)
        }
        return true
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf()
    ) {
        init {
            if (type != null) types.add(type)
        }

        suspend fun toHomePageList(provider: StremioProvider): HomePageList? {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val res = tryParseJson<CatalogResponse>(app.get("${provider.mainUrl}/catalog/$type/$id.json").text) ?: return@forEach
                res.metas.forEach {  entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return HomePageList(
                name ?: id,
                entries
            )
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>)
    private data class CatalogEntry(
        val name: String,
        val id: String,
        val poster: String?,
        val description: String?,
        val type: String?
    ) {
        fun toSearchResponse(provider: StremioProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                name,
                this.toJson(),
                type?.let { getType(it) } ?: TvType.Others
            ) {
                posterUrl = poster
            }
        }
        suspend fun toLoadResponse(provider: StremioProvider): LoadResponse {
            return provider.newMovieLoadResponse(
                name,
                "${provider.mainUrl}/meta/$type/$id.json",
                getType(type),
                "${provider.mainUrl}/stream/$type/$id.json"
            ) {
                posterUrl = poster
                plot = description
            }
        }
    }

    private data class StreamsResponse(val streams: List<Stream>)
    private data class Stream(
        val name: String?,
        val title: String?,
        val url: String?,
        val ytId: String?,
        val externalUrl: String?,
        val behaviorHints: JSONObject?
    ) {
        suspend fun runCallback(subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            if (url != null) {
                var referer: String? = null
                try {
                    val headers = ((behaviorHints?.get("proxyHeaders") as? JSONObject)
                        ?.get("request") as? JSONObject)
                    referer = headers?.get("referer") as? String ?: headers?.get("origin") as? String
                } catch (ex: Throwable) {
                    Log.e("Stremio", Log.getStackTraceString(ex))
                }

                if (url.endsWith(".m3u8")) {
                    callback.invoke(
                        ExtractorLink(
                        name ?: "",
                        title ?: name ?: "",
                        url,
                            referer ?: "",
                        Qualities.Unknown.value,
                        isM3u8 = true
                    ))
                } else {
                    callback.invoke(
                        ExtractorLink(
                            name ?: "",
                            title ?: name ?: "",
                            url,
                            referer ?: "",
                            Qualities.Unknown.value,
                            isM3u8 = false
                        ))
                }
            }
            if (ytId != null) {
                loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            }
            if (externalUrl != null) {
                loadExtractor(externalUrl, subtitleCallback, callback)
            }
        }
    }

    companion object {
        private val typeAliases = hashMapOf(
            "tv" to TvType.TvSeries,
            "series" to TvType.TvSeries,
            "channel" to TvType.Live,
            "adult" to TvType.NSFW
        )

        fun getType(t_str: String?): TvType {
            if (t_str == null) return TvType.Others
            typeAliases[t_str.lowercase()]?.let {
                return it
            }
            for (t_enum in TvType.values()) {
                if (t_enum.toString().lowercase() == t_str.lowercase())
                    return t_enum
            }
            return TvType.Others
        }
    }
}
