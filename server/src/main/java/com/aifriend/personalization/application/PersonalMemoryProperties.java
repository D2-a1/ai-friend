package com.aifriend.personalization.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 可选长期个人偏好配置。
 *
 * @param enabled 是否允许创建或更正长期偏好
 * @param policyVersion 当前必须精确同意的政策版本
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.personal-memory")
public record PersonalMemoryProperties(boolean enabled, String policyVersion) {

    /**
     * 校验政策版本始终存在；即使能力关闭，管理页面也需要展示它。
     */
    public PersonalMemoryProperties {
        if (!StringUtils.hasText(policyVersion)
                || policyVersion.length() > 40
                || !policyVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("长期个人偏好政策版本格式无效");
        }
    }
}
