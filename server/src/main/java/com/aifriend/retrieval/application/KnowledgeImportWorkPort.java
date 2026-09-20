package com.aifriend.retrieval.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;

/**
 * 持久后台待办与剩余租约读取；不代替认领/最终发布。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeImportWorkPort {
    /**
     * 有界扫描待认领/过期任务，包括需收敛终态的到期任务。
     * @param limit 1到10
     * @return 任务ID，不含文档内容
     */
    List<UUID> due(int limit);

    /**
     * 按当前DB时间复验全局及任务租约，返回剩余时间。
     * @param claim 当前认领
     * @return 正的剩余租约，不超过5分钟
     */
    Duration remaining(Claim claim);
}
