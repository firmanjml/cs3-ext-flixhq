package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

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
            val document = app.get(url)
            val regex = "<iframe[^+]+\\+(?:window\\.)?atob\\(['\"]([-A-Za-z0-9+/=]+)".toRegex()
            val encoded = regex.find(document.text)?.groupValues?.get(1) ?: return null
            return base64Decode(encoded)
        }
    }

    private object CaptchaSolver {
        private enum class Gender { Female, Male }
        private suspend fun predictFace(url: String): Gender? {
            val img = "data:image/jpeg;base64," + base64Encode(app.get(url).body.bytes())
            val res = app.post("https://hf.space/embed/njgroene/age-gender-profilepic/api/queue/push/ HTTP/1.1", json = HFRequest(
                listOf(img))).text
            val request = tryParseJson<JSONObject>(res)
            for (i in 1..5) {
                delay(500L)
                val document = app.post("https://hf.space/embed/njgroene/age-gender-profilepic/api/queue/status/", json=request).text
                val status = tryParseJson<JSONObject>(document)
                if (status?.get("status") != "COMPLETE") continue
                val pred = (((status.get("data") as? JSONObject?)
                    ?.get("data") as? JSONArray?)
                    ?.get(0) as? String?) ?: return null
                return if ("Male" in pred) Gender.Male
                else if ("Female" in pred) Gender.Female
                else null
            }
        }

        private data class HFRequest(
            val data: List<String>,
            val action: String = "predict",
            val fn_index: Int = 0,
            val session_hash: String = "aaaaaaaaaaa"
        )
    }
}