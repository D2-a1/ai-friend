package com.aifriend.retention.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * PushPlus App 个人告警通道配置。
 *
 * <p>开关关闭时允许凭据为空并继续使用失败关闭适配器；开启时必须提供个人用户
 * Token 和在 PushPlus 开发设置中生成的 SecretKey。所有远程地址在代码中固定为
 * PushPlus 官方 HTTPS 地址，不允许通过环境变量改向其他主机。</p>
 *
 * @param enabled 是否启用 PushPlus App 告警
 * @param userToken PushPlus 个人用户 Token，不支持消息 Token
 * @param secretKey PushPlus 开放接口 SecretKey
 * @param connectTimeout 建连超时
 * @param readTimeout 读取超时
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.retention.pushplus-alert")
public record PushPlusAlertProperties(
        boolean enabled,
        String userToken,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout) {

    /** PushPlus 官方消息提交地址。 */
    public static final URI SEND_ENDPOINT =
            URI.create("https://www.pushplus.plus/send");
    /** PushPlus 官方 AccessKey 获取地址。 */
    public static final URI ACCESS_KEY_ENDPOINT =
            URI.create("https://www.pushplus.plus/api/common/openApi/getAccessKey");
    /** PushPlus 官方消息结果查询地址。 */
    public static final URI RESULT_ENDPOINT =
            URI.create("https://www.pushplus.plus/api/open/message/sendMessageResult");
    /** PushPlus 官方最近消息查询地址，用于恢复响应丢失的受理流水号。 */
    public static final URI MESSAGE_LIST_ENDPOINT =
            URI.create("https://www.pushplus.plus/api/open/message/list");
    /** 外部调用允许的最大单段超时。 */
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 校验个人告警通道配置。
     */
    public PushPlusAlertProperties {
        userToken = userToken == null ? "" : userToken.trim();
        secretKey = secretKey == null ? "" : secretKey.trim();
        requireTimeout(connectTimeout, "PushPlus 建连超时");
        requireTimeout(readTimeout, "PushPlus 读取超时");
        if (enabled) {
            requireCredential(userToken, 16, 128, "PushPlus 用户 Token");
            requireCredential(secretKey, 32, 256, "PushPlus SecretKey");
        }
    }

    /**
     * 返回不包含个人 Token 和 SecretKey 的脱敏配置说明。
     *
     * @return 仅含开关和超时的安全文本
     */
    @Override
    public String toString() {
        return "PushPlusAlertProperties[enabled=" + enabled
                + ", userToken=***, secretKey=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static void requireTimeout(Duration timeout, String label) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException(label + "必须大于 0 且不超过 10 秒");
        }
    }

    private static void requireCredential(
            String value,
            int minimumLength,
            int maximumLength,
            String label) {
        if (!StringUtils.hasText(value)
                || value.length() < minimumLength
                || value.length() > maximumLength
                || value.startsWith("REPLACE_")
                || value.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new IllegalArgumentException(label + "格式无效");
        }
    }
}