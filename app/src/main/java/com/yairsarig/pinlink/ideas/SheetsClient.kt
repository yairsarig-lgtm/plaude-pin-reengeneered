package com.yairsarig.pinlink.ideas

import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.upload.GoogleHttp
import com.yairsarig.pinlink.upload.GoogleHttpException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Minimal Sheets v4 REST client. Works under the drive.file scope for spreadsheets this app created. */
class SheetsClient(private val http: GoogleHttp) {

    /** Creates "Pin Ideas" with Ideas + Log tabs and header rows, in My Drive root. Returns its id. */
    fun createIdeasSheet(): String {
        val body = JSONObject()
            .put("properties", JSONObject().put("title", PinConfig.SHEET_TITLE))
            .put("sheets", JSONArray()
                .put(tab(PinConfig.SHEET_TAB_IDEAS))
                .put(tab(PinConfig.SHEET_TAB_LOG)))
        val id = JSONObject(http.request("POST", BASE, body.toString())).getString("spreadsheetId")
        val headers = JSONObject().put("valueInputOption", "RAW").put("data", JSONArray()
            .put(valueRange("${PinConfig.SHEET_TAB_IDEAS}!A1", PinConfig.SHEET_IDEAS_HEADERS))
            .put(valueRange("${PinConfig.SHEET_TAB_LOG}!A1", PinConfig.SHEET_LOG_HEADERS)))
        http.request("POST", "$BASE/$id/values:batchUpdate", headers.toString())
        return id
    }

    /**
     * True if the spreadsheet exists. Only a 404 means "gone"; anything else (401 stale token,
     * 403 API disabled, network) propagates, so a hiccup can never lead to creating a duplicate.
     */
    fun exists(id: String): Boolean = try {
        http.request("GET", "$BASE/$id?fields=spreadsheetId")
        true
    } catch (e: GoogleHttpException) {
        if (e.code == 404) false else throw e
    }

    /** Raw rows of each tab (header included), ragged as the API returns them. */
    fun readTabs(id: String): Pair<List<List<String>>, List<List<String>>> {
        val ranges = listOf("${PinConfig.SHEET_TAB_IDEAS}!A:F", "${PinConfig.SHEET_TAB_LOG}!A:E")
            .joinToString("&") { "ranges=" + URLEncoder.encode(it, "UTF-8") }
        val vr = JSONObject(http.request("GET", "$BASE/$id/values:batchGet?$ranges")).optJSONArray("valueRanges") ?: JSONArray()
        return rows(vr.optJSONObject(0)) to rows(vr.optJSONObject(1))
    }

    fun readIdeasRows(id: String): List<List<String>> {
        val range = URLEncoder.encode("${PinConfig.SHEET_TAB_IDEAS}!A:F", "UTF-8")
        return rows(JSONObject(http.request("GET", "$BASE/$id/values/$range")))
    }

    /** Deletes one row (0-based, header = 0) of the Ideas tab. */
    fun deleteIdeasRow(id: String, rowIndex: Int) {
        val sheets = JSONObject(http.request("GET", "$BASE/$id?fields=sheets.properties(sheetId,title)")).getJSONArray("sheets")
        val tabId = (0 until sheets.length()).map { sheets.getJSONObject(it).getJSONObject("properties") }
            .first { it.getString("title") == PinConfig.SHEET_TAB_IDEAS }.getInt("sheetId")
        val req = JSONObject().put("requests", JSONArray().put(JSONObject().put("deleteDimension", JSONObject()
            .put("range", JSONObject().put("sheetId", tabId).put("dimension", "ROWS")
                .put("startIndex", rowIndex).put("endIndex", rowIndex + 1)))))
        http.request("POST", "$BASE/$id:batchUpdate", req.toString())
    }

    private fun tab(title: String) = JSONObject().put("properties", JSONObject().put("title", title))

    private fun valueRange(range: String, row: List<String>) =
        JSONObject().put("range", range).put("values", JSONArray().put(JSONArray(row)))

    private fun rows(valueRange: JSONObject?): List<List<String>> {
        val values = valueRange?.optJSONArray("values") ?: return emptyList()
        return (0 until values.length()).map { r ->
            val row = values.getJSONArray(r)
            (0 until row.length()).map { row.optString(it) }
        }
    }

    private companion object {
        const val BASE = "https://sheets.googleapis.com/v4/spreadsheets"
    }
}
