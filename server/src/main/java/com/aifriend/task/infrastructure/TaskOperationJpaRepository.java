package com.aifriend.task.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aifriend.task.domain.TaskOperationType;

/**
 * 任务写操作幂等墓碑 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskOperationJpaRepository
        extends JpaRepository<TaskOperationEntity, UUID> {

    /**
     * 按 owner、操作类型和幂等摘要查询墓碑。
     *
     * @param ownerUserId owner UUID
     * @param operationType 操作类型
     * @param keyHash 幂等摘要
     * @return 操作墓碑
     */
    Optional<TaskOperationEntity> findByOwnerUserIdAndOperationTypeAndIdempotencyKeyHash(
            UUID ownerUserId,
            TaskOperationType operationType,
            byte[] keyHash);
}
