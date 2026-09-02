package com.aifriend.core.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有限震动时序的安全边界测试。 */
class HapticPatternRegistryTest {

    @Test
    fun everyAudibleCueUsesShortNonRepeatingTimings() {
        HapticCue.entries.filterNot { it == HapticCue.NONE }.forEach { cue ->
            val pattern = checkNotNull(HapticPatternRegistry.pattern(cue))

            assertTrue(pattern.isNotEmpty())
            assertTrue(pattern.all { it >= 0L })
            assertTrue(pattern.sum() <= 700L)
            assertFalse(pattern.drop(1).all { it == 0L })
        }
        assertNull(HapticPatternRegistry.pattern(HapticCue.NONE))
    }

    @Test
    fun callersCannotMutateRegisteredPattern() {
        val first = checkNotNull(HapticPatternRegistry.pattern(HapticCue.ERROR))
        val expected = first.copyOf()
        first.fill(0L)

        assertArrayEquals(expected, HapticPatternRegistry.pattern(HapticCue.ERROR))
    }
}
