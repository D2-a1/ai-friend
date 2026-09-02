package com.aifriend.feature.task

import com.aifriend.feature.guardian.GuardianWakeModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalTaskRecognitionParserTest {

    @Test
    fun `parses bounded transcript and word timestamps`() {
        val recognition = LocalTaskRecognitionParser.parse(
            """{"text":"给 女儿 打 电话","result":[{"word":"给","start":0.1,"end":0.2,"conf":0.8},{"word":"女儿","start":0.21,"end":0.4,"conf":0.9}]}""",
            1_000,
        )

        assertEquals("给 女儿 打 电话", recognition.transcript)
        assertEquals(2, recognition.words.size)
        assertEquals(100, recognition.words.first().startMs)
        assertEquals(GuardianWakeModelManifest.MODEL_VERSION, recognition.modelVersion)
        assertEquals(0.85, recognition.confidence, 0.0001)
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
}
