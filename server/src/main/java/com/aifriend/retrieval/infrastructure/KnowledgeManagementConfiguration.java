package com.aifriend.retrieval.infrastructure;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.retrieval.application.KnowledgeDocumentManagementPort;
import com.aifriend.retrieval.application.KnowledgeCleanupStatusPort;

/**
 * 关闭开关仍提供管理员删除边界；构造不访问数据库，不启动定时维护。
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods=false)
public class KnowledgeManagementConfiguration {
    /** 创建配置。 */
    public KnowledgeManagementConfiguration() { }
    /**
     * 装配功能关闭时仍可用的文档逻辑失效端口。
     * @param source 数据源
     * @param transactions 同数据源事务管理器
     * @return 管理端口
     */
    @Bean @ConditionalOnMissingBean(KnowledgeDocumentManagementPort.class)
    public KnowledgeDocumentManagementPort knowledgeDocumentManagementPort(DataSource source,PlatformTransactionManager transactions) {
        return new JdbcKnowledgeDocumentManagementAdapter(source,transactions);
    }

    /**
     * 关闭开关仍允许管理员观察积压，不新增后台工作。
     * @param source 数据源
     * @param transactions 同源事务管理器
     * @return 只读匿名投影
     */
    @Bean @ConditionalOnMissingBean(KnowledgeCleanupStatusPort.class)
    public KnowledgeCleanupStatusPort knowledgeCleanupStatusPort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeCleanupStatusAdapter(source, transactions);
    }
}
