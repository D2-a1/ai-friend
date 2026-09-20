package com.aifriend.retrieval.application;

/**
 * 删除后的公开索引派生发布，不创建用户导入、不调用模型、不修改活动清单。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeDeletionRebuildPort {
    /**
     * 至多尝试发布一个去除已删除来源的新世代；并发变化留待下次扫描。
     * @return 本次明确结果，异常提交不得当作成功或立即重试
     */
    Outcome rebuild();
    /** 固定扫描结果，无来源/用户数据。 */
    enum Outcome {
        /** 无活动索引或没有待去除来源。 */ NO_WORK,
        /** 有有效租约、容量未释放或来源已变化。 */ DEFERRED,
        /** 已原子发布并回读新世代。 */ REBUILT
    }
}
