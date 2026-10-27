package com.yairsarig.pinlink.upload

import com.yairsarig.pinlink.PinConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Drive v3 REST client: find/create the inbox folder, check for a file, upload text.
 * With the drive.file scope it only ever sees files and folders this app created.
 */
class DriveClient(private val accessToken: String) {

    class HttpException(val code: Int, body: String) : IOException("Drive HTTP $code: ${body.take(300)}")

    /** Returns the id of a live (non-trashed) folder named [name] in My Drive, creating it if needed. */
    fun findOrCreateFolder(name: String, cachedId: String?): String {
        cachedId?.let { id ->
            val meta = runCatching { JSONObject(request("GET", "$FILES/$id?fields=id,trashed")) }.getOrNull()
            if (meta != null && !meta.optBoolean("trashed")) return id
        }
        val q = "name = '${escape(name)}' and mimeType = '$FOLDER_MIME' and trashed = false and 'root' in parents"
        findFirst(q)?.let { return it }
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(request("POST", "$FILES?fields=id", body, "application/json")).getString("id")
    }

    fun findFile(folderId: String, name: String): String? =
        findFirst("name = '${escape(name)}' and '$folderId' in parents and trashed = false")

    /** Multipart upload of a small text file. Returns the new file id. */
    fun uploadText(folderId: String, name: String, text: String): String {
        val meta = JSONObject().put("name", name).put("parents", listOf(folderId)).put("mimeType", "text/plain").toString()
        val boundary = "pinlink-${System.nanoTime()}"
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
            append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$text\r\n")
            append("--$boundary--\r\n")
        }
        return JSONObject(request("POST", "$UPLOAD?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary"))
            .getString("id")
    }

    private fun findFirst(q: String): String? {
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&pageSize=1&spaces=drive"
        val files = JSONObject(request("GET", url)).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun request(method: String, url: String, body: String? = null, contentType: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
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
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
