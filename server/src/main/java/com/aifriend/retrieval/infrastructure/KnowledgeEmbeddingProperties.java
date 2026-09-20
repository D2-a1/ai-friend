package com.aifriend.retrieval.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.aifriend.retrieval.domain.EmbeddingProfile;

/**
 * 独立Embedding配置；关闭时允许空配置，启用须成组填写，绝不读取任务模型配置。
 * @param enabled 向量调用开关，默认false
 * @param endpoint 完整HTTPS embeddings地址
 * @param allowedHosts 精确域名集合
 * @param model 部署模型名称
 * @param apiKey 仓库外凭据
 * @param dimension 预期维度
 * @param profileId 不可变向量空间版本
 * @param batchSize 单次输入上限
 * @param connectTimeout 连接预算
 * @param readTimeout 响应预算
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.embedding")
public record KnowledgeEmbeddingProperties(boolean enabled, URI endpoint, Set<String> allowedHosts,
        String model, String apiKey, int dimension, String profileId, int batchSize,
        Duration connectTimeout, Duration readTimeout) {
    /** 启用时校验所有配置；错误不包含配置值。 */
    public KnowledgeEmbeddingProperties {
        allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
        if (enabled) {
            if (allowedHosts.isEmpty() || allowedHosts.size() > 8
                    || allowedHosts.stream().anyMatch(host -> !validHost(host))) {
                throw new IllegalArgumentException("INVALID_MODEL_HOSTS");
            }
            allowedHosts = allowedHosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
            if (endpoint == null || !"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || !allowedHosts.contains(endpoint.getHost().toLowerCase(Locale.ROOT))
                    || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                    || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || endpoint.getRawPath() == null || !endpoint.getRawPath().endsWith("/embeddings")
                    || endpoint.getRawPath().length() > 512 || endpoint.getRawPath().contains("%")
                    || endpoint.getRawPath().contains("//") || !endpoint.normalize().equals(endpoint)) {
                throw new IllegalArgumentException("INVALID_MODEL_ENDPOINT");
            }
            if (!printable(model, 1, 120) || !printable(apiKey, 8, 512)
                    || batchSize < 1 || batchSize > 64 || !timeout(connectTimeout) || !timeout(readTimeout)) {
                throw new IllegalArgumentException("INVALID_EMBEDDING_CONFIGURATION");
            }
            new EmbeddingProfile(profileId, dimension);
        }
    }

    /**
     * 从已验证配置构造向量空间描述。
     * @return 当前配置绑定的向量空间；关闭时不可调用
     */
    public EmbeddingProfile profile() {
        if (!enabled) { throw new IllegalStateException("EMBEDDING_DISABLED"); }
        return new EmbeddingProfile(profileId, dimension);
    }

    private static boolean printable(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum
                && value.chars().allMatch(point -> point >= 33 && point <= 126);
    }

    private static boolean timeout(Duration value) {
        return value != null && value.compareTo(Duration.ofMillis(1)) >= 0 && value.compareTo(Duration.ofSeconds(8)) <= 0;
    }

    private static boolean validHost(String host) {
        if (host == null || host.length() > 253) { return false; }
        String value = host.toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")
                && !value.matches("[0-9.]+") && !value.endsWith(".localhost") && !value.endsWith(".local")
                && !value.endsWith(".internal") && !value.endsWith(".test") && !value.endsWith(".invalid")
                && !value.endsWith(".example") && !value.equals("example.com") && !value.endsWith(".example.com");
    }

    /** 不回显地址、模型名或凭据。 */
    @Override public String toString() { return "KnowledgeEmbeddingProperties[enabled=" + enabled + ", values=redacted]"; }
}
