package com.yairsarig.pinlink.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveClientTest {
    @Test fun parentsIsAJsonArray() {
        val json = DriveClient.uploadMetadata("1Wh4folder", "2026-10-27_13-38-07.txt").toString()
        assertEquals(true, json.contains("\"parents\":[\"1Wh4folder\"]"))
        assertEquals(true, json.contains("\"name\":\"2026-10-27_13-38-07.txt\""))
    }
}
