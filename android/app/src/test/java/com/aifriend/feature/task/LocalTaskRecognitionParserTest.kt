package com.aifriend.feature.task

import com.aifriend.feature.guardian.GuardianWakeModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskRecognitionParserTest {

    @Test
    fun `parses bounded transcript and word timestamps`() {
        val recognition = LocalTaskRecognitionParser.parse(
            """{"text":"给 女儿 打 电话","result":[{"word":"给","start":0.1,"end":0.2,"conf":0.8},{"word":"女儿","start":0.21,"end":0.4,"conf":0.9},{"word":"打","start":0.4,"end":0.6,"conf":0.85},{"word":"电话","start":0.6,"end":0.9,"conf":0.85}]}""",
            1_000,
        )

        assertEquals("给 女儿 打 电话", recognition.transcript)
        assertEquals(4, recognition.words.size)
        assertEquals(100, recognition.words.first().startMs)
        assertEquals(GuardianWakeModelManifest.MODEL_VERSION, recognition.modelVersion)
        assertEquals(0.85, recognition.confidence, 0.0001)
    }

    @Test
    fun `empty Vosk result is classified as retryable no speech`() {
        assertThrows(NoSpeechRecognizedException::class.java) {
            LocalTaskRecognitionParser.parse("""{"text":""}""", 1_000)
        }
    }

    @Test
    fun `rejects timestamp outside current recording`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalTaskRecognitionParser.parse(
                """{"text":"打 电话","result":[{"word":"电话","start":0.1,"end":2.0,"conf":0.8}]}""",
                1_000,
            )
        }
    }

    @Test
    fun `uses bounded whole utterance evidence when vosk omits word timestamps`() {
        val recognition = LocalTaskRecognitionParser.parse(
            """{"text":"给 老 大 打 电 话"}""",
            1_200,
        )

        assertEquals(1, recognition.words.size)
        assertEquals("给 老 大 打 电 话", recognition.words.single().text)
        assertEquals(0, recognition.words.single().startMs)
        assertEquals(1_200, recognition.words.single().endMs)
        assertEquals(0.5, recognition.confidence, 0.0001)
    }

    @Test
    fun `keeps completed endpoint result when final silence is empty`() {
        val recognition = LocalTaskRecognitionParser.parseSegments(
            listOf(
                """{"text":"给 老大 打电话","result":[{"word":"给","start":0.1,"end":0.2,"conf":0.9},{"word":"老大","start":0.2,"end":0.5,"conf":0.9},{"word":"打电话","start":0.5,"end":0.9,"conf":0.9}]}""",
                """{"text":""}""",
            ),
            1_200,
        )

        assertEquals("给 老大 打电话", recognition.transcript)
        assertEquals(3, recognition.words.size)
        assertEquals(900, recognition.words.last().endMs)
    }
    @Test
    fun `builds bounded call grammar from compatible dialect aliases`() {
        val grammar = TaskCallGrammar.build(setOf("老大"))

        assertTrue(grammar!!.contains("给 老 大 打 电 话"))
        assertTrue(grammar.contains("给 老大 打 电 话"))
        assertTrue(grammar.contains("给 [unk] 打 电 话"))
        assertTrue(grammar.contains("给 老 大 视 频 通 话"))
        assertTrue(grammar.contains("[unk]"))
        assertFalse(grammar.contains("\"语音通话\""))
        assertFalse(grammar.contains("\"视频通话\""))
        assertFalse(grammar.contains("\"打视频\""))
    }

    @Test
    fun `accepts a call action while leaving alias identity to acoustic matching`() {
        assertTrue(TaskCallGrammar.isRecognizedCall("给 老 大 打 电 话", setOf("老大")))
        assertFalse(TaskCallGrammar.isRecognizedCall("给 老 大 发 消 息", setOf("老大")))
        assertTrue(TaskCallGrammar.isRecognizedCall("给 [unk] 打 电 话", setOf("老大")))
    }

    @Test
    fun `rejects unsafe aliases instead of interpolating them into grammar`() {
        assertNull(TaskCallGrammar.build(setOf("坏]称呼")))
        assertNull(TaskCallGrammar.build(setOf(" ")))
    }
}
