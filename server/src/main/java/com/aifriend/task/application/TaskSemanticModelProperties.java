package com.aifriend.task.application;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * 上下文任务语义模型网关配置。
 *
 * <p>供应商、端点、模型和请求方言全部由服务器外部配置注入。开关默认关闭；启用时
 * endpoint 主机必须与 allowedHosts 中的一个精确值一致，防止配置误指向未授权地址。
 * 模型只能生成有限任务草稿补丁，不能确认任务或签发微信动作。
 *
 * @param enabled 是否启用真实语义模型
 * @param endpoint Chat Completions 兼容 HTTPS 端点
 * @param allowedHosts 允许接收短期文本上下文的精确主机集合
 * @param model 模型部署名称
 * @param apiKey Bearer 凭据
 * @param tokenLimitField 输出 Token 上限字段
 * @param maxOutputTokens 最大输出 Token 数
 * @param temperature 生成温度
 * @param thinkingMode 供应商思考开关字段策略
 * @param connectTimeout 建连超时
 * @param readTimeout 单次读取超时
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.task-semantic-model")
public record TaskSemanticModelProperties(
        boolean enabled,
        URI endpoint,
        Set<String> allowedHosts,
        String model,
        String apiKey,
        TokenLimitField tokenLimitField,
        int maxOutputTokens,
        double temperature,
        ThinkingMode thinkingMode,
        Duration connectTimeout,
        Duration readTimeout) {

    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern DNS_HOST = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");

    /** 可配置的 Chat Completions 输出上限字段。 */
    public enum TokenLimitField {
        /** 发送 {@code max_tokens}。 */
        MAX_TOKENS,
        /** 发送 {@code max_completion_tokens}。 */
        MAX_COMPLETION_TOKENS
    }

    /** 可配置的兼容端点思考字段策略。 */
    public enum ThinkingMode {
        /** 不发送 {@code enable_thinking} 字段。 */
        OMIT,
        /** 发送 {@code enable_thinking=false}。 */
        DISABLED,
        /** 发送 {@code enable_thinking=true}。 */
        ENABLED,
        /** 发送通用对象形式 {@code thinking.type=disabled}。 */
        OBJECT_DISABLED,
        /** 发送通用对象形式 {@code thinking.type=enabled}。 */
        OBJECT_ENABLED
    }

    /** 校验模型网关的固定安全边界。 */
    public TaskSemanticModelProperties {
        Objects.requireNonNull(endpoint, "语义模型端点不能为空");
        Objects.requireNonNull(tokenLimitField, "语义模型 Token 字段不能为空");
        Objects.requireNonNull(thinkingMode, "语义模型思考模式不能为空");
        Objects.requireNonNull(connectTimeout, "语义模型建连超时不能为空");
        Objects.requireNonNull(readTimeout, "语义模型读取超时不能为空");
        allowedHosts = normalizeAllowedHosts(allowedHosts);
        requireEndpointShape(endpoint);
        requireOutputSettings(maxOutputTokens, temperature);
        requireTimeout(connectTimeout);
        requireTimeout(readTimeout);
        if (enabled) {
            requireApprovedEndpoint(endpoint, allowedHosts);
            requirePrintable(model, 1, 120, "语义模型名称格式无效");
            requirePrintable(apiKey, 8, 512, "语义模型凭据格式无效");
        }
    }

    /** 返回不泄漏模型凭据的配置摘要。 */
    @Override
    public String toString() {
        return "TaskSemanticModelProperties[enabled=" + enabled
                + ", endpoint=" + endpoint
                + ", allowedHosts=" + allowedHosts
                + ", model=" + model
                + ", apiKey=***, tokenLimitField=" + tokenLimitField
                + ", maxOutputTokens=" + maxOutputTokens
                + ", temperature=" + temperature
                + ", thinkingMode=" + thinkingMode
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }

    private static Set<String> normalizeAllowedHosts(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.size() > 8) {
            throw new IllegalArgumentException("语义模型允许主机数量不能超过 8 个");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String host = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if (host.length() > 253 || !DNS_HOST.matcher(host).matches()) {
                throw new IllegalArgumentException("语义模型允许主机必须是精确 ASCII DNS 主机名");
            }
            normalized.add(host);
        }
        return Set.copyOf(normalized);
    }

    private static void requireEndpointShape(URI endpoint) {
        String rawPath = endpoint.getRawPath();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !StringUtils.hasText(endpoint.getHost())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || !StringUtils.hasText(rawPath)
                || rawPath.length() > 512
                || rawPath.contains("%")
                || rawPath.contains("//")
                || !rawPath.endsWith("/chat/completions")) {
            throw new IllegalArgumentException(
                    "语义模型必须使用无查询参数的 HTTPS Chat Completions 端点");
        }
    }

    private static void requireApprovedEndpoint(URI endpoint, Set<String> allowedHosts) {
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        boolean ipLiteral = host.contains(":")
                || host.chars().allMatch(character ->
                        Character.isDigit(character) || character == '.');
        if (host.equals("localhost")
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal")
                || host.endsWith(".example.com")
                || host.endsWith(".example.invalid")
                || host.endsWith(".test")
                || host.endsWith(".invalid")
                || ipLiteral) {
            throw new IllegalArgumentException("语义模型端点不得使用占位、回环、内网或 IP 主机");
        }
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException(
                    "语义模型端点主机必须精确列入外部 allowed-hosts 配置");
        }
    }

    private static void requireOutputSettings(int maxOutputTokens, double temperature) {
        if (maxOutputTokens < 32 || maxOutputTokens > 1_024) {
            throw new IllegalArgumentException("语义模型最大输出 Token 必须在 32 到 1024 之间");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0D || temperature > 1.0D) {
            throw new IllegalArgumentException("语义模型温度必须在 0 到 1 之间");
        }
    }

    private static void requireTimeout(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0) {
            throw new IllegalArgumentException("语义模型超时必须大于 0 且不超过 10 秒");
        }
    }

    private static void requirePrintable(
            String value,
            int minimum,
            int maximum,
            String message) {
        if (!StringUtils.hasText(value)
                || value.length() < minimum
                || value.length() > maximum
                || value.startsWith("REPLACE_")
                || value.chars().anyMatch(character ->
                        character <= 0x20 || character > 0x7E)) {
            throw new IllegalArgumentException(message);
        }
    }
}
