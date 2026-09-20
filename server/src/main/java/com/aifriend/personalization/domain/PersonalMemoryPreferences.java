package com.aifriend.personalization.domain;

import java.util.Objects;

/**
 * 允许长期保存的有限个人偏好。
 *
 * <p>不接受自由文本，避免误存消息正文、联系人身份或敏感属性。</p>
 *
 * @param speechRate 播报速度
 * @param dialogueStyle 对话详略
 * @param ambiguousCall 含糊通话表达的处理方式
 * @author Codex
 * @since 1.0.0
 */
public record PersonalMemoryPreferences(
        SpeechRatePreference speechRate,
        DialogueStylePreference dialogueStyle,
        AmbiguousCallPreference ambiguousCall) {

    /**
     * 校验三个偏好都由受限枚举给出。
     */
    public PersonalMemoryPreferences {
        Objects.requireNonNull(speechRate, "播报速度偏好不能为空");
        Objects.requireNonNull(dialogueStyle, "对话风格偏好不能为空");
        Objects.requireNonNull(ambiguousCall, "含糊通话偏好不能为空");
    }
}
