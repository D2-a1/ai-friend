package com.aifriend.assistant.infrastructure;

import java.time.Clock;
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

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.application.KnowledgeAnswerService;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;
import com.aifriend.consent.infrastructure.JdbcKnowledgeConsentQueryAdapter;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeAdapter;
import com.aifriend.retrieval.infrastructure.LocalKnowledgeSearchAdapter;
import com.aifriend.retrieval.infrastructure.RrfFusion;

/**
 * 默认关闭的回答应用服务装配；构造不读数据、不调用模型，没有新增HTTP入口。
 * @author codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ai-friend.knowledge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KnowledgeAnswerProperties.class)
public class KnowledgeAnswerConfiguration {
    /** 创建独立配置。 */
    public KnowledgeAnswerConfiguration() { }

    /**
     * 一个实例同时承担完整语料和向量读取，不复制活动指针。
     * @param source 数据源
     * @param transactions 事务管理器
     * @return 权威读取器
     */
    @Bean
    @ConditionalOnMissingBean({KnowledgeRepositoryPort.class, KnowledgeVectorRepositoryPort.class})
    public JdbcKnowledgeAdapter knowledgeRepository(DataSource source, PlatformTransactionManager transactions) {
        return new JdbcKnowledgeAdapter(source, transactions);
    }

    /**
     * 创建本地检索算法。
     * @param vectors 权威向量读取端口
     * @param properties 有界参数
     * @return 无陈旧缓存的算法
     */
    @Bean public KnowledgeSearchPort knowledgeSearchPort(KnowledgeVectorRepositoryPort vectors,
            KnowledgeAnswerProperties properties) {
        return new LocalKnowledgeSearchAdapter(vectors, properties.bm25K1(), properties.bm25B(),
                properties.maximumSnapshotBytes());
    }

    /**
     * 创建排名融合算法。
     * @param properties 本地配置
     * @return 确定性排名融合
     */
    @Bean public RankFusionPort knowledgeRankFusionPort(KnowledgeAnswerProperties properties) {
        return new RrfFusion(properties.rrfK());
    }

    /**
     * 与旧音频/任务授权分离。
     * @param source 当前用途同意的权威数据源
     * @return 独立用途策略
     */
    @Bean public KnowledgeAccessPolicy knowledgeAccessPolicy(DataSource source) {
        return new KnowledgeAccessPolicy(new JdbcKnowledgeConsentQueryAdapter(source));
    }

    /**
     * 只注入在线Embedding，即使后台导入同时开启也不混用两个实例。
     * @param repository 来源
     * @param search 检索
     * @param fusion 融合
     * @param embedding 指定名称的在线向量端口
     * @param generation 独立生成端口
     * @param quota 持久额度
     * @param access 独立同意
     * @param properties 模式及数值
     * @param clock 统一UTC时钟
     * @return 尚需会话/API调用的应用服务
     */
    @Bean public KnowledgeAnswerService knowledgeAnswerService(KnowledgeRepositoryPort repository,
            KnowledgeSearchPort search, RankFusionPort fusion,
            @Qualifier("knowledgeEmbeddingPort") ObjectProvider<EmbeddingPort> embedding,
            ObjectProvider<KnowledgeAnswerGenerationPort> generation,
            KnowledgeQuotaPort quota, KnowledgeAccessPolicy access, KnowledgeAnswerProperties properties, Clock clock) {
        var chat = Optional.ofNullable(generation.getIfAvailable());
        if (properties.mode() == Mode.GENERATED && chat.isEmpty()) {
            throw new IllegalStateException("KNOWLEDGE_GENERATION_NOT_CONFIGURED");
        }
        return new KnowledgeAnswerService(repository, search, fusion, Optional.ofNullable(embedding.getIfAvailable()),
                chat, quota, access, new KnowledgeAnswerService.Settings(properties.mode(), properties.generationBudget()), clock);
    }
}
