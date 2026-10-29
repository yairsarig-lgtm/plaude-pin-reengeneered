package com.yairsarig.pinlink.transcribe

import com.yairsarig.pinlink.PinConfig

/**
 * Whisper's failure mode on silence or noise is a loop: the same sentence over and over, or
 * non-speech tags like `[BLANK_AUDIO]`. This drops non-speech tags and any sentence already seen
 * (case/punctuation-insensitive), and says whether what's left is worth uploading.
 * Pure, so it's unit-testable. Pairs with `no_context = true` in the JNI params, which stops one
 * window's text from seeding the next.
 */
object TranscriptGuard {

    /** [text] is the cleaned transcript; [upload] is false when it's empty or mostly repeats. */
    data class Result(val text: String, val sentences: Int, val repeats: Int) {
        val upload: Boolean
            get() = text.isNotBlank() && repeats <= sentences * PinConfig.TRANSCRIPT_MAX_REPEAT_FRACTION
    }

    private val nonSpeech = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")
    private val spaces = Regex("""\s+""")
    private val notWord = Regex("""[^\p{L}\p{N}']+""")

    fun clean(raw: String): Result {
        val seen = HashSet<String>()
        var sentences = 0
        var repeats = 0
        val lines = raw.lines().mapNotNull { segment ->
            val kept = segment.replace(nonSpeech, " ").replace(spaces, " ").trim().split(sentenceEnd).filter { s ->
                val key = s.lowercase().replace(notWord, " ").trim()
                if (key.isEmpty()) return@filter false
                sentences++
                seen.add(key).also { if (!it) repeats++ }
            }
            kept.joinToString(" ").trim().takeIf { it.isNotEmpty() }
        }
        return Result(lines.joinToString("\n"), sentences, repeats)
    }
}
