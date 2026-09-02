package com.aifriend.contact.application;

/**
 * 仅在当前注册调用内存中存在的已校验声学样本。
 *
 * @param mediaType 已实际解码的媒体类型
 * @param audioContent 原始音频字节，不得持久化或记录日志
 * @param actualDurationMs 实际解码时长
 * @author Codex
 * @since 1.0.0
 */
public record AcousticEnrollmentSample(
        String mediaType,
        byte[] audioContent,
        int actualDurationMs) {

    /** 创建带防御性音频副本的样本。 */
    public AcousticEnrollmentSample {
        audioContent = audioContent.clone();
    }

    /**
     * 获取原始音频字节的防御性副本。
     *
     * @return 原始音频字节副本
     */
    @Override
    public byte[] audioContent() {
        return audioContent.clone();
    }

    /**
     * 返回不包含原始音频的诊断文本。
     *
     * @return 不包含原始音频的诊断文本
     */
    @Override
    public String toString() {
        return "AcousticEnrollmentSample[mediaType=" + mediaType
                + ", sizeBytes=" + audioContent.length
                + ", actualDurationMs=" + actualDurationMs + "]";
    }
}
