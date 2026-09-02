package com.aifriend.feature.task

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.ChannelResultDetail
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.MatchedContact
import com.aifriend.contract.model.SpeechProcessingVersions
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.TaskUnderstanding
import java.math.BigDecimal
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageContinuationWindowTest {

    @Test
    fun trustedSentMessageOpensFiveSecondOneShotWindow() {
        var now = 10_000L
        val window = MessageContinuationWindow { now }

        val opened = window.open(messageSession(TaskState.COMPLETED, ChannelResult.SENT))

        assertEquals(MessageContinuationUi("李明", 5), opened)
        now += 4_001L
        assertEquals(1, window.snapshot()?.secondsRemaining)
        assertEquals("ct_contact", window.consume())
        assertNull(window.consume())
    }

    @Test
    fun expiredWindowClearsContactWithoutConsumption() {
        var now = 20_000L
        val window = MessageContinuationWindow { now }
        window.open(messageSession(TaskState.COMPLETED, ChannelResult.SENT))

        now += 5_000L

        assertNull(window.snapshot())
        assertNull(window.consume())
    }

    @Test
    fun partialRequiresAudioSentAndTextFailedEvidence() {
        val window = MessageContinuationWindow { 30_000L }

        val valid = window.open(messageSession(TaskState.PARTIAL, ChannelResult.PARTIAL))
        val invalid = window.open(
            messageSession(
                TaskState.PARTIAL,
                ChannelResult.PARTIAL,
                audioResult = ChannelPartResult.Result.HANDED_TO_WECHAT,
            ),
        )

        assertEquals("李明", valid?.contactLabel)
        assertNull(invalid)
    }

    @Test
    fun callOrUntrustedTerminalResultNeverOpensWindow() {
        val window = MessageContinuationWindow { 40_000L }

        assertNull(
            window.open(
                messageSession(TaskState.FAILED, ChannelResult.FAILED),
            ),
        )
    }

    private fun messageSession(
        state: TaskState,
        result: ChannelResult,
        audioResult: ChannelPartResult.Result = ChannelPartResult.Result.SENT,
    ): TaskSession {
        val textResult = if (result == ChannelResult.PARTIAL) {
            ChannelPartResult.Result.FAILED
        } else {
            ChannelPartResult.Result.SENT
        }
        return TaskSession(
            sessionId = "ts_message",
            sessionVersion = 4,
            state = state,
            candidates = emptyList(),
            allowedActions = emptySet(),
            expiresAt = OffsetDateTime.now().plusMinutes(5),
            understanding = TaskUnderstanding(
                intent = Intent.SEND_MESSAGE,
                transcript = "回来吃饭",
                effectiveAudioRanges = emptyList(),
                corrections = emptyList(),
                confidence = BigDecimal("0.95"),
                processingVersions = SpeechProcessingVersions(
                    dialectCode = "wugang",
                    dialectPackageVersion = "1",
                    primaryAsrModelVersion = "1",
                    mandarinAssistVersion = "1",
                    fusionRuleVersion = "1",
                    alignmentVersion = "1",
                    templateModelVersion = "1",
                    thresholdVersion = "1",
                ),
                contact = MatchedContact("ct_contact", "李明", "二狗子"),
            ),
            channelResult = ChannelResultDetail(
                result = result,
                parts = listOf(
                    ChannelPartResult(
                        part = ChannelPartResult.Part.AUDIO,
                        result = audioResult,
                        evidenceCode = if (audioResult == ChannelPartResult.Result.SENT) {
                            "audio-proof"
                        } else {
                            null
                        },
                    ),
                    ChannelPartResult(
                        part = ChannelPartResult.Part.TEXT,
                        result = textResult,
                        evidenceCode = if (textResult == ChannelPartResult.Result.SENT) {
                            "text-proof"
                        } else {
                            null
                        },
                    ),
                ),
                occurredAt = OffsetDateTime.now(),
            ),
        )
    }
}
