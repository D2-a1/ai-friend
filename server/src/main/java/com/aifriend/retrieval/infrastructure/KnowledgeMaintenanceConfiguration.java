package com.aifriend.retrieval.infrastructure;

import javax.sql.DataSource;
import com.aifriend.assistant.application.AssistantSessionLifecyclePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.retrieval.application.RetryingKnowledgeHistoryMaintenance;

/** 独立维护仅受自身显式开关控制，业务/导入关闭不终止已受理清理。
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(KnowledgeMaintenanceProperties.class)
@ConditionalOnProperty(prefix="ai-friend.knowledge.maintenance",name="enabled",havingValue="true")
public class KnowledgeMaintenanceConfiguration {
    /** 创建无副作用配置。 */
    public KnowledgeMaintenanceConfiguration() { }
    /**
     * 私有适配器不重复注册端口Bean，数据库控制锁和RC租约仍跨实例共享。
     * @param source 数据源
     * @param transactions 同源事务管理器
     * @param properties 独立固定延迟配置
     * @param sessions 会话生命周期端口；沿用逐owner复验和短事务
     * @return 不含导入、模型或告警通道的维护器
     */
    @Bean(initMethod="start",destroyMethod="close")
    public KnowledgeMaintenanceScheduler knowledgeMaintenanceScheduler(DataSource source, PlatformTransactionManager transactions,
            KnowledgeMaintenanceProperties properties, AssistantSessionLifecyclePort sessions) {
        var history=new RetryingKnowledgeHistoryMaintenance(new JdbcKnowledgeHistoryMaintenanceAdapter(source,transactions),
                new JdbcKnowledgeCleanupRetryAdapter(source,transactions));
        var rebuild=new JdbcKnowledgeDeletionRebuildAdapter(source,transactions);
        var quota=new JdbcKnowledgeQuotaMaintenanceAdapter(source,transactions);
        return new KnowledgeMaintenanceScheduler(history::sweep,rebuild::rebuild,quota::sweep,
                () -> sessions.expireBatch(16),properties.pollInterval());
    }
}
