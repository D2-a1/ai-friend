package com.aifriend.invitation.application;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * 生产微信邀请网页 OAuth 配置。
 *
 * <p>邀请网页 OAuth 使用独立于移动应用登录的公众号 AppID/AppSecret。授权入口和
 * code 兑换端点固定为微信官方 HTTPS 地址；生产回调地址必须由服务器环境注入，
 * 且不得使用占位、回环或 IP 字面量主机。
 *
 * @param enabled 是否启用生产邀请网页 OAuth
 * @param appId 微信公众号网页授权 AppID
 * @param appSecret 微信公众号网页授权 AppSecret
 * @param authorizationEndpoint 微信官方网页授权入口
 * @param tokenEndpoint 微信官方 code 兑换端点
 * @param callbackUrl 对外 HTTPS 回调地址
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.wechat.invitation-oauth")
public record WechatInvitationOAuthProperties(
        boolean enabled,
        String appId,
        String appSecret,
        URI authorizationEndpoint,
        URI tokenEndpoint,
        URI callbackUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    /** 微信官方网页授权入口。 */
    public static final URI OFFICIAL_AUTHORIZATION_ENDPOINT =
            URI.create("https://open.weixin.qq.com/connect/oauth2/authorize");
    /** 微信官方网页授权 code 兑换端点。 */
    public static final URI OFFICIAL_TOKEN_ENDPOINT =
            URI.create("https://api.weixin.qq.com/sns/oauth2/access_token");
    /** 微信邀请 OAuth 回调路径后缀。 */
    private static final String CALLBACK_PATH_SUFFIX = "/oauth/wechat/invitation-callback";
    /** 外部微信调用允许的最大单段超时。 */
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 校验生产邀请网页 OAuth 配置的固定安全边界。
     */
    public WechatInvitationOAuthProperties {
        Objects.requireNonNull(authorizationEndpoint, "微信邀请授权端点不能为空");
        Objects.requireNonNull(tokenEndpoint, "微信邀请身份端点不能为空");
        Objects.requireNonNull(callbackUrl, "微信邀请回调地址不能为空");
        Objects.requireNonNull(connectTimeout, "微信邀请建连超时不能为空");
        Objects.requireNonNull(readTimeout, "微信邀请读取超时不能为空");
        requireOfficialEndpoint(authorizationEndpoint, OFFICIAL_AUTHORIZATION_ENDPOINT);
        requireOfficialEndpoint(tokenEndpoint, OFFICIAL_TOKEN_ENDPOINT);
        requireCallbackShape(callbackUrl);
        requireTimeout(connectTimeout);
        requireTimeout(readTimeout);
        if (enabled) {
            requireCredential(appId);
            requireCredential(appSecret);
            requireProductionCallback(callbackUrl);
        }
    }

    /**
     * 返回不包含 AppID 与 AppSecret 的脱敏配置说明。
     *
     * @return 仅包含开关、固定端点、回调地址和超时的安全文本
     */
    @Override
    public String toString() {
        return "WechatInvitationOAuthProperties[enabled=" + enabled
                + ", appId=***, appSecret=***, authorizationEndpoint=" + authorizationEndpoint
                + ", tokenEndpoint=" + tokenEndpoint
                + ", callbackUrl=" + callbackUrl
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static void requireOfficialEndpoint(URI actual, URI expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("微信邀请 OAuth 端点必须使用固定官方 HTTPS 地址");
        }
    }

    private static void requireCallbackShape(URI callbackUrl) {
        String path = callbackUrl.getPath();
        if (!"https".equalsIgnoreCase(callbackUrl.getScheme())
                || !StringUtils.hasText(callbackUrl.getHost())
                || callbackUrl.getUserInfo() != null
                || callbackUrl.getQuery() != null
                || callbackUrl.getFragment() != null
                || (callbackUrl.getPort() != -1 && callbackUrl.getPort() != 443)
                || path == null
                || !path.endsWith(CALLBACK_PATH_SUFFIX)) {
            throw new IllegalArgumentException("微信邀请回调必须是固定路径的 HTTPS 地址");
        }
    }

    private static void requireProductionCallback(URI callbackUrl) {
        String host = callbackUrl.getHost().toLowerCase(Locale.ROOT);
        if (!host.contains(".")
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("example.com")
                || host.endsWith(".example.com")
                || host.endsWith(".test")
                || host.endsWith(".invalid")
                || host.contains(":")
                || host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
            throw new IllegalArgumentException("微信邀请回调地址不得使用占位、回环或 IP 主机");
        }
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("微信邀请 OAuth 超时必须大于 0 且不超过 5 秒");
        }
    }

    private static void requireCredential(String value) {
        if (!StringUtils.hasText(value)
                || value.length() > 128
                || value.startsWith("REPLACE_")
                || value.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new IllegalArgumentException("微信邀请 OAuth 凭据格式无效");
        }
    }
}
