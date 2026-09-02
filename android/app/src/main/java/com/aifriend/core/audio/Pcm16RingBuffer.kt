package com.aifriend.core.audio

/**
 * 固定容量的 16 位 PCM 环形缓冲。
 *
 * 缓冲只存在当前进程内，不提供文件、网络或持久化接口。清理时会覆盖内部数组，
 * 用于守护休眠阶段保留最近约五秒音频并避免频繁分配。
 *
 * @author codex
 * @since 2026-08-14
 */
class Pcm16RingBuffer(
    private val capacity: Int,
) {
    private val samples = ShortArray(capacity)
    private var writeIndex = 0
    private var sampleCount = 0

    init {
        require(capacity > 0) { "环形缓冲容量必须大于零" }
    }

    /** 当前保存的样本数。 */
    @Synchronized
    fun size(): Int = sampleCount

    /**
     * 复制输入片段到环形缓冲；超过容量时只保留最新样本。
     *
     * @param source PCM 样本
     * @param count 从数组起始位置读取的样本数
     */
    @Synchronized
    fun append(source: ShortArray, count: Int) {
        require(count in 0..source.size) { "PCM 样本数量无效" }
        if (count == 0) return
        val start = (count - capacity).coerceAtLeast(0)
        for (index in start until count) {
            samples[writeIndex] = source[index]
            writeIndex = (writeIndex + 1) % capacity
            if (sampleCount < capacity) sampleCount++
        }
    }

    /**
     * 按时间顺序返回当前样本副本。调用方使用完毕后必须自行清零。
     */
    @Synchronized
    fun snapshot(): ShortArray {
        val result = ShortArray(sampleCount)
        val oldest = if (sampleCount == capacity) writeIndex else 0
        for (index in result.indices) {
            result[index] = samples[(oldest + index) % capacity]
        }
        return result
    }

    /** 覆盖内部样本并恢复空缓冲状态。 */
    @Synchronized
    fun clear() {
        samples.fill(0)
        writeIndex = 0
        sampleCount = 0
    }
}
