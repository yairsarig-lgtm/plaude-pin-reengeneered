package com.yairsarig.pinlink.upload

/**
 * Runs Drive calls with a token, handling revoked access. Play services caches access tokens, so
 * after the owner revokes PinLink it still hands out a dead one and Drive answers 401. On a 401
 * we clear that token, ask once for a fresh one silently, retry once, and otherwise report that
 * consent is needed. Pure (dependencies passed in) so it's unit-testable.
 */
class DriveSession(
    private val authorize: () -> DriveAuth.Outcome,
    private val clearToken: (String) -> Unit,
) {
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        /** No usable access; the owner must re-grant Drive (notification). Don't retry. */
        data object NeedsConsent : Result<Nothing>()
    }

    fun <T> run(block: (accessToken: String) -> T): Result<T> {
        repeat(MAX_TOKENS) {
            val token = when (val auth = runCatching(authorize).getOrNull()) {
                is DriveAuth.Outcome.Token -> auth.accessToken
                // Consent screen needed, or authorize itself failed right after a 401: either way, no access.
                else -> return Result.NeedsConsent
            }
            try {
                return Result.Ok(block(token))
            } catch (e: GoogleHttpException) {
                if (e.code != HTTP_UNAUTHORIZED) throw e
                runCatching { clearToken(token) }
            }
        }
        return Result.NeedsConsent
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val MAX_TOKENS = 2 // the cached one, then one fresh one
    }
}
