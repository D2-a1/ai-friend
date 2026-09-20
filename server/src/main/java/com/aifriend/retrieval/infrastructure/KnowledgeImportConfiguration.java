package com.aifriend.retrieval.infrastructure;

import java.util.Optional;
import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.retrieval.application.EmbeddingPort;
import com.aifriend.retrieval.application.KnowledgeBuildSourcePort;
import com.aifriend.retrieval.application.KnowledgeChunkingPort;
import com.aifriend.retrieval.application.KnowledgeGenerationPort;
import com.aifriend.retrieval.application.KnowledgeImportLeasePort;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.BuildSpecification;
import com.aifriend.retrieval.application.KnowledgeImportWorkPort;
import com.aifriend.retrieval.application.KnowledgeImportWorker;
import com.aifriend.retrieval.application.KnowledgeQuotaPort;
import com.aifriend.retrieval.application.KnowledgeHistoryMaintenancePort;
import com.aifriend.retrieval.application.KnowledgeDeletionRebuildPort;
import com.aifriend.retrieval.application.KnowledgeCleanupRetryPort;
import com.aifriend.retrieval.application.RetryingKnowledgeHistoryMaintenance;

/**
 * 公开知识后台完整构建装配；总开关与导入开关都开启才创建。
 * @author codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ai-friend.knowledge", name = {"enabled", "import.enabled"}, havingValue = "true")
@EnableConfigurationProperties({KnowledgeImportProperties.class, KnowledgeChunkProperties.class, KnowledgeEmbeddingProperties.class})
public class KnowledgeImportConfiguration {
    /** 创建条件配置。 */
    public KnowledgeImportConfiguration() { }

    /**
     * 创建幂等登记端口。
     * @param source 数据源
     * @param transactions 事务管理器
     * @param properties 导入配置
     * @return 幂等登记端口
     */
    @Bean public KnowledgeImportRegistrationPort knowledgeImportRegistrationPort(DataSource source,
            PlatformTransactionManager transactions, KnowledgeImportProperties properties) {
        return new JdbcKnowledgeImportRegistrationAdapter(source, transactions, properties.jobLifetime(), properties.maxPending());
    }
    /**
     * 创建全局租约端口。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return 全局租约端口
     */
    @Bean public KnowledgeImportLeasePort knowledgeImportLeasePort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeImportLeaseAdapter(source, transactions);
    }
    /**
     * 创建完整来源端口。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return 完整来源端口
     */
    @Bean public KnowledgeBuildSourcePort knowledgeBuildSourcePort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeBuildSourceAdapter(source, transactions);
    }
    /**
     * 创建暂存及原子发布端口。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return 暂存及原子发布端口
     */
    @Bean public KnowledgeGenerationPort knowledgeGenerationPort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeGenerationAdapter(source, transactions);
    }
    /**
     * 创建有界待办端口。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return 有界待办端口
     */
    @Bean public KnowledgeImportWorkPort knowledgeImportWorkPort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeImportWorkAdapter(source, transactions);
    }
    /**
     * 创建版本化分块器。
     * @param properties 分块配置
     * @return 版本化分块器
     */
    @Bean public KnowledgeChunkingPort knowledgeChunkingPort(KnowledgeChunkProperties properties) {
        return new KnowledgeChunker(properties.targetChars(), properties.overlapChars(), properties.maxChars());
    }
    /**
     * 固定导入登记和构建共用的版本。
     * @param chunker 分块器
     * @param embedding 向量配置
     * @return 固定构建 profile
     */
    @Bean public BuildSpecification knowledgeBuildSpecification(KnowledgeChunkingPort chunker, KnowledgeEmbeddingProperties embedding) {
        return new BuildSpecification(embedding.enabled() ? Optional.of(embedding.profile()) : Optional.empty(),
                KnowledgeTokenizer.VERSION, chunker.version());
    }
    /**
     * 后台仅注入后台专属模型实例，不争用在线模型的舱壁。
     * @param work 待办端口
     * @param leases 租约端口
     * @param sources 来源端口
     * @param generations 发布端口
     * @param chunker 分块端口
     * @param quota 持久额度
     * @param model 独立后台模型
     * @param properties 后台配置
     * @param embedding 向量配置
     * @param specification 固定构建版本
     * @return 完整编排
     */
    @Bean public KnowledgeImportWorker knowledgeImportWorker(KnowledgeImportWorkPort work, KnowledgeImportLeasePort leases,
            KnowledgeBuildSourcePort sources, KnowledgeGenerationPort generations, KnowledgeChunkingPort chunker,
            KnowledgeQuotaPort quota, @Qualifier("knowledgeImportEmbeddingPort") ObjectProvider<EmbeddingPort> model,
            KnowledgeImportProperties properties, KnowledgeEmbeddingProperties embedding, BuildSpecification specification) {
        if (embedding.enabled() && properties.batchSize() > embedding.batchSize()) {
            throw new IllegalArgumentException("IMPORT_EMBEDDING_BATCH_MISMATCH");
        }
        return new KnowledgeImportWorker(work, leases, sources, generations, chunker, quota, Optional.ofNullable(model.getIfAvailable()),
                new KnowledgeImportWorker.Settings(specification, properties.lease(), properties.batchSize(),
                        properties.maximumSnapshotBytes(), properties.modelCallBudget()));
    }

    /**
     * 创建有界历史回收端口，重试事实保存在独立短事务账本。
     * @param source 数据源
     * @param transactions 事务管理器
     * @param retry 持久退避端口
     * @return 历史回收端口
     */
    @Bean public KnowledgeHistoryMaintenancePort knowledgeHistoryMaintenancePort(DataSource source,
            PlatformTransactionManager transactions, KnowledgeCleanupRetryPort retry) {
        return new RetryingKnowledgeHistoryMaintenance(new JdbcKnowledgeHistoryMaintenanceAdapter(source, transactions), retry);
    }

    /**
     * 创建不持有线程的RC账本。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return RC账本
     */
    @Bean public KnowledgeCleanupRetryPort knowledgeCleanupRetryPort(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeCleanupRetryAdapter(source, transactions);
    }

    /**
     * 删除派生发布与导入共用开关及控制锁，不新增常驻维护线程。
     * @param source 数据源
     * @param transactions 对应事务管理器
     * @return 删除派生发布端口
     */
    @Bean public KnowledgeDeletionRebuildPort knowledgeDeletionRebuildPort(DataSource source,PlatformTransactionManager transactions) {
        return new JdbcKnowledgeDeletionRebuildAdapter(source,transactions);
    }

    /**
     * 创建不影响旧任务调度的私有扫描器。
     * @param worker 构建编排
     * @param maintenance 历史回收
     * @param deletionRebuild 删除派生发布
     * @param properties 有界间隔
     * @param independentMaintenance 独立维护开启时不重复调度清理与重建
     * @return 私有扫描器
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(KnowledgeImportScheduler.class)
    public KnowledgeImportScheduler knowledgeImportScheduler(KnowledgeImportWorker worker,
            KnowledgeHistoryMaintenancePort maintenance, KnowledgeDeletionRebuildPort deletionRebuild, KnowledgeImportProperties properties,
            @org.springframework.beans.factory.annotation.Value("${ai-friend.knowledge.maintenance.enabled:false}") boolean independentMaintenance) {
        return new KnowledgeImportScheduler(worker,
                independentMaintenance ? () -> new KnowledgeHistoryMaintenancePort.Cleanup(0,0) : maintenance,
                independentMaintenance ? () -> KnowledgeDeletionRebuildPort.Outcome.NO_WORK : deletionRebuild, properties.pollInterval());
    }
}
