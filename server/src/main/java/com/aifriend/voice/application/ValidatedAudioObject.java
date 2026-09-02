package com.aifriend.voice.application;

import java.util.Arrays;
import java.util.UUID;

import com.aifriend.voice.domain.AudioPurpose;

/**
 * 已通过归属、存储版本和内容校验的音频快照。
 *
 * <p>原始音频只留在当前调用内存；该快照仍需在事务内重新锁定元数据版本后才能消费。
 *
 * @param audioObjectId 音频对象 UUID
 * @param ownerUserId 对象所属用户 UUID
 * @param purpose 受限业务用途
 * @param mediaType 媒体类型
 * @param audioContent 已校验原始音频字节
 * @param actualDurationMs 真实解码时长毫秒数
 * @param storageVersion 已复验的对象存储版本
 * @param metadataVersion 事务外读取时的元数据乐观锁版本
 * @author Codex
 * @since 1.0.0
 */
public record ValidatedAudioObject(
        UUID audioObjectId,
        UUID ownerUserId,
        AudioPurpose purpose,
        String mediaType,
        byte[] audioContent,
        int actualDurationMs,
        String storageVersion,
        long metadataVersion)
        implements AutoCloseable {

    /**
     * 创建带防御性音频副本的已校验快照。
     */
    public ValidatedAudioObject {
        audioContent = audioContent.clone();
    }

    /**
     * 获取已校验音频字节副本。
     *
     * @return 已校验音频字节副本
     */
    @Override
    public byte[] audioContent() {
        return audioContent.clone();
    }

    /** 覆盖当前调用持有的已验证音频缓冲。 */
    @Override
    public void close() {
        Arrays.fill(audioContent, (byte) 0);
    }

    /**
     * 返回不包含 owner 和音频内容的诊断文本。
     *
     * @return 脱敏诊断文本
     */
    @Override
    public String toString() {
        return "ValidatedAudioObject[purpose=" + purpose
                + ", mediaType=" + mediaType
                + ", sizeBytes=" + audioContent.length
                + ", actualDurationMs=" + actualDurationMs
                + ", metadataVersion=" + metadataVersion + "]";
    }
}
