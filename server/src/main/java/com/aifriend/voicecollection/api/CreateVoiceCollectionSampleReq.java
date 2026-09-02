package com.aifriend.voicecollection.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 创建封闭测试语音样本请求。
 *
 * @param audioObjectId TEST_VOICE_COLLECTION 用途音频对象
 * @param category 有限样本类别
 * @param promptCode 固定提示编码，不含正文
 * @param environment 有限环境分类
 * @param dialectCode 方言代码
 * @param consentPolicyVersion 独立授权政策版本
 * @param reviewedTranscript 本机试听后人工核对的实际发音文字
 * @param reviewConfirmed 必须为 true 的人工复核确认
 * @param reviewPolicyVersion 人工复核政策版本
 * @author Codex
 * @since 1.0.0
 */
public record CreateVoiceCollectionSampleReq(
        @NotBlank @Pattern(regexp = "au_[A-Fa-f0-9]{32}") String audioObjectId,
        @NotNull VoiceCollectionCategory category,
        @NotBlank @Pattern(regexp = "[a-z0-9_]{3,64}") String promptCode,
        @NotNull VoiceCollectionEnvironment environment,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{2,40}") String dialectCode,
        @NotBlank @Size(max = 60) String consentPolicyVersion,
        @NotBlank @Size(max = 120) String reviewedTranscript,
        @AssertTrue boolean reviewConfirmed,
        @NotBlank @Size(max = 60) String reviewPolicyVersion) {
}
