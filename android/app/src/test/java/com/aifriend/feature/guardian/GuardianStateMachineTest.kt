package com.aifriend.feature.guardian

import com.aifriend.core.feedback.FeedbackSymbol
import com.aifriend.core.feedback.HapticCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双唤醒窗口和抢占优先级测试。 */
class GuardianStateMachineTest {

    @Test
    fun oneWakeHitDoesNotLeaveSleeping() {
        val machine = startedMachine()

        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))

        assertEquals(GuardianMode.SLEEPING, machine.status.mode)
    }

    @Test
    fun secondWakeHitWithinFiveSecondsStartsListening() {
        val machine = startedMachine()

        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))
        machine.reduce(GuardianEvent.WakeWordDetected(5_999L))

        assertEquals(GuardianMode.AWAKE_LISTENING, machine.status.mode)
    }

    @Test
    fun lateSecondHitBecomesNewFirstHit() {
        val machine = startedMachine()

        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))
        machine.reduce(GuardianEvent.WakeWordDetected(6_001L))
        machine.reduce(GuardianEvent.WakeWordDetected(11_001L))

        assertEquals(GuardianMode.AWAKE_LISTENING, machine.status.mode)
    }

    @Test
    fun microphoneBusyPreemptsListeningAndResumesSleeping() {
        val machine = startedMachine()
        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))
        machine.reduce(GuardianEvent.WakeWordDetected(2_000L))

        machine.reduce(GuardianEvent.AudioBecameBusy)
        assertEquals(GuardianMode.WECHAT_BUSY, machine.status.mode)

        machine.reduce(GuardianEvent.AudioBecameAvailable)
        assertEquals(GuardianMode.SLEEPING, machine.status.mode)
    }

    @Test
    fun disablePreventsLateWakeFromRevivingService() {
        val machine = startedMachine()
        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))

        machine.reduce(GuardianEvent.DisableRequested)
        machine.reduce(GuardianEvent.WakeWordDetected(2_000L))

        assertEquals(GuardianMode.GUARDIAN_OFF, machine.status.mode)
    }

    @Test
    fun permissionRevocationOffersRecoveryUntilNextExplicitEnable() {
        val machine = startedMachine()

        machine.reduce(GuardianEvent.PermissionRevoked)

        assertEquals(GuardianMode.GUARDIAN_OFF, machine.status.mode)
        assertTrue(machine.status.permissionRecoveryRequired)
        machine.reduce(GuardianEvent.EnableRequested)
        assertFalse(machine.status.permissionRecoveryRequired)
    }

    @Test
    fun cancelledCaptureReturnsToSleepingWithoutRevivingOldWake() {
        val machine = startedMachine()
        machine.reduce(GuardianEvent.WakeWordDetected(1_000L))
        machine.reduce(GuardianEvent.WakeWordDetected(2_000L))

        machine.reduce(GuardianEvent.TaskCaptureStopped("本次任务已取消"))

        assertEquals(GuardianMode.SLEEPING, machine.status.mode)
        assertEquals("本次任务已取消", machine.status.message)
        machine.reduce(GuardianEvent.WakeWordDetected(2_500L))
        assertEquals(GuardianMode.SLEEPING, machine.status.mode)
    }

    @Test
    fun listeningAndErrorHaveNonColorFeedback() {
        val listening = GuardianStatus(GuardianMode.AWAKE_LISTENING, "正在听")
            .feedbackPresentation()
        val error = GuardianStatus(GuardianMode.ERROR, "已停止")
            .feedbackPresentation()

        assertEquals(FeedbackSymbol.LISTENING, listening.symbol)
        assertEquals(HapticCue.LISTENING, listening.hapticCue)
        assertEquals(FeedbackSymbol.ERROR, error.symbol)
        assertEquals(HapticCue.ERROR, error.hapticCue)
    }

    private fun startedMachine(): GuardianStateMachine = GuardianStateMachine().apply {
        reduce(GuardianEvent.EnableRequested)
        reduce(GuardianEvent.CaptureStarted)
    }
}
