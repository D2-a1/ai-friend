package com.aifriend.personalization.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.SpeechRatePreference;

/**
 * 创建或更正有限长期个人偏好的请求。
 *
 * @param speechRate 播报速度
 * @param dialogueStyle 对话详略
 * @param ambiguousCall 含糊通话表达处理方式
 * @param expectedVersion 首次创建为 0，更正时为当前版本
 * @author Codex
 * @since 1.0.0
 */
public record UpdatePersonalMemoryReq(
        @NotNull SpeechRatePreference speechRate,
        @NotNull DialogueStylePreference dialogueStyle,
        @NotNull AmbiguousCallPreference ambiguousCall,
        @PositiveOrZero long expectedVersion) {
}
