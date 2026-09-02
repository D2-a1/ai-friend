package com.aifriend.invitation.application;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 邀请 OAuth 回调应用层固定限流配置。
 *
 * <p>该限制按邀请会话摘要计数，是公网网关来源地址限流之后的第二层保护。
 * 参数固定，防止通过环境变量静默放宽安全边界。
 *
 * @param maximumAttempts 每个时间窗允许的最大尝试次数
 * @param window 固定计数时间窗
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.invitation-oauth-rate-limit")
public record InvitationOAuthRateLimitProperties(
        int maximumAttempts,
        Duration window) {

    /** 固定允许的最大尝试次数。 */
    private static final int REQUIRED_MAXIMUM_ATTEMPTS = 5;
    /** 固定计数时间窗。 */
    private static final Duration REQUIRED_WINDOW = Duration.ofMinutes(5);

    /**
     * 校验不可被部署配置放宽的限流边界。
     */
    public InvitationOAuthRateLimitProperties {
        Objects.requireNonNull(window, "邀请 OAuth 限流时间窗不能为空");
        if (maximumAttempts != REQUIRED_MAXIMUM_ATTEMPTS
                || !REQUIRED_WINDOW.equals(window)) {
            throw new IllegalArgumentException("邀请 OAuth 限流必须固定为每五分钟五次");
        }
    }
}
