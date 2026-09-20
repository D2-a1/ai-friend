package com.aifriend.retrieval.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立问答生成配置；不读取旧任务模型或Embedding凭据。启用不等于质量已通过。
 * @param enabled 默认关闭
 * @param endpoint 完整HTTPS Chat Completions地址
 * @param allowedHosts 精确公网ASCII主机
 * @param model 固定模型名称
 * @param apiKey 仓库外密钥
 * @param profileId 配置版本
 * @param qualityReportId 显式质量报告标识，报告内容需上线前独立验收
 * @param tokenLimitField 输出Token字段方言
 * @param maxOutputTokens 最大输出Token
 * @param thinkingMode 外部配置的思考字段方言
 * @param temperature 可选温度；null不发送
 * @param connectTimeout 连接超时
 * @param readTimeout 响应超时
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.chat")
public record KnowledgeChatProperties(boolean enabled, URI endpoint, Set<String> allowedHosts,
        String model, String apiKey, String profileId, String qualityReportId, TokenLimitField tokenLimitField,
        int maxOutputTokens, ThinkingMode thinkingMode, Double temperature, Duration connectTimeout, Duration readTimeout) {
    /** 只支持显式选择的通用字段，不按供应商主机判断。 */
    public enum TokenLimitField {
        /** max_tokens。 */ MAX_TOKENS,
        /** max_completion_tokens。 */ MAX_COMPLETION_TOKENS
    }
    /** 不依赖供应商域名的请求方言。 */
    public enum ThinkingMode {
        /** 不发送。 */ OMIT,
        /** enable_thinking=false。 */ DISABLED,
        /** enable_thinking=true。 */ ENABLED,
        /** thinking.type=disabled。 */ OBJECT_DISABLED,
        /** thinking.type=enabled。 */ OBJECT_ENABLED
    }

    /** 开启时成组校验；错误不回显配置值。 */
    public KnowledgeChatProperties {
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        if (enabled) {
            if (allowedHosts.isEmpty() || allowedHosts.size() > 8 || allowedHosts.stream().anyMatch(h -> !host(h))) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_CHAT_HOSTS");
            }
            allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
            if (endpoint == null || !"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || !allowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))
                    || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                    || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || endpoint.getRawPath() == null || !endpoint.getRawPath().endsWith("/chat/completions")
                    || endpoint.getRawPath().length() > 512 || endpoint.getRawPath().contains("%")
                    || endpoint.getRawPath().contains("//") || !endpoint.normalize().equals(endpoint)) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_CHAT_ENDPOINT");
            }
            if (!ascii(model, 1, 120) || !ascii(apiKey, 8, 512) || !identifier(profileId) || !identifier(qualityReportId)
                    || tokenLimitField == null || thinkingMode == null || maxOutputTokens < 1 || maxOutputTokens > 2048
                    || (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 1))
                    || !timeout(connectTimeout) || !timeout(readTimeout)) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_CHAT_CONFIGURATION");
            }
        }
    }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,100}"); }
    private static boolean ascii(String value, int min, int max) {
        return value != null && value.length() >= min && value.length() <= max && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
    private static boolean timeout(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(Duration.ofSeconds(4)) <= 0;
    }
    private static boolean host(String host) {
        if (host == null || host.length() > 253) { return false; }
        String value = host.toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
                && !value.matches("[0-9.]+") && !value.endsWith(".localhost") && !value.endsWith(".local")
                && !value.endsWith(".internal") && !value.endsWith(".test") && !value.endsWith(".invalid")
                && !value.endsWith(".example") && !value.equals("example.com") && !value.endsWith(".example.com");
    }
    /** 不泄露供应商地址、密钥或模型名。 */
    @Override public String toString() { return "KnowledgeChatProperties[enabled=" + enabled + ", values=redacted]"; }
}
