package com.yairsarig.pinlink.upload

import com.yairsarig.pinlink.PinConfig
import org.json.JSONArray
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

    /**
     * Returns the id of a live (non-trashed) folder named [name], wherever it is (the owner moves it
     * into "Pin Assistant"; drive.file only ever shows us folders this app created), creating one in
     * My Drive root only if none exists.
     */
    fun findOrCreateFolder(name: String, cachedId: String?): String {
        cachedId?.let { id ->
            val meta = runCatching { JSONObject(request("GET", "$FILES/$id?fields=id,trashed")) }.getOrNull()
            if (meta != null && !meta.optBoolean("trashed")) return id
        }
        val q = "name = '${escape(name)}' and mimeType = '$FOLDER_MIME' and trashed = false"
        findFirst(q)?.let { return it }
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(request("POST", "$FILES?fields=id", body, "application/json")).getString("id")
    }

    fun findFile(folderId: String, name: String): String? =
        findFirst("name = '${escape(name)}' and '$folderId' in parents and trashed = false")

    /** Multipart upload of a small text file. Returns the new file id. */
    fun uploadText(folderId: String, name: String, text: String): String {
        val meta = uploadMetadata(folderId, name).toString()
        val boundary = "pinlink-${System.nanoTime()}"
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
            append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$text\r\n")
            append("--$boundary--\r\n")
        }
        return JSONObject(request("POST", "$UPLOAD?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary"))
            .getString("id")
    }

    /**
     * Makes sure [fileId] lives in [folderId] (and only there). Returns true if it had to be moved.
     * Drive's PATCH goes through POST + X-HTTP-Method-Override; HttpURLConnection can't send PATCH.
     */
    fun ensureInFolder(fileId: String, folderId: String): Boolean {
        val parents = JSONObject(request("GET", "$FILES/$fileId?fields=parents")).optJSONArray("parents") ?: JSONArray()
        val current = (0 until parents.length()).map { parents.getString(it) }
        if (current == listOf(folderId)) return false
        val remove = current.filter { it != folderId }.joinToString(",")
        val url = "$FILES/$fileId?addParents=$folderId" + (if (remove.isNotEmpty()) "&removeParents=$remove" else "") + "&fields=id"
        request("POST", url, "{}", "application/json", methodOverride = "PATCH")
        return true
    }

    private fun findFirst(q: String): String? {
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&pageSize=1&spaces=drive"
        val files = JSONObject(request("GET", url)).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun request(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        methodOverride: String? = null,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
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
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        /** `parents` must be a JSON array; org.json turns a Kotlin List into the string "[id]". */
        fun uploadMetadata(folderId: String, name: String): JSONObject =
            JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).put("mimeType", "text/plain")

        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
