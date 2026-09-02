package com.aifriend.retention.application;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * 个人运维接手 TOTP 身份配置。
 *
 * <p>生产开关关闭时保留失败关闭身份端口；开启时只接受标准 Base32 TOTP
 * 秘密、稳定的匿名主体标识和固定 HTTPS 接手页面根地址。秘密只允许从服务器
 * 外部环境注入，不得进入仓库、日志或网页。</p>
 *
 * @param enabled 是否启用个人 TOTP 运维身份
 * @param subjectId 稳定匿名运维主体标识，不得使用姓名或联系方式
 * @param secretBase32 至少 160 位的标准 Base32 TOTP 秘密
 * @param acknowledgementBaseUrl 公开 HTTPS 接手页面根地址
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.retention.operations-totp")
public record OperationsTotpProperties(
        boolean enabled,
        String subjectId,
        String secretBase32,
        URI acknowledgementBaseUrl) {

    /** 接手页面固定路径。 */
    private static final String ACKNOWLEDGEMENT_BASE_PATH =
            "/api/v1/operations/account-closure-alert-deliveries/";

    /**
     * 规范化并校验个人 TOTP 运维身份配置。
     */
    public OperationsTotpProperties {
        subjectId = subjectId == null ? "" : subjectId.trim();
        secretBase32 = secretBase32 == null
                ? ""
                : secretBase32.replace(" ", "").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(acknowledgementBaseUrl, "运维接手页面根地址不能为空");
        requireBaseUrlShape(acknowledgementBaseUrl);
        if (enabled) {
            requireSubjectId(subjectId);
            requireSecret(secretBase32);
            requireProductionHost(acknowledgementBaseUrl);
        }
    }

    /**
     * 生成只包含随机投递 UUID 的公开接手页面地址。
     *
     * @param deliveryId P0 告警投递 UUID
     * @return 固定 HTTPS 接手页面地址
     */
    public URI acknowledgementUri(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        return URI.create(acknowledgementBaseUrl + deliveryId.toString() + "/acknowledgement");
    }

    /**
     * 返回不包含匿名主体和 TOTP 秘密的脱敏配置。
     *
     * @return 仅含开关和页面根地址的安全文本
     */
    @Override
    public String toString() {
        return "OperationsTotpProperties[enabled=" + enabled
                + ", subjectId=***, secretBase32=***, acknowledgementBaseUrl="
                + acknowledgementBaseUrl + "]";
    }

    private static void requireBaseUrlShape(URI baseUrl) {
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                || !StringUtils.hasText(baseUrl.getHost())
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || (baseUrl.getPort() != -1 && baseUrl.getPort() != 443)
                || !ACKNOWLEDGEMENT_BASE_PATH.equals(baseUrl.getPath())) {
            throw new IllegalArgumentException("运维接手页面必须使用固定路径的 HTTPS 根地址");
        }
    }

    private static void requireProductionHost(URI baseUrl) {
        String host = baseUrl.getHost().toLowerCase(Locale.ROOT);
        if (!host.contains(".")
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("example.com")
                || host.endsWith(".example.com")
                || host.endsWith(".test")
                || host.endsWith(".invalid")
                || host.contains(":")
                || host.chars().allMatch(character ->
                        Character.isDigit(character) || character == '.')) {
            throw new IllegalArgumentException("运维接手页面不得使用占位、回环或 IP 主机");
        }
    }

    private static void requireSubjectId(String subjectId) {
        if (!StringUtils.hasText(subjectId)
                || subjectId.length() < 16
                || subjectId.length() > 128
                || subjectId.startsWith("REPLACE_")
                || !subjectId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("匿名运维主体标识格式无效");
        }
    }

    private static void requireSecret(String secretBase32) {
        if (secretBase32.length() < 32
                || secretBase32.length() > 128
                || !secretBase32.matches("[A-Z2-7]+")) {
            throw new IllegalArgumentException("TOTP Base32 秘密格式无效");
        }
    }
}
