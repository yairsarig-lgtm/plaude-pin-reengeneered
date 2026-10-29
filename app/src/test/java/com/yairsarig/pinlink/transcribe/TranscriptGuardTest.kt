package com.yairsarig.pinlink.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGuardTest {

    @Test fun normalMemoPassesUnchanged() {
        val r = TranscriptGuard.clean(" Idea: water the basil.\n Put X on my calendar Thursday at 4.")
        assertEquals("Idea: water the basil.\nPut X on my calendar Thursday at 4.", r.text)
        assertTrue(r.upload)
    }

    @Test fun repeatedSegmentsAreDropped() {
        val r = TranscriptGuard.clean("Add eggs to the shopping list.\nAdd milk.\n add eggs to the shopping list!")
        assertEquals("Add eggs to the shopping list.\nAdd milk.", r.text)
        assertEquals(1, r.repeats)
        assertTrue(r.upload)
    }

    @Test fun loopInsideOneSegmentIsCollapsed() {
        val r = TranscriptGuard.clean("Call mom tonight. Thank you. Thank you.")
        assertEquals("Call mom tonight. Thank you.", r.text)
        assertTrue(r.upload)
    }

    @Test fun mostlyRepeatsIsNotUploaded() {
        val r = TranscriptGuard.clean("Thank you.\nThank you.\nThank you.\nThank you.")
        assertEquals("Thank you.", r.text)
        assertEquals(3, r.repeats)
        assertFalse(r.upload)
    }

    @Test fun emptyOrNonSpeechIsNotUploaded() {
        assertFalse(TranscriptGuard.clean("").upload)
        assertFalse(TranscriptGuard.clean(" [BLANK_AUDIO]\n (wind blowing)\n *music*\n ...").upload)
    }

    @Test fun nonSpeechTagsAreStrippedFromSpeech() {
        val r = TranscriptGuard.clean("[BLANK_AUDIO]\n Idea: a garden map (inaudible) for the shed.")
        assertEquals("Idea: a garden map for the shed.", r.text)
        assertTrue(r.upload)
    }
}
