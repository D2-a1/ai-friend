package com.aifriend.voicecollection.application;

import java.time.Duration;
import java.util.Objects;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 封闭测试语音采集配置。
 *
 * @param policyVersion 独立授权政策版本
 * @param trainingPolicyVersion 模型训练独立授权政策版本
 * @param reviewPolicyVersion 人工复核政策版本
 * @param rawAudioMaxAge 原始采集音频最长留存时间
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.voice-collection")
public record VoiceCollectionProperties(
        @NotBlank String policyVersion,
        @NotBlank String trainingPolicyVersion,
        @NotBlank String reviewPolicyVersion,
        @NotNull Duration rawAudioMaxAge) {

    /** 原始采集音频不可突破的三十天硬上限。 */
    private static final Duration MAXIMUM_RAW_AUDIO_AGE = Duration.ofDays(30);

    /**
     * 复验采集政策版本和留存硬上限。
     */
    public VoiceCollectionProperties {
        Objects.requireNonNull(rawAudioMaxAge, "rawAudioMaxAge must not be null");
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 60) {
            throw new IllegalArgumentException("语音采集政策版本不能为空且不得超过 60 字符");
        }
        if (trainingPolicyVersion == null || trainingPolicyVersion.isBlank()
                || trainingPolicyVersion.length() > 60) {
            throw new IllegalArgumentException("模型训练政策版本不能为空且不得超过 60 字符");
        }
        if (reviewPolicyVersion == null || reviewPolicyVersion.isBlank()
                || reviewPolicyVersion.length() > 60) {
            throw new IllegalArgumentException("人工复核政策版本不能为空且不得超过 60 字符");
        }
        if (rawAudioMaxAge.isZero()
                || rawAudioMaxAge.isNegative()
                || rawAudioMaxAge.compareTo(MAXIMUM_RAW_AUDIO_AGE) > 0) {
            throw new IllegalArgumentException("采集原始音频留存时间必须大于 0 且不超过 30 天");
        }
    }
}
