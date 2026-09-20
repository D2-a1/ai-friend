package com.aifriend.core.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedAudioCaptureTest {
    @Test fun sameLeaseRecordsStopsAndCanRecordNextUtterance() = runTest {
        val d = Driver(); val lease = OwnedAudioCapture(d)
        lease.startUtterance(30000); assertTrue(d.automatic); assertEquals(AudioCaptureState.CAPTURING, lease.state.value)
        assertEquals(SpeechEndpointBoundary.UTTERANCE_COMPLETE, lease.awaitSpeechEndpoint())
        assertSame(d.audio, lease.stop()); assertEquals(AudioCaptureState.STOPPED, lease.state.value)
        lease.startUtterance(30000); lease.cancel(); assertNull(d.owner); assertEquals(2, d.starts)
    }
    @Test fun lateCancelCannotStopNewerLease() = runTest {
        val d = Driver(); val first = OwnedAudioCapture(d); val second = OwnedAudioCapture(d)
        first.start(1000); first.stop(); second.start(1000); val owner = d.owner
        first.cancel(); first.cancel(); assertSame(owner, d.owner); assertEquals(0, d.stoppedOthers)
        second.cancel(); assertNull(d.owner)
    }
    @Test fun failedStartCannotCancelExistingRecorder() = runTest {
        val d = Driver(); val first = OwnedAudioCapture(d); val second = OwnedAudioCapture(d)
        first.start(1000); val owner = d.owner
        try { second.start(1000); fail() } catch (_: IllegalStateException) { }
        assertSame(owner, d.owner); assertEquals(0, d.stoppedOthers); first.cancel()
    }
    @Test fun cancelDuringStartWaitsForOwnedHardwareThenReleasesIt() = runTest {
        val d = Driver(); d.startGate = CompletableDeferred(); val lease = OwnedAudioCapture(d)
        val start = launch { lease.start(1000) }; runCurrent()
        val cancel = launch { lease.cancel() }; runCurrent(); assertFalse(cancel.isCompleted)
        d.startGate!!.complete(Unit); runCurrent(); start.join(); cancel.join()
        assertNull(d.owner); assertEquals(AudioCaptureState.STOPPED, lease.state.value)
    }
    @Test fun coroutineCancellationDuringStartStillReleasesOwnedHardware() = runTest {
        val d = Driver(); d.startGate = CompletableDeferred(); val lease = OwnedAudioCapture(d)
        val start = launch { lease.start(1000) }; runCurrent(); start.cancel(); d.startGate!!.complete(Unit)
        start.join(); assertNull(d.owner); assertEquals(AudioCaptureState.FAILED, lease.state.value)
    }
    @Test fun cancelledStopClearsUndeliveredAudio() = runTest {
        val d = Driver(); d.stopGate = CompletableDeferred(); val lease = OwnedAudioCapture(d); lease.start(1000)
        val stop = launch { lease.stop(); fail("Cancelled stop cannot deliver WAV") }; runCurrent()
        stop.cancel(); d.stopGate!!.complete(Unit); stop.join()
        assertTrue(d.audio.wavBytes.all { it == 0.toByte() }); assertNull(d.owner)
    }
    @Test fun lateStopCannotConsumeDifferentLeaseAudio() = runTest {
        val d = Driver(); val first = OwnedAudioCapture(d); val second = OwnedAudioCapture(d)
        first.start(1000); first.stop(); second.start(1000); val owner = d.owner
        try { first.stop(); fail() } catch (_: IllegalStateException) { }
        assertSame(owner, d.owner); second.cancel()
    }
    @Test fun cancelledLeaseCannotBeRestarted() = runTest {
        val d = Driver(); val lease = OwnedAudioCapture(d); lease.cancel()
        try { lease.startUtterance(1000); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, d.starts); assertEquals(SpeechEndpointBoundary.STOPPED, lease.awaitSpeechEndpoint())
    }
    @Test fun endpointOfOtherLeaseIsNotObserved() = runTest {
        val d = Driver(); val first = OwnedAudioCapture(d); val second = OwnedAudioCapture(d)
        first.start(1000)
        assertEquals(SpeechEndpointBoundary.UNSUPPORTED, second.awaitSpeechEndpoint()); first.cancel()
    }
    @Test fun cleanupFailureIsNotReportedStopped() = runTest {
        val d = Driver(); val lease = OwnedAudioCapture(d); lease.start(1000); d.cancelFailure = true
        try { lease.cancel(); fail() } catch (_: IllegalStateException) { }
        assertEquals(AudioCaptureState.FAILED, lease.state.value)
        d.cancelFailure = false; lease.cancel(); assertNull(d.owner)
    }
    @Test fun objectIdentityNotEqualityAuthorizesCapture() {
        val a = EqualOwner(); val b = EqualOwner()
        assertEquals(a, b); assertFalse(ownsAudioSession(a, b)); assertTrue(ownsAudioSession(a, a))
        assertTrue(ownsAudioSession(null, null)); assertFalse(ownsAudioSession(null, a)); assertFalse(ownsAudioSession(a, null))
    }
    @Test fun staleFocusCallbackCannotCancelLaterUtteranceOfSameOwner() {
        val owner = Any(); val firstFocus = EqualOwner(); val nextFocus = EqualOwner()
        assertTrue(ownsAudioSession(owner, owner))
        assertFalse(matchesAudioFocusRequest(firstFocus, nextFocus))
        assertFalse(matchesAudioFocusRequest(firstFocus, null))
        assertTrue(matchesAudioFocusRequest(nextFocus, nextFocus))
        assertTrue(matchesAudioFocusRequest(null, nextFocus))
    }
    private class EqualOwner { override fun equals(other: Any?) = other is EqualOwner; override fun hashCode() = 1 }
    private class Driver : OwnedAudioCaptureDriver {
        var owner: Any? = null; var starts = 0; var automatic = false; var stoppedOthers = 0
        var startGate: CompletableDeferred<Unit>? = null; var stopGate: CompletableDeferred<Unit>? = null
        var cancelFailure = false
        val audio = CapturedAudio(byteArrayOf(1, 2, 3), 1000)
        override suspend fun startOwned(owner: Any, maxDurationMs: Int, automaticEndpoint: Boolean) {
            check(this.owner == null)
            startGate?.await(); this.owner = owner; starts++; automatic = automaticEndpoint
        }
        override suspend fun awaitOwned(owner: Any) = if (ownsAudioSession(owner, this.owner)) SpeechEndpointBoundary.UTTERANCE_COMPLETE else SpeechEndpointBoundary.UNSUPPORTED
        override suspend fun stopOwned(owner: Any): CapturedAudio {
            check(ownsAudioSession(owner, this.owner)); stopGate?.await(); this.owner = null; return audio
        }
        override suspend fun cancelOwned(owner: Any) {
            if (!ownsAudioSession(owner, this.owner)) return
            if (cancelFailure) error("Synthetic cleanup failure")
            this.owner = null
        }
    }
}
