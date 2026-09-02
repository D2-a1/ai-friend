package com.aifriend.task.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.task.domain.TaskState;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 任务会话、候选和 TASK 音频消费的原子提交服务。
 *
 * <p>对象存储、解码、ASR 和声学匹配必须在进入本事务前完成。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskCreationTransactionService {

    private final TaskSessionRepositoryPort repositoryPort;
    private final AudioObjectConsumptionTransactionService audioTransactionService;
    private final DigestService digestService;
    private final TaskHistoryClearingGuardPort historyClearingGuardPort;
    private final TaskSessionMapper mapper;

    /**
     * 创建任务原子提交服务。
     *
     * @param repositoryPort 任务持久化端口
     * @param audioTransactionService 音频锁定消费服务
     * @param digestService 常量时间摘要比较服务
     * @param historyClearingGuardPort 任务历史清除门闩端口
     * @param mapper 会话响应映射器
     */
    public TaskCreationTransactionService(
            TaskSessionRepositoryPort repositoryPort,
            AudioObjectConsumptionTransactionService audioTransactionService,
            DigestService digestService,
            TaskHistoryClearingGuardPort historyClearingGuardPort,
            TaskSessionMapper mapper) {
        this.repositoryPort = repositoryPort;
        this.audioTransactionService = audioTransactionService;
        this.digestService = digestService;
        this.historyClearingGuardPort = historyClearingGuardPort;
        this.mapper = mapper;
    }

    /**
     * 锁定 owner 命名空间并原子写入任务、候选和音频 CONSUMED 状态。
     *
     * @param candidate 待创建会话
     * @param candidates 候选关系
     * @param validatedAudioObject 已在事务外校验的 TASK 音频
     * @param continuationSourceSessionId 已核验的上一条消息会话，可空
     * @return 新任务或同请求安全重放结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskSessionView create(
            TaskStoredSession candidate,
            List<TaskStoredCandidate> candidates,
            ValidatedAudioObject validatedAudioObject,
            UUID continuationSourceSessionId) {
        repositoryPort.lockNamespace(candidate.ownerUserId(), candidate.createdAt());
        if (historyClearingGuardPort.isClearing(candidate.ownerUserId())) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        TaskStoredSession replay = repositoryPort.findByCreateKey(
                candidate.ownerUserId(), candidate.createIdempotencyKeyHash()).orElse(null);
        if (replay != null) {
            if (!digestService.constantTimeEquals(
                    replay.createRequestHash(), candidate.createRequestHash())) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return mapper.toView(replay);
        }
        ensureContinuationSourceCurrent(candidate.ownerUserId(), continuationSourceSessionId);
        return audioTransactionService.consume(validatedAudioObject, ignored -> {
            TaskStoredSession saved = repositoryPort.saveSession(candidate);
            repositoryPort.saveCandidates(candidates);
            return mapper.toView(saved);
        });
    }

    private void ensureContinuationSourceCurrent(
            UUID ownerUserId,
            UUID continuationSourceSessionId) {
        if (continuationSourceSessionId == null) {
            return;
        }
        TaskStoredSession latest = repositoryPort.findLatestByOwner(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        if (!latest.id().equals(continuationSourceSessionId)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }
}
