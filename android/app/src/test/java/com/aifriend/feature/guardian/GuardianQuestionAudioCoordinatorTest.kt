package com.aifriend.feature.guardian

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuardianQuestionAudioCoordinatorTest {
    @Test fun sleepingGuardianOnlyGrantsAfterAudioReleaseAndResumesOnClose() = runTest {
        val f = Fixture(); val released = CompletableDeferred<Unit>(); f.pauseGate = released
        val request = async { f.coordinator.acquireQuestion() }; runCurrent()
        assertFalse(request.isCompleted); assertEquals(GuardianMode.QUESTION_PAUSED, f.machine.status.mode)
        f.machine.reduce(GuardianEvent.WakeWordDetected(1)); f.machine.reduce(GuardianEvent.WakeWordDetected(2))
        assertEquals(GuardianMode.QUESTION_PAUSED, f.machine.status.mode)
        released.complete(Unit); val lease = request.await()!!; assertFalse(f.microphone)
        lease.release(); assertEquals(GuardianMode.SLEEPING, f.machine.status.mode); assertEquals(1, f.resumes)
    }
    @Test fun ordinaryBackgroundRestartCannotEnterDuringQuestion() = runTest {
        val f = Fixture(); val lease = f.coordinator.acquireQuestion()!!; var starts = 0
        assertFalse(f.coordinator.guardianOperation(f.owner) { starts++ }); assertEquals(0, starts)
        lease.release(); assertTrue(f.coordinator.guardianOperation(f.owner) { starts++ }); assertEquals(1, starts)
    }
    @Test fun newExplicitStartWaitsUntilQuestionFinishesWithoutStartingNewService() = runTest {
        val c = GuardianQuestionAudioCoordinator(); val lease = c.acquireQuestion()!!; val owner = Any(); var starts = 0
        c.register(owner, { true }, {})
        val job = launch { c.guardianOperation(owner, wait = true) { starts++ } }; runCurrent(); assertEquals(0, starts)
        lease.release(); runCurrent(); job.join(); assertEquals(1, starts)
    }
    @Test fun secondQuestionCannotPreemptFirstAndLateOldReleaseCannotUnlockNext() = runTest {
        val f = Fixture(); val first = f.coordinator.acquireQuestion()!!
        assertNull(f.coordinator.acquireQuestion()); assertEquals(1, f.pauses)
        first.release(); val next = f.coordinator.acquireQuestion()!!; first.release()
        assertFalse(f.coordinator.guardianOperation(f.owner) { fail("Old lease unlocked new capture") })
        next.release(); assertEquals(2, f.resumes)
    }
    @Test fun activeContactTaskIsNotInterruptedByQuestion() = runTest {
        val f = Fixture(); f.machine.reduce(GuardianEvent.WakeWordDetected(1)); f.machine.reduce(GuardianEvent.WakeWordDetected(2))
        assertNull(f.coordinator.acquireQuestion()); assertTrue(f.microphone)
        assertEquals(GuardianMode.AWAKE_LISTENING, f.machine.status.mode); assertEquals(0, f.resumes)
    }
    @Test fun userDisableAndPermissionRevocationNeverResumeFromOldLease() = runTest {
        listOf(GuardianEvent.DisableRequested, GuardianEvent.PermissionRevoked).forEach { event ->
            val f = Fixture(); val lease = f.coordinator.acquireQuestion()!!; f.machine.reduce(event); lease.release()
            assertEquals(GuardianMode.GUARDIAN_OFF, f.machine.status.mode); assertEquals(0, f.resumes)
        }
    }
    @Test fun serviceReplacementCannotReceiveOldResumeCallback() = runTest {
        val f = Fixture(); val lease = f.coordinator.acquireQuestion()!!; val next = Any(); var newResumes = 0
        f.coordinator.unregister(f.owner); f.coordinator.register(next, { true }, { newResumes++ })
        f.coordinator.unregister(f.owner); lease.release()
        assertEquals(0, f.resumes); assertEquals(0, newResumes)
        assertFalse(f.coordinator.guardianOperation(f.owner) { fail() }); assertTrue(f.coordinator.guardianOperation(next) {})
    }
    @Test fun cancelDuringNonCancellablePauseDoesNotLeakOwnership() = runTest {
        val f = Fixture(); f.pauseGate = CompletableDeferred()
        val request = async { f.coordinator.acquireQuestion() }; runCurrent(); request.cancel(); f.pauseGate!!.complete(Unit)
        try { request.await(); fail() } catch (_: CancellationException) { }
        runCurrent(); assertTrue(f.coordinator.guardianOperation(f.owner) {})
        assertEquals(GuardianMode.SLEEPING, f.machine.status.mode)
    }
    @Test fun timedOutGuardianOperationWaitDoesNotPauseOrReserveQuestion() = runTest {
        val f = Fixture(); val hold = CompletableDeferred<Unit>()
        val guardian = launch { f.coordinator.guardianOperation(f.owner) { hold.await() } }; runCurrent()
        val request = async { f.coordinator.acquireQuestion() }; advanceTimeBy(5_001); runCurrent()
        assertNull(request.await()); assertEquals(0, f.pauses)
        hold.complete(Unit); guardian.join(); val lease = f.coordinator.acquireQuestion()!!; lease.release()
    }
    @Test fun latePauseReturnAfterDeadlineIsNotAUsableQuestionLease() = runTest {
        val f = Fixture(); f.pauseGate = CompletableDeferred()
        val request = async { f.coordinator.acquireQuestion() }; runCurrent(); advanceTimeBy(5_001)
        f.pauseGate!!.complete(Unit); runCurrent(); assertNull(request.await())
        assertEquals(GuardianMode.SLEEPING, f.machine.status.mode); assertTrue(f.coordinator.guardianOperation(f.owner) {})
    }
    @Test fun pauseFailureIsNotReportedSuccessfulAndClearsReservation() = runTest {
        val f = Fixture(); f.pauseFailure = true
        try { f.coordinator.acquireQuestion(); fail() } catch (_: IllegalStateException) { }
        assertTrue(f.coordinator.guardianOperation(f.owner) {})
    }
    @Test fun cancelledExplicitStartNeverRunsAfterRelease() = runTest {
        val f = Fixture(); val lease = f.coordinator.acquireQuestion()!!
        val restart = launch { f.coordinator.guardianOperation(f.owner, wait = true) { fail("Cancelled start resumed") } }
        runCurrent(); restart.cancelAndJoin(); lease.release(); runCurrent()
    }
    @Test fun busyEventsDoNotEndQuestionPauseAndReleaseDoesNotReviveFailure() {
        val f = Fixture(); f.machine.reduce(GuardianEvent.QuestionPauseRequested)
        f.machine.reduce(GuardianEvent.AudioBecameBusy); f.machine.reduce(GuardianEvent.AudioBecameAvailable)
        assertEquals(GuardianMode.QUESTION_PAUSED, f.machine.status.mode)
        f.machine.reduce(GuardianEvent.Failed("合成失败")); f.machine.reduce(GuardianEvent.QuestionReleased)
        assertEquals(GuardianMode.ERROR, f.machine.status.mode)
    }
    @Test fun unknownQuestionCleanupBlocksResumeNewQuestionAndWaitingStart() = runTest {
        val f = Fixture(); val lease = f.coordinator.acquireQuestion()!!
        val waitingStart = async { f.coordinator.guardianOperation(f.owner, wait = true) { fail("Cleanup is not confirmed") } }
        runCurrent(); lease.retainAfterCleanupFailure(); runCurrent()
        assertFalse(waitingStart.await()); lease.release()
        assertEquals(0, f.resumes); assertNull(f.coordinator.acquireQuestion())
        assertFalse(f.coordinator.guardianOperation(f.owner) {})
    }
    @Test fun oldLeaseCannotPoisonDifferentQuestionReservation() = runTest {
        val f = Fixture(); val old = f.coordinator.acquireQuestion()!!; old.release()
        val current = f.coordinator.acquireQuestion()!!; old.retainAfterCleanupFailure(); current.release()
        assertTrue(f.coordinator.guardianOperation(f.owner) {}); assertEquals(2, f.resumes)
    }

    private class Fixture {
        val coordinator = GuardianQuestionAudioCoordinator(); val owner = Any(); val machine = GuardianStateMachine()
        var pauseGate: CompletableDeferred<Unit>? = null; var microphone = true; var pauses = 0; var resumes = 0; var pauseFailure = false
        init {
            machine.reduce(GuardianEvent.EnableRequested); machine.reduce(GuardianEvent.CaptureStarted)
            coordinator.register(owner, {
                withContext(NonCancellable) {
                    if (machine.reduce(GuardianEvent.QuestionPauseRequested).mode != GuardianMode.QUESTION_PAUSED) return@withContext false
                    pauses++; pauseGate?.await(); microphone = false
                    if (pauseFailure) error("Synthetic pause failure")
                    true
                }
            }, {
                if (machine.status.mode == GuardianMode.QUESTION_PAUSED) {
                    machine.reduce(GuardianEvent.QuestionReleased); resumes++
                }
            })
        }
    }
}
