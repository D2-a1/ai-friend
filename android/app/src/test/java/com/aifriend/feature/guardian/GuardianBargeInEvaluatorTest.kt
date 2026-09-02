package com.aifriend.feature.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 抢话诊断时序、回声误触发和失败关闭测试。 */
class GuardianBargeInEvaluatorTest {

    @Test
    fun echoOnlyPassesOnlyWhenNoVoiceStartIsObserved() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.ECHO_ONLY, acknowledgementStartedAtMs = 1_000)
        evaluator.observe(atMs = 1_100, voiced = false)
        evaluator.acknowledgementFinished(atMs = 1_500)
        evaluator.observe(atMs = 1_800, voiced = false)

        val result = evaluator.finish(atMs = 1_801)

        assertEquals(GuardianBargeInDecision.PASSED, result.decision)
        assertEquals(0, result.falseVoiceStarts)
        assertNull(result.detectionLatencyMs)
    }

    @Test
    fun deviceEchoDuringAcknowledgementFailsInsteadOfBecomingSpeech() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.ECHO_ONLY, acknowledgementStartedAtMs = 2_000)
        evaluator.observe(atMs = 2_100, voiced = true)
        evaluator.observe(atMs = 2_200, voiced = false)
        evaluator.observe(atMs = 2_300, voiced = true)
        evaluator.acknowledgementFinished(atMs = 2_500)

        val result = evaluator.finish(atMs = 2_800)

        assertEquals(GuardianBargeInDecision.FAILED, result.decision)
        assertEquals(2, result.falseVoiceStarts)
    }

    @Test
    fun markedSpeechDuringAcknowledgementPassesWithinLatencyBoundary() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.MARKED_SPEECH, acknowledgementStartedAtMs = 3_000)
        evaluator.observe(atMs = 3_100, voiced = false)
        evaluator.markExternalSpeechStarted(atMs = 3_350)
        evaluator.observe(atMs = 3_520, voiced = true)
        evaluator.acknowledgementFinished(atMs = 3_700)

        val result = evaluator.finish(atMs = 4_000)

        assertEquals(GuardianBargeInDecision.PASSED, result.decision)
        assertEquals(170L, result.detectionLatencyMs)
    }

    @Test
    fun markedSpeechImmediatelyAfterAcknowledgementAlsoPasses() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.MARKED_SPEECH, acknowledgementStartedAtMs = 3_000)
        evaluator.acknowledgementFinished(atMs = 3_500)
        evaluator.markExternalSpeechStarted(atMs = 3_550)
        evaluator.observe(atMs = 3_700, voiced = true)

        val result = evaluator.finish(atMs = 3_800)

        assertEquals(GuardianBargeInDecision.PASSED, result.decision)
        assertEquals(150L, result.detectionLatencyMs)
    }

    @Test
    fun markedSpeechBeyondLatencyBoundaryFailsClosed() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.MARKED_SPEECH, acknowledgementStartedAtMs = 3_000)
        evaluator.acknowledgementFinished(atMs = 3_500)
        evaluator.markExternalSpeechStarted(atMs = 3_600)
        evaluator.observe(atMs = 4_101, voiced = true)

        val result = evaluator.finish(atMs = 4_200)

        assertEquals(GuardianBargeInDecision.FAILED, result.decision)
        assertEquals(501L, result.detectionLatencyMs)
    }

    @Test
    fun clearMakesLateFramesAndFinishInvalid() {
        val evaluator = GuardianBargeInEvaluator()
        evaluator.start(GuardianBargeInScenario.MARKED_SPEECH, acknowledgementStartedAtMs = 4_000)
        evaluator.markExternalSpeechStarted(atMs = 4_100)
        evaluator.clear()

        assertThrows(IllegalStateException::class.java) {
            evaluator.observe(atMs = 4_200, voiced = true)
        }
        assertThrows(IllegalStateException::class.java) {
            evaluator.finish(atMs = 4_300)
        }
    }
}
