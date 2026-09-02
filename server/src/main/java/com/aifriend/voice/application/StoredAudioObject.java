package com.aifriend.voice.application;

import java.util.Arrays;

/**
 * 已按指定版本读取的私有音频对象。
 *
 * <p>音频字节使用防御性副本，诊断字符串只显示大小和版本，不得暴露内容。
 *
 * @param storageVersion 实际读取到的对象存储版本
 * @param audioContent 原始音频字节，仅限当前调用内存
 * @author Codex
 * @since 1.0.0
 */
public record StoredAudioObject(String storageVersion, byte[] audioContent)
        implements AutoCloseable {

    /**
     * 创建带防御性字节副本的存储对象。
     */
    public StoredAudioObject {
        audioContent = audioContent.clone();
    }

    /**
     * 获取原始音频字节副本。
     *
     * @return 原始音频字节副本
     */
    @Override
    public byte[] audioContent() {
        return audioContent.clone();
    }

    /** 覆盖当前调用持有的对象存储音频缓冲。 */
    @Override
    public void close() {
        Arrays.fill(audioContent, (byte) 0);
    }

    /**
     * 返回不包含音频内容的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "StoredAudioObject[storageVersion=" + storageVersion
                + ", sizeBytes=" + audioContent.length + "]";
    }
}
