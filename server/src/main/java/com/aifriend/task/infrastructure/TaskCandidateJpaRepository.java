package com.aifriend.task.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 任务候选关系 Spring Data Repository。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface TaskCandidateJpaRepository
        extends JpaRepository<TaskCandidateEntity, UUID> {
}
