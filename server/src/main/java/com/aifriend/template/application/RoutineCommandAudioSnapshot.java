package com.aifriend.template.application;

import java.util.Arrays;

/**
 * 事务外读取并完整复验的短期 TASK 音频快照。
 *
 * <p>原始字节只驻留当前工作器内存，调用方必须使用 {@link #close()} 覆盖缓冲。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class RoutineCommandAudioSnapshot implements AutoCloseable {

    private final String mediaType;
    private final int actualDurationMs;
    private final byte[] audioContent;

    /**
     * 创建带防御性副本的音频快照。
     *
     * @param mediaType 已验证媒体类型
     * @param actualDurationMs 实际解码时长毫秒
     * @param audioContent 原始音频字节
     */
    public RoutineCommandAudioSnapshot(
            String mediaType,
            int actualDurationMs,
            byte[] audioContent) {
        this.mediaType = mediaType;
        this.actualDurationMs = actualDurationMs;
        this.audioContent = audioContent.clone();
    }

    /**
     * 返回媒体类型。
     *
     * @return 已验证媒体类型
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * 返回实际时长。
     *
     * @return 实际解码时长毫秒
     */
    public int actualDurationMs() {
        return actualDurationMs;
    }

    /**
     * 返回原始音频防御性副本。
     *
     * @return 只供当前声学处理使用的字节副本
     */
    public byte[] audioContent() {
        return audioContent.clone();
    }

    /** 覆盖当前工作器持有的原始音频缓冲。 */
    @Override
    public void close() {
        Arrays.fill(audioContent, (byte) 0);
    }
}
