package com.aifriend.task.application;

import java.util.UUID;

/**
 * 任务创建前查询 owner 是否正在清除历史的端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskHistoryClearingGuardPort {
    /**
     * 查询当前 owner 是否有进行中的历史清除作业。
     *
     * @param ownerUserId owner UUID
     * @return 正在清除时返回 true
     */
    boolean isClearing(UUID ownerUserId);
}