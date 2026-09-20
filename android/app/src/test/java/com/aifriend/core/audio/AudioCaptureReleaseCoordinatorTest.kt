package com.aifriend.core.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioCaptureReleaseCoordinatorTest {
    @Test
    fun cleanupSurvivesOwnerCancellationWhileSuspended() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        val capture = FakeCapture()
        val releases = AudioCaptureReleaseCoordinator(capture, owner)
        val cleanup = releases.release()
        assertEquals(1, capture.cancelCalls)
        owner.cancel()
        capture.allowRelease.complete(Unit)
        cleanup.await()
        assertFalse(capture.recording)
    }

    @Test
    fun onClearedCanReleaseAfterOwnerScopeWasAlreadyCancelled() = runTest {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        owner.cancel()
        val capture = FakeCapture()
        val cleanup = AudioCaptureReleaseCoordinator(capture, owner).release()
        assertEquals(1, capture.cancelCalls)
        capture.allowRelease.complete(Unit)
        cleanup.await()
        assertFalse(capture.recording)
    }

    @Test
    fun repeatedExitSharesCleanupAndNextStartWaitsForRelease() = runTest {
        val capture = FakeCapture()
        val releases = AudioCaptureReleaseCoordinator(capture, this)
        val first = releases.release()
        assertSame(first, releases.release())
        assertTrue(capture.recording)
        capture.allowRelease.complete(Unit)
        first.await()
        capture.start(1000)
        assertEquals(1, capture.cancelCalls)
        assertTrue(capture.recording)
    }

    @Test
    fun cleanupFailurePropagatesSoCallerCannotStartNewRecording() = runTest {
        val capture = FakeCapture(failCleanup = true)
        capture.allowRelease.complete(Unit)
        val releases = AudioCaptureReleaseCoordinator(capture, this)
        val result = runCatching {
            releases.release().await()
            capture.start(1000)
        }
        assertTrue(result.isFailure)
        assertEquals(0, capture.startCalls)
    }

    private class FakeCapture(private val failCleanup: Boolean = false) : AudioCapturePort {
        override val state = MutableStateFlow(AudioCaptureState.CAPTURING)
        val allowRelease = CompletableDeferred<Unit>()
        var cancelCalls = 0
        var startCalls = 0
        var recording = true
        override suspend fun start(maxDurationMs: Int) {
            check(!recording)
            startCalls++
            recording = true
        }
        override suspend fun stop(): CapturedAudio = error("not used")
        override suspend fun cancel() {
            cancelCalls++
            allowRelease.await()
            if (failCleanup) error("cleanup failed")
            recording = false
            state.value = AudioCaptureState.STOPPED
        }
    }
}
