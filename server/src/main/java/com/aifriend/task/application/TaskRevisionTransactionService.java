package com.aifriend.task.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskOperationType;
import com.aifriend.task.domain.TaskRevisionMode;
import com.aifriend.task.domain.TaskState;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;

/** 把修订录音消费、候选替换、摘要失效和会话版本推进放在同一事务中。 */
@Service
public class TaskRevisionTransactionService {

    private static final Duration IDLE_EXTENSION = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_CONVERSATION = Duration.ofMinutes(15);

    private final TaskSessionRepositoryPort repositoryPort;
    private final AudioObjectConsumptionTransactionService audioTransactionService;
    private final TaskPayloadCodec payloadCodec;
    private final TaskSessionMapper mapper;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建修订事务服务。
     *
     * @param repositoryPort 任务会话仓储端口
     * @param audioTransactionService 音频原子消费服务
     * @param payloadCodec 任务敏感载荷保护器
     * @param mapper 会话映射器
     * @param digestService 摘要服务
     * @param clock UTC 时钟
     */
    public TaskRevisionTransactionService(
            TaskSessionRepositoryPort repositoryPort,
            AudioObjectConsumptionTransactionService audioTransactionService,
            TaskPayloadCodec payloadCodec,
            TaskSessionMapper mapper,
            DigestService digestService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.audioTransactionService = audioTransactionService;
        this.payloadCodec = payloadCodec;
        this.mapper = mapper;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 原子提交当前会话的一轮口头重说或纠错。
     *
     * @param ownerUserId 当前 owner
     * @param expectedVersion 客户端已见会话版本
     * @param keyHash 修订幂等键摘要
     * @param requestHash 修订请求摘要
     * @param revision 事务外准备完成的修订
     * @return 已推进版本的任务会话
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskSessionView commit(
            UUID ownerUserId,
            long expectedVersion,
            byte[] keyHash,
            byte[] requestHash,
            PreparedTaskRevision revision) {
        TaskStoredSession current = repositoryPort.findByOwnerAndIdForUpdate(
                ownerUserId, revision.baseSession().id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        TaskStoredOperation replay = repositoryPort.findOperation(
                ownerUserId, TaskOperationType.REVISION, keyHash).orElse(null);
        if (replay != null) {
            boolean safe = replay.taskSessionId().equals(current.id())
                    && replay.resultingSessionVersion() == current.sessionVersion()
                    && digestService.constantTimeEquals(replay.requestHash(), requestHash);
            if (!safe) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return mapper.toView(current);
        }
        Instant now = Instant.now(clock);
        boolean mutableState = current.state() == TaskState.AWAITING_CONFIRMATION
                || current.state() == TaskState.AWAITING_SELECTION
                || current.state() == TaskState.NEEDS_RETRY
                || current.state() == TaskState.NEEDS_CONTENT_REPEAT;
        if (!mutableState || current.state().terminal()
                || current.sessionVersion() != expectedVersion
                || !current.expiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return audioTransactionService.consume(revision.audio(), ignored -> {
            TaskPayload previous = payloadCodec.decode(current.payloadCipher());
            List<TaskCandidateView> candidateViews = revision.replaceCandidates()
                    ? revision.matches().stream()
                            .map(match -> new TaskCandidateView(
                                    match.candidateId(),
                                    new TaskMatchedContactView(match.publicContactId(),
                                            match.displayName(), match.alias()),
                                    match.scoreBand(), match.rank()))
                            .toList()
                    : previous.candidates();
            TaskConversationTurnType turnType = revision.mode() == TaskRevisionMode.FULL_RETRY
                    ? TaskConversationTurnType.USER_RETRY
                    : TaskConversationTurnType.USER_CORRECTION;
            TaskConversationContext conversation = previous.conversationContext()
                    .appendUser(turnType, revision.revisionTranscript())
                    .appendSystemRehearsal(revision.spokenSummary());
            TaskPayload payload = new TaskPayload(
                    revision.context(), revision.understanding(), candidateViews,
                    revision.spokenSummary(), allowedActions(
                            revision.state(), revision.understanding().intent()),
                    revision.state() == TaskState.AWAITING_CONFIRMATION ? now : null,
                    null, null, null, conversation);
            Instant maximumExpiry = current.createdAt().plus(MAXIMUM_CONVERSATION);
            Instant extendedExpiry = now.plus(IDLE_EXTENSION);
            Instant expiresAt = extendedExpiry.isBefore(maximumExpiry)
                    ? extendedExpiry : maximumExpiry;
            TaskStoredSession updated = new TaskStoredSession(
                    current.id(), current.ownerUserId(),
                    revision.replaceSourceAudio()
                            ? revision.audio().audioObjectId()
                            : current.sourceAudioObjectId(),
                    current.clientTaskId(), current.createIdempotencyKeyHash(),
                    current.createRequestHash(), revision.state(), payloadCodec.encode(payload),
                    revision.selectedContactId(), revision.summaryHash(), null, null,
                    current.sessionVersion() + 1, expiresAt, current.createdAt(), now);
            TaskStoredSession saved = repositoryPort.saveSession(updated);
            if (revision.replaceCandidates()) {
                repositoryPort.replaceCandidates(saved.id(), revision.matches().stream()
                        .map(match -> new TaskStoredCandidate(
                                UUID.randomUUID(), saved.id(), ownerUserId, match.contactId(),
                                match.scoreBand(), match.rank(), now))
                        .toList());
            }
            repositoryPort.saveOperation(new TaskStoredOperation(
                    UUID.randomUUID(), saved.id(), ownerUserId, TaskOperationType.REVISION,
                    keyHash, requestHash, saved.sessionVersion(), now));
            return mapper.toView(saved);
        });
    }

    private Set<TaskAction> allowedActions(TaskState state, TaskIntent intent) {
        if (state == TaskState.AWAITING_SELECTION) {
            return Set.of(TaskAction.SELECT_CANDIDATE, TaskAction.RETRY,
                    TaskAction.CANCEL);
        }
        if (state == TaskState.AWAITING_CONFIRMATION) {
            TaskAction confirm = intent == TaskIntent.SEND_MESSAGE
                    ? TaskAction.CONFIRM_SEND : TaskAction.CONFIRM_CALL;
            return Set.of(confirm, TaskAction.CORRECT, TaskAction.REJECT,
                    TaskAction.CANCEL);
        }
        if (state == TaskState.NEEDS_RETRY
                || state == TaskState.NEEDS_CONTENT_REPEAT) {
            return Set.of(TaskAction.RETRY, TaskAction.CANCEL);
        }
        return Set.of();
    }
}