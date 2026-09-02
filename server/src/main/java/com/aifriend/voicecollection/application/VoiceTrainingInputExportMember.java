package com.aifriend.voicecollection.application;

import java.util.Arrays;
import java.util.UUID;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 训练输入导出阶段读取的冻结成员与当前样本事实。
 *
 * <p>人工复核密文只允许在当前导出调用内短暂存在，使用后必须调用
 * {@link #clearSensitiveData()} 清零。</p>
 *
 * @param sampleId 样本 UUID
 * @param audioObjectId 当前音频对象 UUID
 * @param memberOrder 冻结成员顺序
 * @param frozenSampleVersion 冻结时样本版本
 * @param currentSampleVersion 当前样本版本
 * @param audioSha256 冻结音频 SHA-256
 * @param reviewedTranscriptSha256 冻结人工复核密文 SHA-256
 * @param reviewedTranscriptCipher 当前人工复核密文
 * @param category 冻结采集类别
 * @param promptCode 冻结提示编码
 * @param environment 冻结录音环境
 * @param dialectCode 冻结方言代码
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingInputExportMember(
        UUID sampleId,
        UUID audioObjectId,
        int memberOrder,
        long frozenSampleVersion,
        long currentSampleVersion,
        byte[] audioSha256,
        byte[] reviewedTranscriptSha256,
        byte[] reviewedTranscriptCipher,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode) {

    /**
     * 清零当前对象持有的人工复核密文。
     */
    public void clearSensitiveData() {
        if (reviewedTranscriptCipher != null) {
            Arrays.fill(reviewedTranscriptCipher, (byte) 0);
        }
    }
}
