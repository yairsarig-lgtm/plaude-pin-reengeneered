package com.yairsarig.pinlink.upload

import com.yairsarig.pinlink.PinConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A Google API answered with a non-2xx status. [DriveSession] treats 401 as "token stale/revoked". */
class GoogleHttpException(val code: Int, body: String) : IOException("Google API HTTP $code: ${body.take(300)}")

/** Bearer-token HTTP for Google REST APIs (Drive, Sheets). */
class GoogleHttp(private val accessToken: String) {

    fun request(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = "application/json",
        methodOverride: String? = null,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            // HttpURLConnection can't send PATCH; Google accepts POST + override.
            methodOverride?.let { conn.setRequestProperty("X-HTTP-Method-Override", it) }
            conn.connectTimeout = PinConfig.HTTP_TIMEOUT_MS
            conn.readTimeout = PinConfig.HTTP_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GoogleHttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}
