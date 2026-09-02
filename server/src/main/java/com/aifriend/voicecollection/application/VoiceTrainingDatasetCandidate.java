package com.aifriend.voicecollection.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 训练数据集选择阶段的当前样本事实。
 *
 * <p>人工复核密文只在本次短事务内用于计算不可逆摘要，使用后必须调用
 * {@link #clearSensitiveData()} 清零。</p>
 *
 * @param sampleId 样本 UUID
 * @param audioObjectId 音频对象 UUID
 * @param sampleVersion 样本乐观锁版本
 * @param category 采集类别
 * @param promptCode 固定提示编码
 * @param environment 录音环境
 * @param dialectCode 方言代码
 * @param audioSha256 音频内容 SHA-256
 * @param reviewedTranscriptCipher 人工复核文字密文
 * @param reviewedAt 人工复核时间
 * @param retentionUntil 样本留存截止时间
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingDatasetCandidate(
        UUID sampleId,
        UUID audioObjectId,
        long sampleVersion,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode,
        byte[] audioSha256,
        byte[] reviewedTranscriptCipher,
        Instant reviewedAt,
        Instant retentionUntil) {

    /**
     * 清零当前对象持有的人工复核密文字节。
     */
    public void clearSensitiveData() {
        if (reviewedTranscriptCipher != null) {
            Arrays.fill(reviewedTranscriptCipher, (byte) 0);
        }
    }
}
