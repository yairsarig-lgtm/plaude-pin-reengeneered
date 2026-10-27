package com.yairsarig.pinlink.upload

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.yairsarig.pinlink.PinConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Drive access via Google's AuthorizationClient. The OAuth client is the Android type registered
 * in Google Cloud against this package + signing SHA-1, so no client ID or secret lives in the app.
 */
object DriveAuth {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(PinConfig.DRIVE_SCOPE)))
        .build()

    sealed class Outcome {
        data class Token(val accessToken: String) : Outcome()
        /** First use, or consent was revoked: needs an Activity to show Google's consent screen. */
        data class NeedsConsent(val intent: PendingIntent?) : Outcome()
    }

    /** App-wide Drive status for the UI: null = unknown yet. Set by the UI's checks and by uploads. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected
    fun setConnected(value: Boolean) { _connected.value = value }

    /** Blocking. Drive calls that survive a revoked/stale cached token; see [DriveSession]. */
    fun session(context: Context) = DriveSession(
        authorize = { authorize(context) },
        clearToken = { GoogleAuthUtil.clearToken(context, it) },
    )

    /** Blocking; call off the main thread. */
    fun authorize(context: Context): Outcome = toOutcome(Tasks.await(Identity.getAuthorizationClient(context).authorize(request)))

    /** Async variant for the UI. */
    fun authorizeAsync(context: Context, onResult: (Result<Outcome>) -> Unit) {
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { onResult(Result.success(toOutcome(it))) }
            .addOnFailureListener { onResult(Result.failure(it)) }
    }

    /** Reads the consent screen's result. */
    fun fromConsentResult(context: Context, data: Intent?): Outcome =
        toOutcome(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))

    private fun toOutcome(r: AuthorizationResult): Outcome = when {
        r.hasResolution() -> Outcome.NeedsConsent(r.pendingIntent)
        else -> Outcome.Token(r.accessToken ?: error("authorized but no access token"))
    }
}
