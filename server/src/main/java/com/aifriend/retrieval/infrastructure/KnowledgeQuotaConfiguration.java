package com.aifriend.retrieval.infrastructure;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.retrieval.application.KnowledgeQuotaPort;

/**
 * 总开关关闭时不装配预算存储；不复用旧联系任务限流配置。
 * @author codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeQuotaProperties.class)
public class KnowledgeQuotaConfiguration {
    /** 创建条件配置。 */
    public KnowledgeQuotaConfiguration() { }

    /**
     * 仅创建端口，不连接数据库；额度子开关关闭时预留失败关闭。
     * @param source 受控数据源
     * @param transactions 对应事务管理器
     * @param properties 独立知识额度
     * @return 持久预留端口
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai-friend.knowledge", name = "enabled", havingValue = "true")
    public KnowledgeQuotaPort knowledgeQuotaPort(DataSource source, PlatformTransactionManager transactions,
            KnowledgeQuotaProperties properties) {
        return new JdbcKnowledgeQuotaAdapter(source, transactions, properties);
    }
}
