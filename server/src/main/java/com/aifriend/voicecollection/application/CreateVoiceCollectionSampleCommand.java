package com.aifriend.voicecollection.application;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 创建封闭测试语音样本命令。
 *
 * @param audioObjectId au_ 前缀音频对象编号
 * @param category 有限采集类别
 * @param promptCode 客户端固定提示编码，不含提示正文或转写
 * @param environment 有限环境分类
 * @param dialectCode 方言代码
 * @param consentPolicyVersion 独立授权政策版本
 * @param reviewedTranscript 本机试听后人工核对的实际发音文字
 * @param reviewConfirmed 是否已经明确确认人工复核
 * @param reviewPolicyVersion 人工复核政策版本
 * @author Codex
 * @since 1.0.0
 */
public record CreateVoiceCollectionSampleCommand(
        String audioObjectId,
        VoiceCollectionCategory category,
        String promptCode,
        VoiceCollectionEnvironment environment,
        String dialectCode,
        String consentPolicyVersion,
        String reviewedTranscript,
        boolean reviewConfirmed,
        String reviewPolicyVersion) {
}
