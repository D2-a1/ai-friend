package com.aifriend.retention.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 联系人解绑后续清理持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface ContactUnboundCleanupRepositoryPort {

    /**
     * 锁定一条已到期且联系人确已解绑的清理事件。
     *
     * @param now 当前 UTC 时间
     * @return 已锁定任务；没有可处理事件时为空
     */
    Optional<ContactUnboundCleanupJob> lockNextReady(Instant now);

    /**
     * 清空联系人全部称呼密文并标记为已删除。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人 UUID
     * @param now 当前 UTC 时间
     * @return 发生变化的称呼数量
     */
    int scrubAliases(UUID ownerUserId, UUID contactId, Instant now);

    /**
     * 取消引用联系人的非终态任务并清除可执行上下文。
     *
     * @param ownerUserId owner UUID
     * @param contactId 联系人 UUID
     * @param now 当前 UTC 时间
     * @return 被取消的任务数量
     */
    int cancelNonTerminalTasks(
            UUID ownerUserId,
            UUID contactId,
            Instant now);

    /**
     * 将清理事件标记为完成。
     *
     * @param eventId Outbox 事件 UUID
     */
    void markCompleted(UUID eventId);
}
