package com.aifriend.core.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * 统一音频捕获端口。录音结果只允许在当前进程内短暂使用。
 *
 * @author codex
 * @since 2026-07-25
 */
interface AudioCapturePort {
    val state: StateFlow<AudioCaptureState>

    /**
     * 开始录制 16 kHz 单声道 PCM。
     *
     * @param maxDurationMs 本次录音最长毫秒数
     */
    suspend fun start(maxDurationMs: Int)

    /**
     * 停止录音并返回标准 WAV 字节。实现必须在返回前删除临时文件。
     */
    suspend fun stop(): CapturedAudio

    /**
     * 取消录音并立即清理未提交内容。
     */
    suspend fun cancel()
}

/**
 * 当前进程内的 WAV 录音。使用完毕必须调用 [clear]。
 *
 * @property wavBytes 标准 PCM WAV 字节
 * @property durationMs 从 PCM 样本数计算的真实时长
 */
class CapturedAudio(
    val wavBytes: ByteArray,
    val durationMs: Int,
) {
    /**
     * 清零当前内存副本，防止页面退出后继续持有原始录音。
     */
    fun clear() {
        wavBytes.fill(0)
    }
}

/**
 * 音频捕获状态。
 */
enum class AudioCaptureState {
    STOPPED,
    STARTING,
    CAPTURING,
    STOPPING,
    FAILED,
}
