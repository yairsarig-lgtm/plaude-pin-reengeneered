package com.yairsarig.pinlink.upload

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Minimal Drive v3 REST client: find/create the inbox folder, check for a file, upload text.
 * With the drive.file scope it only ever sees files and folders this app created.
 */
class DriveClient(private val http: GoogleHttp) {

    constructor(accessToken: String) : this(GoogleHttp(accessToken))

    /**
     * Returns the id of a live (non-trashed) folder named [name], wherever it is (the owner moves it
     * into "Pin Assistant"; drive.file only ever shows us folders this app created), creating one in
     * My Drive root only if none exists.
     */
    fun findOrCreateFolder(name: String, cachedId: String?): String {
        cachedId?.let { id ->
            val meta = runCatching { JSONObject(http.request("GET", "$FILES/$id?fields=id,trashed")) }.getOrNull()
            if (meta != null && !meta.optBoolean("trashed")) return id
        }
        val q = "name = '${escape(name)}' and mimeType = '$FOLDER_MIME' and trashed = false"
        findFirst(q)?.let { return it }
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(http.request("POST", "$FILES?fields=id", body)).getString("id")
    }

    /** An app-visible (drive.file) spreadsheet called [name], wherever the owner moved it. */
    fun findSpreadsheet(name: String): String? =
        findFirst("name = '${escape(name)}' and mimeType = '$SHEET_MIME' and trashed = false")

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
        return JSONObject(http.request("POST", "$UPLOAD?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary"))
            .getString("id")
    }

    /**
     * Makes sure [fileId] lives in [folderId] (and only there). Returns true if it had to be moved.
     * Drive's PATCH goes through POST + X-HTTP-Method-Override; HttpURLConnection can't send PATCH.
     */
    fun ensureInFolder(fileId: String, folderId: String): Boolean {
        val parents = JSONObject(http.request("GET", "$FILES/$fileId?fields=parents")).optJSONArray("parents") ?: JSONArray()
        val current = (0 until parents.length()).map { parents.getString(it) }
        if (current == listOf(folderId)) return false
        val remove = current.filter { it != folderId }.joinToString(",")
        val url = "$FILES/$fileId?addParents=$folderId" + (if (remove.isNotEmpty()) "&removeParents=$remove" else "") + "&fields=id"
        http.request("POST", url, "{}", methodOverride = "PATCH")
        return true
    }

    private fun findFirst(q: String): String? {
        val url = "$FILES?q=${URLEncoder.encode(q, "UTF-8")}&fields=files(id)&pageSize=1&spaces=drive"
        val files = JSONObject(http.request("GET", url)).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        /** `parents` must be a JSON array; org.json turns a Kotlin List into the string "[id]". */
        fun uploadMetadata(folderId: String, name: String): JSONObject =
            JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).put("mimeType", "text/plain")

        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val SHEET_MIME = "application/vnd.google-apps.spreadsheet"
    }
}
