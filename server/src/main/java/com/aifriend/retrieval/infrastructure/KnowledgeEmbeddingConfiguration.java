package com.aifriend.retrieval.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认不装配外部客户端；新总开关与Embedding子开关同时明确开启才允许装配。
 * @author codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeEmbeddingProperties.class)
public class KnowledgeEmbeddingConfiguration {
    /** 创建条件配置。 */
    public KnowledgeEmbeddingConfiguration() { }

    /**
     * 后台导入专用连接执行器、舱壁与熔断器，不占在线调用并发。
     * @param properties 同一批准向量空间配置
     * @param mapper JSON组件
     * @return 独立后台网关
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ai-friend.knowledge", name = {"enabled", "embedding.enabled", "import.enabled"}, havingValue = "true")
    public EmbeddingsHttpAdapter knowledgeImportEmbeddingPort(KnowledgeEmbeddingProperties properties, ObjectMapper mapper) {
        return new EmbeddingsHttpAdapter(properties, new PinnedModelHttpTransport(properties), mapper,
                CircuitBreaker.ofDefaults("knowledgeImportEmbedding"),
                Bulkhead.of("knowledgeImportEmbedding", BulkheadConfig.custom()
                        .maxConcurrentCalls(1).maxWaitDuration(java.time.Duration.ZERO).build()));
    }

    /**
     * 仅创建客户端，不发请求；问答/导入应用层另负责同意和持久费用预留。
     * @param properties 独立外部配置
     * @param mapper JSON组件
     * @return 独立向量端口
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ai-friend.knowledge", name = {"enabled", "embedding.enabled"}, havingValue = "true")
    public EmbeddingsHttpAdapter knowledgeEmbeddingPort(KnowledgeEmbeddingProperties properties, ObjectMapper mapper) {
        return new EmbeddingsHttpAdapter(properties, new PinnedModelHttpTransport(properties), mapper,
                CircuitBreaker.ofDefaults("knowledgeEmbedding"),
                Bulkhead.of("knowledgeEmbedding", BulkheadConfig.custom()
                        .maxConcurrentCalls(4).maxWaitDuration(java.time.Duration.ZERO).build()));
    }
}
