package com.yairsarig.pinlink.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import com.yairsarig.pinlink.ideas.Idea
import com.yairsarig.pinlink.ideas.IdeaFilter
import com.yairsarig.pinlink.ideas.IdeasModel
import com.yairsarig.pinlink.ideas.IdeasRepository
import com.yairsarig.pinlink.ideas.LogEntry
import com.yairsarig.pinlink.upload.DriveAuth
import com.yairsarig.pinlink.upload.DriveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home screen's state: ideas from the app's sheet, the filter, and delete-with-undo. */
class IdeasViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IdeasRepository(app)

    data class State(
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        /** All ideas, newest first, minus the one waiting out its undo window. */
        val ideas: List<Idea> = emptyList(),
        val recentLog: List<LogEntry> = emptyList(),
        val filter: IdeaFilter = IdeaFilter.All,
        val pendingDelete: Idea? = null,
        val error: String? = null,
        val needsDrive: Boolean = false,
    ) {
        val visible: List<Idea> get() = IdeasModel.apply(filter, ideas)
        val tags: List<Pair<String, Int>> get() = IdeasModel.tagCounts(ideas)
        val needsReviewCount: Int get() = ideas.count { it.needsReview }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val sheetId: String? get() = repo.sheetId

    private var all: List<Idea> = emptyList()
    private var deleteJob: Job? = null

    init { refresh() }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.load() } }
            _state.update { s ->
                r.fold(
                    onSuccess = { result ->
                        when (result) {
                            is DriveSession.Result.Ok -> {
                                all = IdeasModel.newestFirst(result.value.ideas)
                                DriveAuth.setConnected(true)
                                s.copy(loaded = true, refreshing = false, error = null, needsDrive = false,
                                    ideas = all.filterNot { it == s.pendingDelete },
                                    recentLog = IdeasModel.recentLog(result.value.log))
                            }
                            is DriveSession.Result.NeedsConsent -> {
                                DriveAuth.setConnected(false)
                                s.copy(loaded = true, refreshing = false, needsDrive = true)
                            }
                        }
                    },
                    onFailure = { e ->
                        PinLog.e("Loading ideas failed", e)
                        s.copy(loaded = true, refreshing = false, error = friendly(e))
                    },
                )
            }
        }
    }

    fun setFilter(f: IdeaFilter) = _state.update { it.copy(filter = f) }

    /** Hides the row now; deletes it from the sheet after the undo window unless [undo] is called. */
    fun delete(idea: Idea) {
        commitPendingDelete() // a second delete commits the first right away
        _state.update { it.copy(pendingDelete = idea, ideas = it.ideas - idea) }
        deleteJob = viewModelScope.launch {
            delay(PinConfig.UNDO_WINDOW_MS)
            commitPendingDelete()
        }
    }

    fun undo() {
        deleteJob?.cancel()
        _state.update { s ->
            val idea = s.pendingDelete ?: return@update s
            s.copy(pendingDelete = null, ideas = IdeasModel.newestFirst(s.ideas + idea))
        }
    }

    private fun commitPendingDelete() {
        val idea = _state.value.pendingDelete ?: return
        deleteJob?.cancel()
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.delete(idea) } }
            if (r.isFailure || r.getOrNull() is DriveSession.Result.NeedsConsent) {
                PinLog.e("Delete failed; restoring row", r.exceptionOrNull())
                _state.update { s -> s.copy(ideas = IdeasModel.newestFirst(s.ideas + idea), error = "Couldn't delete — try again") }
            }
            refresh()
        }
    }

    override fun onCleared() {
        // Leaving the screen inside the undo window still deletes, as the snackbar promised.
        val idea = _state.value.pendingDelete ?: return
        Thread { runCatching { repo.delete(idea) } }.start()
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "SERVICE_DISABLED" in m || "has not been used" in m -> "Google Sheets API is off for this app's Cloud project"
            "UnknownHost" in e.javaClass.simpleName || "Unable to resolve" in m -> "Offline"
            else -> "Couldn't load ideas"
        }
    }
}
