package com.yairsarig.pinlink.claude

import com.yairsarig.pinlink.PinConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What happened when we fired the routine. */
sealed class FireResult {
    data class Ok(val sessionUrl: String?) : FireResult()
    /** 429: the routine's or account's hourly limit. The hourly task is the backup. */
    data class RateLimited(val retryAfterSec: Long?) : FireResult()
    /** 400/401/403/404: retrying can't help until the URL/token in Settings change. */
    data class Rejected(val code: Int, val message: String) : FireResult()
    /** Network trouble or 5xx: worth retrying. */
    data class Failed(val message: String) : FireResult()
    data object NotConfigured : FireResult()
    /** Our own [PinConfig.MAX_FIRES_PER_HOUR] cap. */
    data object Capped : FireResult()
}

/**
 * POSTs to a Claude Code routine's fire endpoint. The bearer token is per-routine and can only
 * start that routine. Neither the URL nor the token is ever logged.
 */
object RoutineClient {

    fun fire(url: String, token: String, text: String): FireResult {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return FireResult.Rejected(0, "Routine URL is not valid")
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = PinConfig.HTTP_TIMEOUT_MS
            conn.readTimeout = PinConfig.HTTP_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("anthropic-version", PinConfig.ROUTINE_API_VERSION)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            interpret(code, body, conn.getHeaderField("Retry-After"))
        } catch (e: IOException) {
            FireResult.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** Pure: status + body -> result. */
    fun interpret(code: Int, body: String, retryAfter: String?): FireResult = when (code) {
        in 200..299 -> FireResult.Ok(runCatching { JSONObject(body).optString("claude_code_session_url").ifBlank { null } }.getOrNull())
        429 -> FireResult.RateLimited(retryAfter?.trim()?.toLongOrNull())
        400, 401, 403, 404 -> FireResult.Rejected(code, errorMessage(body) ?: "HTTP $code")
        else -> FireResult.Failed("HTTP $code" + (errorMessage(body)?.let { ": $it" } ?: ""))
    }

    private fun errorMessage(body: String): String? =
        runCatching { JSONObject(body).getJSONObject("error").optString("message").ifBlank { null } }.getOrNull()
}
