package com.aifriend.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯内存 PCM 环形缓冲测试。 */
class Pcm16RingBufferTest {

    @Test
    fun bufferKeepsSamplesInTimeOrderBeforeCapacity() {
        val buffer = Pcm16RingBuffer(5)

        buffer.append(shortArrayOf(1, 2, 3), 3)

        assertEquals(3, buffer.size())
        assertArrayEquals(shortArrayOf(1, 2, 3), buffer.snapshot())
    }

    @Test
    fun bufferKeepsOnlyNewestSamplesAfterWrap() {
        val buffer = Pcm16RingBuffer(5)

        buffer.append(shortArrayOf(1, 2, 3), 3)
        buffer.append(shortArrayOf(4, 5, 6, 7), 4)

        assertEquals(5, buffer.size())
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), buffer.snapshot())
    }

    @Test
    fun clearRemovesAllReadableSamples() {
        val buffer = Pcm16RingBuffer(3)
        buffer.append(shortArrayOf(7, 8, 9), 3)

        buffer.clear()

        assertEquals(0, buffer.size())
        assertArrayEquals(shortArrayOf(), buffer.snapshot())
    }
}
