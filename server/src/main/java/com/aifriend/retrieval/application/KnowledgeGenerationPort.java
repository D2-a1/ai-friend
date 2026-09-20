package com.aifriend.retrieval.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeBuildSourcePort.Source;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort.Snapshot;

/**
 * 完整索引的暂存与原子发布；所有调用均须重新验证持久租约。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeGenerationPort {
    /**
     * 开始不可见世代，同一世代相同清单可重放，不覆盖任何活动来源。
     * @param build 完整来源和分块清单
     */
    void begin(Build build);

    /**
     * 暂存最多64个片段；模型调用必须在此方法之外完成。
     * @param build 已开始构建
     * @param chunkIds 本批清单ID，不能重复
     * @param vectors 有profile时必须与本批ID完全一致，否则必须为空
     */
    void stage(Build build, List<UUID> chunkIds, Map<UUID, float[]> vectors);

    /**
     * 重读全部暂存材料并在同一事务发布，失败必须保留旧活动指针。
     * @param build 完整且仍持有有效租约的构建
     */
    void publish(Build build);

    /**
     * 不可变构建材料；禁止替换来源、世代算法或语料修订号。
     * @param source 持久任务对应的全部来源
     * @param snapshot 对这些来源的完整分块
     */
    record Build(Source source, Snapshot snapshot) {
        /** 复用快照完整性约束，同时绑定来源与算法空间。 */
        public Build {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(snapshot, "snapshot");
            if (!source.snapshot(snapshot.version().generation(), snapshot.chunks()).equals(snapshot)) {
                throw new IllegalArgumentException("BUILD_SNAPSHOT_MISMATCH");
            }
        }
        @Override public String toString() { return "Build[content=redacted]"; }
    }
}
