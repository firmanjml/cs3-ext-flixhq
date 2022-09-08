package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class SuperembedProvider : TmdbProvider() {
    override var mainUrl = "https://seapi.link"
    override val apiName = "Superembed"
    override var name = "Superembed"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mappedData = tryParseJson<TmdbLink>(data)
        val tmdbId = mappedData?.tmdbID ?: return false

        val document = app.get("https://seapi.link/?type=tmdb&id=${tmdbId}&max_results=1").text
        val response = tryParseJson<ApiResponse>(document) ?: return false

        response.results.forEach {
            it.getIframeContents()?.let { it1 ->
                Log.d("supaembed", it1)
                loadExtractor(it1, subtitleCallback, callback)
            }
        }

        return true
    }

    private data class ApiResponse(
        val results: List<ApiResultItem>
    )

    private data class ApiResultItem(
        val server: String,
        val title: String,
        val quality: String,
        val size: Int,
        val url: String
    ) {
        suspend fun getIframeContents(): String? {
            val document = app.get(url).text
            val regex = "<iframe[^+]+\\+(?:window\\.)?atob\\(['\"]([-A-Za-z0-9+/=]+)".toRegex()
            val encoded = regex.find(document)?.groupValues?.get(1) ?: return null
            return base64Decode(encoded)
        }
    }
}