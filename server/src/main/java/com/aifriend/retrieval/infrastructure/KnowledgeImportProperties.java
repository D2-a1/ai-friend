package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 后台导入的有界调度/存储参数；不包含供应商身份或凭据。
 * @param pollInterval 扫描间隔
 * @param lease 全局租约
 * @param jobLifetime 总任务期限
 * @param batchSize 单批片段数
 * @param maxPending 最大待处理任务数
 * @param maximumSnapshotBytes 内存预算上限
 * @param modelCallBudget 单次模型预算
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.import")
public record KnowledgeImportProperties(@DefaultValue("2s") Duration pollInterval,
        @DefaultValue("180s") Duration lease, @DefaultValue("600s") Duration jobLifetime,
        @DefaultValue("32") int batchSize, @DefaultValue("100") int maxPending,
        @DefaultValue("134217728") long maximumSnapshotBytes, @DefaultValue("8s") Duration modelCallBudget) {
    /** 即使关闭前配置仍须满足硬边界；默认值均为有界值。 */
    public KnowledgeImportProperties {
        if (pollInterval == null || pollInterval.compareTo(Duration.ofSeconds(1)) < 0 || pollInterval.compareTo(Duration.ofMinutes(1)) > 0
                || pollInterval.toNanos() % 1_000_000 != 0 || lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0 || lease.toNanos() % 1_000_000 != 0
                || jobLifetime == null || jobLifetime.compareTo(Duration.ofSeconds(1)) < 0
                || jobLifetime.compareTo(Duration.ofMinutes(30)) > 0 || jobLifetime.toNanos() % 1_000_000_000 != 0
                || batchSize < 1 || batchSize > 64 || maxPending < 1 || maxPending > 100
                || maximumSnapshotBytes < 1024 * 1024 || maximumSnapshotBytes > 128L * 1024 * 1024
                || modelCallBudget == null || modelCallBudget.compareTo(Duration.ofMillis(1)) < 0
                || modelCallBudget.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_IMPORT_CONFIGURATION");
        }
    }
}
