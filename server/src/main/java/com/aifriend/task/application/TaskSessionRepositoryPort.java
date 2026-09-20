package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.task.domain.TaskOperationType;

/**
 * owner 范围任务会话、候选和幂等操作持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskSessionRepositoryPort {

    /**
     * 按 owner 和创建幂等摘要查询任务。
     *
     * @param ownerUserId owner UUID
     * @param keyHash 创建幂等摘要
     * @return owner 范围会话
     */
    Optional<TaskStoredSession> findByCreateKey(UUID ownerUserId, byte[] keyHash);

    /**
     * 按 owner 和会话 UUID 查询任务。
     *
     * @param ownerUserId owner UUID
     * @param sessionId 会话 UUID
     * @return owner 范围会话
     */
    Optional<TaskStoredSession> findByOwnerAndId(UUID ownerUserId, UUID sessionId);

    /**
     * 查询当前 owner 最新创建的一条任务。
     *
     * <p>只返回一条，用于继续窗口核对任务顺序；不得据此跨 owner 推断联系人。
     *
     * @param ownerUserId owner UUID
     * @return owner 范围最新会话
     */
    Optional<TaskStoredSession> findLatestByOwner(UUID ownerUserId);

    /**
     * 查询当前 owner 最近二十条已结束任务。
     *
     * @param ownerUserId owner UUID
     * @return 按创建时间倒序排列的终态会话
     */
    List<TaskStoredSession> listRecentResultsByOwner(UUID ownerUserId);

    /**
     * 按 owner 和会话 UUID 加写锁查询任务。
     *
     * @param ownerUserId owner UUID
     * @param sessionId 会话 UUID
     * @return owner 范围加锁会话
     */
    Optional<TaskStoredSession> findByOwnerAndIdForUpdate(UUID ownerUserId, UUID sessionId);

    /**
     * 创建或锁定 owner 任务命名空间。
     *
     * @param ownerUserId owner UUID
     * @param now 创建时间
     * @return 命名空间版本
     */
    long lockNamespace(UUID ownerUserId, Instant now);

    /**
     * 保存任务会话快照。
     *
     * @param session 会话快照
     * @return 保存后会话
     */
    TaskStoredSession saveSession(TaskStoredSession session);

    /**
     * 保存当前会话候选关系。
     *
     * @param candidates 候选关系
     */
    void saveCandidates(List<TaskStoredCandidate> candidates);

    /**
     * 在同一事务内用修订后的候选快照替换旧候选。
     *
     * @param sessionId 任务会话 UUID
     * @param candidates 新候选关系
     */
    void replaceCandidates(UUID sessionId, List<TaskStoredCandidate> candidates);

    /**
     * 查找写操作幂等墓碑。
     *
     * @param ownerUserId owner UUID
     * @param operationType 写操作类型
     * @param keyHash 幂等键摘要
     * @return 操作墓碑
     */
    Optional<TaskStoredOperation> findOperation(
            UUID ownerUserId,
            TaskOperationType operationType,
            byte[] keyHash);

    /**
     * 保存写操作幂等墓碑。
     *
     * @param operation 写操作幂等墓碑
     */
    void saveOperation(TaskStoredOperation operation);
}
