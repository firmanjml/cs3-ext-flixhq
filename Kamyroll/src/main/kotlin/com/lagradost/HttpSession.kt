package com.lagradost
//Credits https://github.com/ArjixWasTaken/CloudStream-3/blob/master/app/src/main/java/com/ArjixWasTaken/cloudstream3/utils/HttpSession.kt
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse

/**
 * An HTTP session manager.
 *
 * This class simply keeps cookies across requests.
 *
 * @property sessionCookies A cookie jar.
 *
 * TODO: be replaced with built in Session once that works as it should.
 */
class HttpSession {
    private val sessionCookies: MutableMap<String, String> = mutableMapOf()

    suspend fun get(
        url: String,
        headers: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
    ): NiceResponse {
        sessionCookies.putAll(cookies)
        val res =
            app.get(
                url,
                headers,
                cookies = sessionCookies,
            )
        sessionCookies.putAll(res.headers.filter { it.first.lowercase() == "set-cookie" })
        return res
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
    ): NiceResponse {
        sessionCookies.putAll(cookies)
        val res =
            app.post(
                url,
                headers,
                cookies = sessionCookies,
            )

        sessionCookies.putAll(res.headers.filter { it.first.lowercase() == "set-cookie" })
        return res
    }
}