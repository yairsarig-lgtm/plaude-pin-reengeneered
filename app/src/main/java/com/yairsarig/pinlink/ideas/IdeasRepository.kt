package com.yairsarig.pinlink.ideas

import android.content.Context
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import com.yairsarig.pinlink.upload.DriveAuth
import com.yairsarig.pinlink.upload.DriveClient
import com.yairsarig.pinlink.upload.DriveSession
import com.yairsarig.pinlink.upload.GoogleHttp

/**
 * The app's own "Pin Ideas" spreadsheet. Found by its saved ID (it may be moved anywhere;
 * drive.file access follows the file), else by name among app-created files, else created.
 * Blocking; call off the main thread.
 */
class IdeasRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)

    val sheetId: String? get() = prefs.getString(PinConfig.PREF_SHEET_ID, null)

    data class Snapshot(val ideas: List<Idea>, val log: List<LogEntry>)

    fun load(): DriveSession.Result<Snapshot> = DriveAuth.session(context).run { token ->
        val http = GoogleHttp(token)
        val id = ensureSheet(http)
        val (ideaRows, logRows) = SheetsClient(http).readTabs(id)
        Snapshot(IdeasModel.parseIdeas(ideaRows), IdeasModel.parseLog(logRows))
    }

    /** Deletes [idea]'s row, located by exact content right now. Ok(false) if it's already gone. */
    fun delete(idea: Idea): DriveSession.Result<Boolean> = DriveAuth.session(context).run { token ->
        val sheets = SheetsClient(GoogleHttp(token))
        val id = sheetId ?: error("no sheet yet")
        val row = IdeasModel.rowIndexOf(sheets.readIdeasRows(id), idea)
        if (row == null) {
            PinLog.w("Delete: row for '${idea.text.take(40)}' no longer in the sheet")
            false
        } else {
            sheets.deleteIdeasRow(id, row)
            PinLog.i("Deleted idea row $row: '${idea.text.take(40)}'")
            true
        }
    }

    private fun ensureSheet(http: GoogleHttp): String {
        val sheets = SheetsClient(http)
        sheetId?.let { if (sheets.exists(it)) return it }
        val found = DriveClient(http).findSpreadsheet(PinConfig.SHEET_TITLE)
        val id = found ?: sheets.createIdeasSheet()
        prefs.edit().putString(PinConfig.PREF_SHEET_ID, id).apply()
        PinLog.i("${if (found != null) "Found" else "Created"} '${PinConfig.SHEET_TITLE}' spreadsheet, ID: $id")
        return id
    }
}
