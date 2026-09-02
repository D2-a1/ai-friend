package com.aifriend.template.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 四类安全指令整批注册请求 DTO。
 *
 * @param commands 恰好四类指令，每类两遍录音
 * @param consentPolicyVersion 客户端明确同意的语音模板政策版本
 * @author Codex
 * @since 1.0.0
 */
public record SafetyCommandEnrollmentReq(
        @NotNull @Size(min = 4, max = 4) List<@Valid SafetyCommandEnrollmentItemReq> commands,
        @NotBlank @Size(max = 40) String consentPolicyVersion) {
}
