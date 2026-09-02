package com.aifriend.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建任务客户端版本上下文请求。
 *
 * @param appVersion App 版本
 * @param wechatVersion 微信版本
 * @param ruleVersion 微信规则版本
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param mandarinAssistVersion 普通话辅助版本
 * @param fusionRuleVersion 融合规则版本
 * @param templateModelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @author Codex
 * @since 1.0.0
 */
public record TaskClientContextReq(
        @NotBlank @Size(max = 40) String appVersion,
        @NotBlank @Size(max = 40) String wechatVersion,
        @NotBlank @Size(max = 40) String ruleVersion,
        @NotBlank @Size(min = 2, max = 40) String dialectCode,
        @NotBlank @Size(max = 60) String dialectPackageVersion,
        @NotBlank @Size(max = 60) String mandarinAssistVersion,
        @NotBlank @Size(max = 60) String fusionRuleVersion,
        @NotBlank @Size(max = 60) String templateModelVersion,
        @NotBlank @Size(max = 60) String thresholdVersion) {
}
