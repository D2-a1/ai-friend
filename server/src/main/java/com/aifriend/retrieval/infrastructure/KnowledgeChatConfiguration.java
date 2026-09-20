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
 * 默认关闭的独立问答生成配置；仅创建客户端，不代表许可或发起调用。
 * @author codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeChatProperties.class)
public class KnowledgeChatConfiguration {
    /** 创建条件配置。 */
    public KnowledgeChatConfiguration() { }

    /**
     * 创建独立问答网关，应用层仍须先校验用途同意与持久额度。
     * @param properties 本功能配置
     * @param mapper JSON组件
     * @return 本功能独立客户端
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ai-friend.knowledge", name = {"enabled", "chat.enabled"}, havingValue = "true")
    public ChatCompletionsKnowledgeAnswerAdapter knowledgeAnswerGenerationPort(KnowledgeChatProperties properties, ObjectMapper mapper) {
        return new ChatCompletionsKnowledgeAnswerAdapter(properties, new PinnedModelHttpTransport(properties), mapper,
                CircuitBreaker.ofDefaults("knowledgeAnswer"),
                Bulkhead.of("knowledgeAnswer", BulkheadConfig.custom().maxConcurrentCalls(4)
                        .maxWaitDuration(java.time.Duration.ZERO).build()));
    }
}
