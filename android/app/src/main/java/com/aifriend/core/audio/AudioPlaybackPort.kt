package com.aifriend.core.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * 应用内 WAV 回放端口。回放不创建公共媒体文件。
 *
 * @author codex
 * @since 2026-08-12
 */
interface AudioPlaybackPort {
    val state: StateFlow<AudioPlaybackState>

    /**
     * 回放当前内存中的 PCM WAV。
     *
     * @param wavBytes 待回放的 WAV 字节
     */
    suspend fun play(wavBytes: ByteArray)

    /**
     * 停止当前回放。
     */
    suspend fun stop()

    /**
     * 页面退出或进程内任务作废时，同步关闭当前回放闩。
     */
    fun stopImmediately()
}

/**
 * 音频回放状态。
 */
enum class AudioPlaybackState {
    STOPPED,
    STARTING,
    PLAYING,
    STOPPING,
    FAILED,
}
