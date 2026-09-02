package com.aifriend.feature.guardian

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianWechatCallAudioCoordinatorTest {

    @Test
    fun absentGuardianMeansThereIsNoMicrophoneLeaseToRelease() = runTest {
        assertTrue(GuardianWechatCallAudioCoordinator().releaseBeforeCall())
    }

    @Test
    fun registeredGuardianReleasesAndResumesOnlyForItsOwner() = runTest {
        val coordinator = GuardianWechatCallAudioCoordinator()
        val owner = Any()
        var releases = 0
        var resumes = 0
        coordinator.register(owner, { releases++ }, { resumes++ })

        assertTrue(coordinator.releaseBeforeCall())
        coordinator.resumeIfIdle()
        coordinator.unregister(Any())
        coordinator.resumeIfIdle()
        coordinator.unregister(owner)
        coordinator.resumeIfIdle()

        assertEquals(1, releases)
        assertEquals(2, resumes)
    }

    @Test
    fun releaseTimeoutFailsClosed() = runTest {
        val coordinator = GuardianWechatCallAudioCoordinator()
        coordinator.register(Any(), { delay(2_000L) }, {})

        assertFalse(coordinator.releaseBeforeCall())
    }
}
