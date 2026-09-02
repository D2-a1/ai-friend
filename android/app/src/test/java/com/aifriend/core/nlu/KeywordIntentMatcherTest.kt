package com.aifriend.core.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地关键词意图匹配测试。
 */
class KeywordIntentMatcherTest {
    private val matcher = KeywordIntentMatcher()

    @Test
    fun cancellationMustHavePriorityOverCallSignal() {
        val signals = matcher.match("不要打了，取消打电话")

        assertEquals(IntentSignalType.CANCELLATION, signals.first().type)
        assertTrue(signals.any { it.type == IntentSignalType.VOICE_CALL })
    }

    @Test
    fun correctionMustBeRecognizedBeforeVideoCall() {
        val signals = matcher.match("不对，改成视频通话")

        assertEquals(IntentSignalType.CORRECTION, signals.first().type)
        assertTrue(signals.any { it.type == IntentSignalType.VIDEO_CALL })
    }
}
