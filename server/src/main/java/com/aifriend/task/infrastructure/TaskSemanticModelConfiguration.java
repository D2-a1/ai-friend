package com.aifriend.task.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.task.application.TaskConversationUnderstandingPort;
import com.aifriend.task.application.TaskSemanticModelProperties;

/**
 * 生产上下文语义模型条件装配。
 *
 * <p>只在非测试环境且显式开启时使用真实模型；兼容云端自用 dev Profile，默认继续使用
 * 本地保守实现。模型 Bean 标记为首选，但仍只能实现有限草稿端口，不能进入确认与动作
 * 计划签发层。
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class TaskSemanticModelConfiguration {

    /** 创建语义模型条件配置。 */
    public TaskSemanticModelConfiguration() {
    }

    /**
     * 提供真实 Chat Completions 语义模型适配器。
     *
     * @param properties 类型安全模型配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 舱壁注册表
     * @return 有上下文的有限语义理解端口
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "ai-friend.task-semantic-model",
            name = "enabled",
            havingValue = "true")
    public TaskConversationUnderstandingPort taskConversationUnderstandingPort(
            TaskSemanticModelProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry) {
        return new ChatCompletionsTaskUnderstandingAdapter(
                properties, restClientBuilder, objectMapper,
                circuitBreakerRegistry, bulkheadRegistry);
    }
}