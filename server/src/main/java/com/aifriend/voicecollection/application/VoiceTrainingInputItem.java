package com.aifriend.voicecollection.application;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 写入本机训练工作区的单条非敏感元数据。
 *
 * @param memberOrder 数据集成员顺序
 * @param category 采集类别
 * @param promptCode 固定提示编码
 * @param environment 录音环境
 * @param dialectCode 方言代码
 * @param split 训练或验证分区
 * @param durationMs 服务端解码得到的音频时长毫秒数
 * @param audioSha256 音频 SHA-256
 * @param transcriptSha256 人工复核明文 UTF-8 SHA-256
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingInputItem(
        int memberOrder,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode,
        VoiceTrainingInputSplit split,
        int durationMs,
        byte[] audioSha256,
        byte[] transcriptSha256) {

    /**
     * 创建带防御性摘要副本的训练输入元数据。
     */
    public VoiceTrainingInputItem {
        audioSha256 = audioSha256.clone();
        transcriptSha256 = transcriptSha256.clone();
    }

    /**
     * 获取音频摘要副本。
     *
     * @return 音频 SHA-256 副本
     */
    @Override
    public byte[] audioSha256() {
        return audioSha256.clone();
    }

    /**
     * 获取人工复核明文摘要副本。
     *
     * @return 人工复核明文 SHA-256 副本
     */
    @Override
    public byte[] transcriptSha256() {
        return transcriptSha256.clone();
    }
}
