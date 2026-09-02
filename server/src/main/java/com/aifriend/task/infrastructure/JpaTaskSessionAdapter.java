package com.aifriend.task.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskSessionRepositoryPort;
import com.aifriend.task.application.TaskStoredCandidate;
import com.aifriend.task.application.TaskStoredOperation;
import com.aifriend.task.application.TaskStoredSession;
import com.aifriend.task.domain.TaskOperationType;
import com.aifriend.task.domain.TaskState;

/**
 * MySQL 任务会话、候选和幂等墓碑持久化适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaTaskSessionAdapter implements TaskSessionRepositoryPort {

    private static final List<TaskState> RESULT_STATES = List.of(
            TaskState.REJECTED,
            TaskState.CANCELLED,
            TaskState.SIMULATED,
            TaskState.COMPLETED,
            TaskState.PARTIAL,
            TaskState.FAILED);

    private final TaskNamespaceJpaRepository namespaceRepository;
    private final TaskSessionJpaRepository sessionRepository;
    private final TaskCandidateJpaRepository candidateRepository;
    private final TaskOperationJpaRepository operationRepository;

    /**
     * 创建任务持久化适配器。
     *
     * @param namespaceRepository owner 命名空间 Repository
     * @param sessionRepository 会话 Repository
     * @param candidateRepository 候选 Repository
     * @param operationRepository 幂等墓碑 Repository
     */
    public JpaTaskSessionAdapter(
            TaskNamespaceJpaRepository namespaceRepository,
            TaskSessionJpaRepository sessionRepository,
            TaskCandidateJpaRepository candidateRepository,
            TaskOperationJpaRepository operationRepository) {
        this.namespaceRepository = namespaceRepository;
        this.sessionRepository = sessionRepository;
        this.candidateRepository = candidateRepository;
        this.operationRepository = operationRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStoredSession> findByCreateKey(UUID ownerUserId, byte[] keyHash) {
        return sessionRepository.findByOwnerUserIdAndCreateIdempotencyKeyHash(
                ownerUserId, keyHash).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStoredSession> findByOwnerAndId(UUID ownerUserId, UUID sessionId) {
        return sessionRepository.findByOwnerUserIdAndId(ownerUserId, sessionId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStoredSession> findLatestByOwner(UUID ownerUserId) {
        return sessionRepository.findFirstByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskStoredSession> listRecentResultsByOwner(UUID ownerUserId) {
        return sessionRepository
                .findTop20ByOwnerUserIdAndStateInOrderByCreatedAtDesc(
                        ownerUserId, RESULT_STATES)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStoredSession> findByOwnerAndIdForUpdate(
            UUID ownerUserId, UUID sessionId) {
        return sessionRepository.findByOwnerAndIdForUpdate(ownerUserId, sessionId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public long lockNamespace(UUID ownerUserId, Instant now) {
        TaskNamespaceEntity namespace = namespaceRepository
                .findByOwnerForUpdate(ownerUserId).orElse(null);
        if (namespace == null) {
            try {
                namespaceRepository.saveAndFlush(new TaskNamespaceEntity(ownerUserId, 0, now));
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            namespace = namespaceRepository.findByOwnerForUpdate(ownerUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        }
        return namespace.getVersion();
    }

    /** {@inheritDoc} */
    @Override
    public TaskStoredSession saveSession(TaskStoredSession session) {
        return toDomain(sessionRepository.save(new TaskSessionEntity(
                session.id(), session.ownerUserId(), session.sourceAudioObjectId(),
                session.clientTaskId(), session.createIdempotencyKeyHash(),
                session.createRequestHash(), session.state(), session.payloadCipher(),
                session.selectedContactId(), session.summaryHash(), session.planId(),
                session.planExpiresAt(), session.sessionVersion(), session.expiresAt(),
                session.createdAt(), session.updatedAt())));
    }

    /** {@inheritDoc} */
    @Override
    public void saveCandidates(List<TaskStoredCandidate> candidates) {
        candidateRepository.saveAll(candidates.stream().map(candidate ->
                new TaskCandidateEntity(candidate.id(), candidate.taskSessionId(),
                        candidate.ownerUserId(), candidate.contactId(),
                        candidate.scoreBand(), candidate.rank(), candidate.createdAt()))
                .toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TaskStoredOperation> findOperation(
            UUID ownerUserId, TaskOperationType operationType, byte[] keyHash) {
        return operationRepository
                .findByOwnerUserIdAndOperationTypeAndIdempotencyKeyHash(
                        ownerUserId, operationType, keyHash)
                .map(this::toOperation);
    }

    /** {@inheritDoc} */
    @Override
    public void saveOperation(TaskStoredOperation operation) {
        operationRepository.save(new TaskOperationEntity(
                operation.id(), operation.taskSessionId(), operation.ownerUserId(),
                operation.operationType(), operation.idempotencyKeyHash(),
                operation.requestHash(), operation.resultingSessionVersion(),
                operation.completedAt()));
    }

    private TaskStoredSession toDomain(TaskSessionEntity entity) {
        return new TaskStoredSession(
                entity.getId(), entity.getOwnerUserId(), entity.getSourceAudioObjectId(),
                entity.getClientTaskId(), entity.getCreateIdempotencyKeyHash(),
                entity.getCreateRequestHash(), entity.getState(), entity.getPayloadCipher(),
                entity.getSelectedContactId(), entity.getSummaryHash(), entity.getPlanId(),
                entity.getPlanExpiresAt(), entity.getSessionVersion(), entity.getExpiresAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private TaskStoredOperation toOperation(TaskOperationEntity entity) {
        return new TaskStoredOperation(
                entity.getId(), entity.getTaskSessionId(), entity.getOwnerUserId(),
                entity.getOperationType(), entity.getIdempotencyKeyHash(),
                entity.getRequestHash(), entity.getResultingSessionVersion(),
                entity.getCompletedAt());
    }
}
