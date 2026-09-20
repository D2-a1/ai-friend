package com.aifriend.personalization.api;

import com.aifriend.personalization.domain.AmbiguousCallPreference;
import com.aifriend.personalization.domain.DialogueStylePreference;
import com.aifriend.personalization.domain.SpeechRatePreference;

/**
 * 用户可查看的有限长期偏好。
 *
 * @param speechRate 播报速度
 * @param dialogueStyle 对话详略
 * @param ambiguousCall 含糊通话表达处理方式
 * @author Codex
 * @since 1.0.0
 */
public record PersonalMemoryPreferencesResp(
        SpeechRatePreference speechRate,
        DialogueStylePreference dialogueStyle,
        AmbiguousCallPreference ambiguousCall) {
}
