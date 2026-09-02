package com.aifriend.identity.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

/**
 * 生产微信移动应用身份兑换配置。
 *
 * <p>开关关闭时允许凭据为空并由不可用适配器失败关闭；开关开启时必须提供
 * AppID、AppSecret 和固定微信官方 HTTPS 端点。端点不可改向其他主机，避免
 * 一次性 code 与 AppSecret 被发送到非微信服务。
 *
 * @param enabled 是否启用生产微信身份兑换
 * @param appId 微信开放平台移动应用 AppID
 * @param appSecret 微信开放平台移动应用 AppSecret
 * @param tokenEndpoint 微信官方 code 兑换端点
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.wechat.identity")
public record WechatIdentityProperties(
        boolean enabled,
        String appId,
        String appSecret,
        URI tokenEndpoint,
        Duration connectTimeout,
        Duration readTimeout) {

    /** 微信官方 OAuth code 兑换端点。 */
    public static final URI OFFICIAL_TOKEN_ENDPOINT =
            URI.create("https://api.weixin.qq.com/sns/oauth2/access_token");
    /** 外部微信调用允许的最大单段超时。 */
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 校验生产身份配置的固定安全边界。
     */
    public WechatIdentityProperties {
        Objects.requireNonNull(tokenEndpoint, "微信身份兑换端点不能为空");
        Objects.requireNonNull(connectTimeout, "微信身份建连超时不能为空");
        Objects.requireNonNull(readTimeout, "微信身份读取超时不能为空");
        requireOfficialEndpoint(tokenEndpoint);
        requireTimeout(connectTimeout);
        requireTimeout(readTimeout);
        if (enabled) {
            requireCredential(appId);
            requireCredential(appSecret);
        }
    }

    /**
     * 返回不包含 AppID 与 AppSecret 的脱敏配置说明。
     *
     * @return 仅包含开关、固定端点和超时的安全文本
     */
    @Override
    public String toString() {
        return "WechatIdentityProperties[enabled=" + enabled
                + ", appId=***, appSecret=***, tokenEndpoint=" + tokenEndpoint
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static void requireOfficialEndpoint(URI endpoint) {
        if (!OFFICIAL_TOKEN_ENDPOINT.equals(endpoint)) {
            throw new IllegalArgumentException("微信身份兑换端点必须使用固定官方 HTTPS 地址");
        }
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("微信身份调用超时必须大于 0 且不超过 5 秒");
        }
    }

    private static void requireCredential(String value) {
        if (!StringUtils.hasText(value)
                || value.length() > 128
                || value.startsWith("REPLACE_")
                || value.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new IllegalArgumentException("微信身份凭据格式无效");
        }
    }
}
