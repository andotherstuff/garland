package com.andotherstuff.garland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarlandSessionStoreNormalizationTest {
    @Test
    fun trimsPrivateKeysLoadedFromSessionState() {
        assertEquals("deadbeef", GarlandSessionStore.normalizePrivateKeyHex("  deadbeef  "))
    }

    @Test
    fun dropsBlankPrivateKeysLoadedFromSessionState() {
        assertNull(GarlandSessionStore.normalizePrivateKeyHex("   "))
    }
}
