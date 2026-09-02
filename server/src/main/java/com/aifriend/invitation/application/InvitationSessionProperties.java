package com.aifriend.invitation.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * 受限邀请会话 Cookie 与固定有效期配置。
 *
 * @param cookieName 必须满足 __Host- 约束的 Cookie 名
 * @param ttl 固定会话有效期
 * @param consentPolicyVersion 亲友同意的当前政策版本
 * @param continuationUrl OAuth 成功后的无敏感参数页面
 * @param unavailableUrl OAuth 失败后的统一不可用页面
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.invitation-session")
public record InvitationSessionProperties(
        @NotBlank String cookieName,
        @NotNull Duration ttl,
        @NotBlank String consentPolicyVersion,
        @NotNull URI continuationUrl,
        @NotNull URI unavailableUrl) {

    /**
     * 使用固定开发默认值创建配置，供隔离单元测试使用。
     *
     * @param cookieName Cookie 名
     * @param ttl 固定有效期
     */
    public InvitationSessionProperties(String cookieName, Duration ttl) {
        this(cookieName, ttl, "invitation-consent-v1",
                URI.create("https://invite.example.com/invite/continue"),
                URI.create("https://invite.example.com/invite/unavailable"));
    }

    /**
     * 校验不可被环境配置弱化的 Cookie 和期限边界。
     *
     * @throws NullPointerException 当 Cookie 名或期限为空时抛出
     * @throws IllegalArgumentException 当 Cookie 名或期限不符合冻结契约时抛出
     */
    @ConstructorBinding
    public InvitationSessionProperties {
        Objects.requireNonNull(cookieName, "cookieName must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(consentPolicyVersion, "consentPolicyVersion must not be null");
        Objects.requireNonNull(continuationUrl, "continuationUrl must not be null");
        Objects.requireNonNull(unavailableUrl, "unavailableUrl must not be null");
        if (!"__Host-ai_friend_invitation".equals(cookieName)) {
            throw new IllegalArgumentException("邀请会话 Cookie 名不得偏离 OpenAPI 契约");
        }
        if (!Duration.ofMinutes(30).equals(ttl)) {
            throw new IllegalArgumentException("邀请会话有效期必须固定为 30 分钟");
        }
        if (consentPolicyVersion.isBlank() || consentPolicyVersion.length() > 40) {
            throw new IllegalArgumentException("邀请同意政策版本长度必须为 1—40");
        }
        validateRedirect(continuationUrl);
        validateRedirect(unavailableUrl);
    }

    private static void validateRedirect(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("邀请回跳地址必须是无敏感查询和 fragment 的 HTTPS 地址");
        }
    }
}
