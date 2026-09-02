package com.aifriend.voicecollection.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.aifriend.consent.domain.ConsentDecision;

/**
 * 单条测试语音样本训练授权请求。
 *
 * @param decision 授予或撤回决定
 * @param confirmed 用户明确确认，必须为 true
 * @param policyVersion 模型训练政策版本
 * @param expectedVersion 客户端最后读取的样本版本
 * @author codex
 * @since 1.0.0
 */
public record UpdateVoiceCollectionTrainingAuthorizationReq(
        @NotNull ConsentDecision decision,
        @AssertTrue boolean confirmed,
        @NotBlank @Size(max = 60) String policyVersion,
        @PositiveOrZero long expectedVersion) {
}
