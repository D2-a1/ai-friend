package com.aifriend.template.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.aifriend.template.domain.SafetyCommandType;

/**
 * 一类安全指令的双录请求 DTO。
 *
 * @param type 固定安全指令类型
 * @param firstAudioObjectId 第一遍 au_ 音频对象编号
 * @param secondAudioObjectId 第二遍 au_ 音频对象编号
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollmentItemReq(
        @NotNull SafetyCommandType type,
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String firstAudioObjectId,
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String secondAudioObjectId) {
}
