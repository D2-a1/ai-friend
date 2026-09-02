package com.aifriend.feature.privacy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWipeCoordinatorTest {

    @Test
    fun acceptancePersistsMarkerBeforeWipeAndClearsOnlyAfterSuccess() = runTest {
        val events = mutableListOf<String>()
        val marker = FakeMarker(events)
        val coordinator = AccountWipeCoordinator(marker, FakeWiper(events))

        coordinator.beginAcceptedWipe()

        assertEquals(listOf("mark", "wipe", "clear"), events)
        assertFalse(marker.required)
    }

    @Test
    fun failedWipeKeepsMarkerForNextStartup() = runTest {
        val marker = FakeMarker(mutableListOf())
        val coordinator = AccountWipeCoordinator(marker, AccountLocalDataWiper { error("disk") })

        runCatching { coordinator.beginAcceptedWipe() }

        assertTrue(marker.required)
    }

    @Test
    fun startupResumesExistingMarkerWithoutRewritingIt() = runTest {
        val events = mutableListOf<String>()
        val marker = FakeMarker(events).apply { required = true }
        val coordinator = AccountWipeCoordinator(marker, FakeWiper(events))

        coordinator.resumeRequiredWipe()

        assertEquals(listOf("wipe", "clear"), events)
        assertFalse(marker.required)
    }

    private class FakeMarker(private val events: MutableList<String>) : AccountWipeMarkerStore {
        var required = false
        override fun isRequired(): Boolean = required
        override fun markRequired() {
            events += "mark"
            required = true
        }
        override fun clear() {
            events += "clear"
            required = false
        }
    }

    private class FakeWiper(private val events: MutableList<String>) : AccountLocalDataWiper {
        override suspend fun wipe() {
            events += "wipe"
        }
    }
}
