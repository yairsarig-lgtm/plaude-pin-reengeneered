package com.yairsarig.pinlink.claude

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tells the Claude routine that new transcripts are in Pin Inbox. Counts uploads since the last
 * fire, fires once for all of them, and keeps under [PinConfig.MAX_FIRES_PER_HOUR]. The hourly
 * scheduled run stays as the backup for anything a fire misses.
 */
object ClaudeTrigger {

    data class State(
        val configured: Boolean = false,
        val auto: Boolean = true,
        /** The last fire got 400/401/403/404: URL or token needs fixing. */
        val rejected: Boolean = false,
        val uploadsSinceFire: Int = 0,
        /** Last successful fire (epoch ms), 0 = never. */
        val lastFireAt: Long = 0,
        val firing: Boolean = false,
        /** A background fire is scheduled (debounce or retry). */
        val scheduled: Boolean = false,
    )

    /** One fire: what came back and how many memos it covered. */
    data class Fired(val result: FireResult, val memos: Int)

    private lateinit var app: Context
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    private val prefs: SharedPreferences get() = app.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy { openSecure(app) }

    fun init(context: Context) {
        app = context.applicationContext
        publish()
    }

    // ---------- Settings ----------

    fun routineUrl(): String = secure.getString(PinConfig.PREF_ROUTINE_URL, null).orEmpty()
    fun hasToken(): Boolean = !secure.getString(PinConfig.PREF_ROUTINE_TOKEN, null).isNullOrBlank()

    /** [token] null keeps the stored one (the field is never shown back); blank clears it. */
    fun saveRoutine(url: String, token: String?) {
        secure.edit().putString(PinConfig.PREF_ROUTINE_URL, url.trim()).apply {
            if (token != null) putString(PinConfig.PREF_ROUTINE_TOKEN, token.trim())
        }.commit()
        prefs.edit().putBoolean(PinConfig.PREF_CLAUDE_REJECTED, false).apply()
        PinLog.i("Claude routine settings saved (configured=${isConfigured()})")
        publish()
    }

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean(PinConfig.PREF_CLAUDE_AUTO, on).apply()
        publish()
        if (on && _state.value.uploadsSinceFire > 0) FireWorker.schedule(app)
    }

    /** Valid enough to try: an https URL and a token. */
    fun isConfigured(): Boolean = routineUrl().startsWith(PinConfig.ROUTINE_URL_PREFIX) && hasToken()

    // ---------- Firing ----------

    /** UploadWorker calls this after each new transcript lands in Pin Inbox. */
    fun onUploaded(context: Context) {
        prefs.edit().putInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, prefs.getInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, 0) + 1).apply()
        publish()
        if (_state.value.auto) FireWorker.schedule(context)
    }

    internal fun setScheduled(on: Boolean) { _state.value = _state.value.copy(scheduled = on) }

    /** Fires for every upload since the last fire; null if there are none. */
    suspend fun firePending(): Fired? = mutex.withLock {
        val memos = prefs.getInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, 0)
        if (memos <= 0) return null
        val result = send(PinConfig.FIRE_TEXT_TEMPLATE.format(memos))
        if (result is FireResult.Ok || result is FireResult.RateLimited) {
            // Uploads that landed during the request stay counted for the next fire.
            val left = (prefs.getInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, 0) - memos).coerceAtLeast(0)
            prefs.edit().putInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, left).apply()
        }
        if (result is FireResult.Ok) prefs.edit().putLong(PinConfig.PREF_LAST_FIRE_AT, System.currentTimeMillis()).apply()
        publish()
        Fired(result, memos)
    }

    /** Settings > Test: one fire regardless of pending uploads. */
    suspend fun fireTest(): FireResult = mutex.withLock { send(PinConfig.FIRE_TEST_TEXT).also { publish() } }

    private suspend fun send(text: String): FireResult {
        if (!isConfigured()) return FireResult.NotConfigured
        val now = System.currentTimeMillis()
        val recent = recentFires(prefs.getString(PinConfig.PREF_FIRE_TIMES, "").orEmpty(), now)
        if (recent.size >= PinConfig.MAX_FIRES_PER_HOUR) {
            PinLog.w("Claude: ${recent.size} fires in the last hour; leaving the rest to the hourly run")
            return FireResult.Capped
        }
        _state.value = _state.value.copy(firing = true)
        val url = routineUrl()
        val token = secure.getString(PinConfig.PREF_ROUTINE_TOKEN, null).orEmpty()
        val result = withContext(Dispatchers.IO) { RoutineClient.fire(url, token, text) }
        _state.value = _state.value.copy(firing = false)
        prefs.edit().apply {
            if (result !is FireResult.Failed) putString(PinConfig.PREF_FIRE_TIMES, (recent + now).joinToString(","))
            if (result is FireResult.Rejected) putBoolean(PinConfig.PREF_CLAUDE_REJECTED, true)
            if (result is FireResult.Ok) putBoolean(PinConfig.PREF_CLAUDE_REJECTED, false)
        }.apply()
        PinLog.i("Claude fire: " + when (result) {
            is FireResult.Ok -> "OK"
            is FireResult.RateLimited -> "rate limited (retry after ${result.retryAfterSec ?: "?"} s)"
            is FireResult.Rejected -> "rejected ${result.code}: ${result.message}"
            is FireResult.Failed -> "failed: ${result.message}"
            else -> result.toString()
        })
        return result
    }

    private fun publish() {
        _state.value = _state.value.copy(
            configured = isConfigured(),
            auto = prefs.getBoolean(PinConfig.PREF_CLAUDE_AUTO, true),
            rejected = prefs.getBoolean(PinConfig.PREF_CLAUDE_REJECTED, false),
            uploadsSinceFire = prefs.getInt(PinConfig.PREF_UPLOADS_SINCE_FIRE, 0),
            lastFireAt = prefs.getLong(PinConfig.PREF_LAST_FIRE_AT, 0),
        )
    }

    /** Pure: the stored fire times (CSV of epoch ms) still inside the hourly window. */
    fun recentFires(csv: String, now: Long): List<Long> =
        csv.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { now - it < PinConfig.FIRE_WINDOW_MS }

    /** Keystore trouble (e.g. after a restore) makes the file unreadable: start it over, empty. */
    private fun openSecure(context: Context): SharedPreferences {
        fun open() = EncryptedSharedPreferences.create(
            context, PinConfig.SECURE_PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return try {
            open()
        } catch (e: Exception) {
            PinLog.e("Secure prefs unreadable; resetting (routine URL/token must be re-entered)", e)
            context.deleteSharedPreferences(PinConfig.SECURE_PREFS_NAME)
            open()
        }
    }
}
