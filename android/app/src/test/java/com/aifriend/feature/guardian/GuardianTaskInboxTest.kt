package com.aifriend.feature.guardian

import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.core.audio.CapturedAudio
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 守护任务交接和当前进程恢复资格测试。 */
class GuardianTaskInboxTest {

    @Test
    fun handoffCanBeConsumedOnceAndCarriesExplicitResumeEligibility() {
        val inbox = GuardianTaskInbox()
        val session = taskSession()
        val audio = CapturedAudio(byteArrayOf(1, 2, 3), 10)

        inbox.publish(session, audio)
        val handoff = inbox.take(session.sessionId)

        assertEquals(session, handoff?.session)
        assertEquals(audio, handoff?.taskAudio)
        assertTrue(handoff?.guardianResumeEligible == true)
        assertNull(inbox.signal.value)
        assertNull(inbox.take(session.sessionId))
    }

    @Test
    fun mismatchedSessionDoesNotConsumeHandoff() {
        val inbox = GuardianTaskInbox()
        val session = taskSession()
        val audio = CapturedAudio(byteArrayOf(1, 2, 3), 10)
        inbox.publish(session, audio)

        assertNull(inbox.take("ts_other"))
        assertEquals(session, inbox.take(session.sessionId)?.session)
    }

    @Test
    fun replacementAndClearZeroPreviousAudioOwnership() {
        val inbox = GuardianTaskInbox()
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5, 6)

        inbox.publish(taskSession(), CapturedAudio(firstBytes, 10))
        inbox.publish(
            taskSession().copy(sessionId = "ts_replacement"),
            CapturedAudio(secondBytes, 10),
        )

        assertTrue(firstBytes.all { it == 0.toByte() })
        inbox.clear()
        assertTrue(secondBytes.all { it == 0.toByte() })
        assertNull(inbox.signal.value)
    }

    private fun taskSession(): TaskSession = TaskSession(
        sessionId = "ts_0123456789abcdef0123456789abcdef",
        sessionVersion = 1,
        state = TaskState.AWAITING_CONFIRMATION,
        candidates = emptyList(),
        allowedActions = emptySet(),
        expiresAt = OffsetDateTime.parse("2026-08-19T04:00:00Z"),
    )
}
