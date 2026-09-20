package com.aifriend.task.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskOperationType;
import com.aifriend.task.domain.TaskRevisionMode;
import com.aifriend.task.domain.TaskState;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 单次唤醒会话内的完整重说与定向口头纠错编排。
 *
 * <p>语音识别和语义理解在事务外完成；事务内重新核对版本后，才消费录音并替换草稿。
 * 每次修订都会生成新摘要和新会话版本，旧确认不能重放到新草稿。
 */
@Service
public class TaskRevisionService {

    private final TaskSessionRepositoryPort repositoryPort;
    private final AudioObjectConsumptionService audioConsumptionService;
    private final TaskSpeechRecognitionPort speechRecognitionPort;
    private final TaskConversationUnderstandingPort understandingPort;
    private final TaskPersonalizationPort personalizationPort;
    private final TaskContactMatcherPort contactMatcherPort;
    private final TaskPayloadCodec payloadCodec;
    private final TaskRevisionTransactionService transactionService;
    private final TaskSessionMapper mapper;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建会话修订服务。
     *
     * @param repositoryPort 任务会话仓储端口
     * @param audioConsumptionService TASK 音频校验服务
     * @param speechRecognitionPort 事务外语音识别端口
     * @param understandingPort 首轮及多轮共用的上下文理解端口
     * @param personalizationPort 只读有限个人偏好端口
     * @param contactMatcherPort owner 范围联系人匹配端口
     * @param payloadCodec 任务敏感载荷保护器
     * @param transactionService 修订原子提交服务
     * @param mapper 会话映射器
     * @param digestService 摘要服务
     * @param clock UTC 时钟
     */
    public TaskRevisionService(
            TaskSessionRepositoryPort repositoryPort,
            AudioObjectConsumptionService audioConsumptionService,
            TaskSpeechRecognitionPort speechRecognitionPort,
            TaskConversationUnderstandingPort understandingPort,
            TaskPersonalizationPort personalizationPort,
            TaskContactMatcherPort contactMatcherPort,
            TaskPayloadCodec payloadCodec,
            TaskRevisionTransactionService transactionService,
            TaskSessionMapper mapper,
            DigestService digestService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.audioConsumptionService = audioConsumptionService;
        this.speechRecognitionPort = speechRecognitionPort;
        this.understandingPort = understandingPort;
        this.personalizationPort = personalizationPort;
        this.contactMatcherPort = contactMatcherPort;
        this.payloadCodec = payloadCodec;
        this.transactionService = transactionService;
        this.mapper = mapper;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 修订当前会话草稿，不执行任何微信动作。
     *
     * @param ownerUserId 当前 owner
     * @param publicSessionId ts_ 会话编号
     * @param idempotencyKey 幂等键
     * @param command 本轮录音与预期版本
     * @return 新版本任务草稿
     */
    public TaskSessionView revise(
            UUID ownerUserId,
            String publicSessionId,
            String idempotencyKey,
            ReviseTaskCommand command) {
        validateCommand(command);
        UUID sessionId = PublicIdCodec.parseTaskSessionId(publicSessionId);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(fingerprint(command));
        TaskStoredSession base = repositoryPort.findByOwnerAndId(ownerUserId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        TaskStoredOperation replay = repositoryPort.findOperation(
                ownerUserId, TaskOperationType.REVISION, keyHash).orElse(null);
        if (replay != null) {
            boolean safe = replay.taskSessionId().equals(base.id())
                    && replay.resultingSessionVersion() == base.sessionVersion()
                    && digestService.constantTimeEquals(replay.requestHash(), requestHash);
            if (!safe) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return mapper.toView(base);
        }
        ensureMutable(base, command.expectedVersion());
        TaskPayload previous = payloadCodec.decode(base.payloadCipher());
        TaskClientContext context = withRecognition(
                previous.context(), command.basicRecognition());
        ValidatedAudioObject audio = audioConsumptionService.validateAll(
                ownerUserId, List.of(command.audioObjectId()), AudioPurpose.TASK).get(0);
        TaskSpeechRecognition recognition = speechRecognitionPort.recognize(audio, context);
        validateRecognition(recognition, audio.actualDurationMs());
        TaskConversationPreferences preferences =
                personalizationPort.currentPreferences(ownerUserId);
        TaskDraftRevision draft = understandingPort.revise(
                previous, recognition, audio.actualDurationMs(), command.mode(), preferences);
        validateRecognition(draft.recognition(), audio.actualDurationMs());

        List<TaskContactCandidate> matches = List.of();
        if (draft.outcome() == TaskInterpretationOutcome.READY
                && draft.replaceContact()) {
            matches = contactMatcherPort.match(
                    ownerUserId, audio, draft.recognition(), context);
            validateMatches(matches);
        }
        boolean unique = matches.size() == 1
                && "UNIQUE".equals(matches.get(0).scoreBand());
        TaskMatchedContactView selectedContact = draft.replaceContact()
                ? unique ? toContact(matches.get(0)) : null
                : previous.understanding().contact();
        UUID selectedContactId = draft.replaceContact()
                ? unique ? matches.get(0).contactId() : null
                : base.selectedContactId();
        TaskState state = state(draft, matches, selectedContact);
        String spokenSummary = state == TaskState.AWAITING_CONFIRMATION
                ? summary(draft.intent(), selectedContact, draft.messageText()) : null;
        String summaryHash = spokenSummary == null ? null : hashSummary(spokenSummary);
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                draft.intent(), selectedContact, draft.recognition().transcript(),
                draft.messageText(), effectiveRanges(previous, draft),
                draft.corrections(), draft.recognition().confidence(),
                processingVersions(context, draft.recognition()));
        PreparedTaskRevision prepared = new PreparedTaskRevision(
                base, audio, context, understanding, matches, selectedContact,
                selectedContactId, draft.replaceContact(), draft.replaceSourceAudio(),
                state, spokenSummary, summaryHash, command.mode(),
                recognition.transcript());
        return transactionService.commit(ownerUserId, command.expectedVersion(),
                keyHash, requestHash, prepared);
    }

    private List<TaskAudioRange> effectiveRanges(
            TaskPayload previous,
            TaskDraftRevision draft) {
        if (draft.replaceSourceAudio()) {
            return draft.recognition().effectiveAudioRanges();
        }
        if (draft.intent() == TaskIntent.SEND_MESSAGE) {
            return previous.understanding().effectiveAudioRanges();
        }
        return List.of();
    }

    private TaskState state(
            TaskDraftRevision draft,
            List<TaskContactCandidate> matches,
            TaskMatchedContactView selectedContact) {
        if (draft.outcome() == TaskInterpretationOutcome.CANCELLED) {
            return TaskState.CANCELLED;
        }
        if (draft.outcome() == TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT) {
            return TaskState.NEEDS_CONTENT_REPEAT;
        }
        if (draft.outcome() == TaskInterpretationOutcome.NEEDS_RETRY) {
            return TaskState.NEEDS_RETRY;
        }
        if (draft.replaceContact()) {
            if (matches.isEmpty()) return TaskState.NEEDS_RETRY;
            return matches.size() == 1 && "UNIQUE".equals(matches.get(0).scoreBand())
                    ? TaskState.AWAITING_CONFIRMATION
                    : TaskState.AWAITING_SELECTION;
        }
        return selectedContact == null
                ? TaskState.NEEDS_RETRY : TaskState.AWAITING_CONFIRMATION;
    }

    private TaskProcessingVersionsView processingVersions(
            TaskClientContext context,
            TaskSpeechRecognition recognition) {
        return new TaskProcessingVersionsView(
                context.dialectCode(), context.dialectPackageVersion(),
                recognition.primaryAsrModelVersion(),
                recognition.mandarinAssistModelVersion(),
                recognition.fusionRuleVersion(), recognition.alignmentVersion(),
                context.templateModelVersion(), context.thresholdVersion());
    }

    private TaskMatchedContactView toContact(TaskContactCandidate candidate) {
        return new TaskMatchedContactView(
                candidate.publicContactId(), candidate.displayName(), candidate.alias());
    }

    private String summary(
            TaskIntent intent,
            TaskMatchedContactView contact,
            String messageText) {
        if (contact == null) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        String target = StringUtils.hasText(contact.displayName())
                ? contact.displayName() : contact.alias();
        String value = switch (intent) {
            case SEND_MESSAGE -> {
                if (!StringUtils.hasText(messageText)) {
                    throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
                }
                yield "给" + target + "发送消息：" + messageText;
            }
            case VOICE_CALL -> "给" + target + "发起微信语音通话";
            case VIDEO_CALL -> "给" + target + "发起微信视频通话";
            default -> throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        };
        if (value.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private String hashSummary(String summary) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                digestService.sha256(summary.getBytes(StandardCharsets.UTF_8)));
    }

    private TaskClientContext withRecognition(
            TaskClientContext context,
            TaskClientRecognitionEvidence recognition) {
        return new TaskClientContext(
                context.appVersion(), context.wechatVersion(), context.ruleVersion(),
                context.dialectCode(), context.dialectPackageVersion(),
                context.mandarinAssistVersion(), context.fusionRuleVersion(),
                context.templateModelVersion(), context.thresholdVersion(), recognition);
    }

    private void ensureMutable(TaskStoredSession session, long expectedVersion) {
        boolean mutable = session.state() == TaskState.AWAITING_CONFIRMATION
                || session.state() == TaskState.AWAITING_SELECTION
                || session.state() == TaskState.NEEDS_RETRY
                || session.state() == TaskState.NEEDS_CONTENT_REPEAT;
        if (!mutable || session.state().terminal()
                || session.sessionVersion() != expectedVersion
                || !session.expiresAt().isAfter(Instant.now(clock))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void validateCommand(ReviseTaskCommand command) {
        if (command == null || command.mode() == null
                || command.expectedVersion() < 1
                || !within(command.audioObjectId(), 4, 64)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private byte[] fingerprint(ReviseTaskCommand command) {
        String recognition = command.basicRecognition() == null ? ""
                : command.basicRecognition().fingerprintInput();
        return (command.audioObjectId() + "|" + command.expectedVersion() + "|"
                + command.mode() + "|" + recognition)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void validateRecognition(TaskSpeechRecognition result, int durationMs) {
        boolean invalid = result == null || !within(result.transcript(), 1, 1000)
                || result.confidence() < 0 || result.confidence() > 1
                || !within(result.primaryAsrModelVersion(), 1, 60)
                || !within(result.mandarinAssistModelVersion(), 1, 60)
                || !within(result.fusionRuleVersion(), 1, 60)
                || !within(result.alignmentVersion(), 1, 60)
                || result.nBest().isEmpty() || result.nBest().size() > 6
                || result.nBest().get(0).source() != TaskAsrSource.PRIMARY
                || result.effectiveAudioRanges().size() > 10
                || result.effectiveAudioRanges().stream().anyMatch(range ->
                        range.startMs() < 0 || range.endMs() <= range.startMs()
                                || range.endMs() > durationMs);
        if (invalid) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
    }

    private void validateMatches(List<TaskContactCandidate> matches) {
        if (matches == null || matches.size() > 3) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        for (int index = 0; index < matches.size(); index++) {
            TaskContactCandidate candidate = matches.get(index);
            boolean invalid = candidate == null || candidate.rank() != index + 1
                    || !within(candidate.candidateId(), 1, 64)
                    || !within(candidate.publicContactId(), 4, 64)
                    || candidate.contactId() == null
                    || !within(candidate.displayName(), 0, 80)
                    || !within(candidate.alias(), 1, 40)
                    || !("UNIQUE".equals(candidate.scoreBand())
                            || "AMBIGUOUS".equals(candidate.scoreBand()));
            if (invalid) {
                throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
            }
        }
    }

    private boolean within(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}