package com.aifriend.task.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskRevisionMode;
import com.aifriend.task.domain.TaskState;
import com.aifriend.template.application.SafetyCommandTemplateRepositoryPort;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandType;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * TASK 音频事务外校验、识别、纠正解释和联系人匹配编排服务。
 *
 * <p>ASR 和声学匹配不在数据库事务中运行；任一步失败都不消费音频、不创建任务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskCreationService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(5);

    private final TaskSessionRepositoryPort repositoryPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final SafetyCommandTemplateRepositoryPort safetyTemplateRepositoryPort;
    private final AudioObjectConsumptionService audioConsumptionService;
    private final TaskSpeechRecognitionPort speechRecognitionPort;
    private final TaskConversationUnderstandingPort understandingPort;
    private final TaskPersonalizationPort personalizationPort;
    private final TaskContactMatcherPort contactMatcherPort;
    private final TaskContinuationResolver continuationResolver;
    private final TaskPayloadCodec payloadCodec;
    private final TaskCreationTransactionService transactionService;
    private final TaskSessionMapper mapper;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建任务语音链编排服务。
     *
     * @param repositoryPort 任务会话端口
     * @param consentGrantQueryPort 授权查询端口
     * @param safetyTemplateRepositoryPort 安全指令模板端口
     * @param audioConsumptionService TASK 音频校验服务
     * @param speechRecognitionPort 事务外语音识别端口
     * @param understandingPort 首轮及多轮共用的上下文语义理解端口
     * @param personalizationPort 只读有限个人偏好端口
     * @param contactMatcherPort owner 范围联系人匹配端口
     * @param continuationResolver 消息继续联系人事实解析器
     * @param payloadCodec 任务载荷保护器
     * @param transactionService 任务与音频原子提交服务
     * @param mapper 会话映射器
     * @param digestService 摘要服务
     * @param clock UTC 时钟
     */
    public TaskCreationService(
            TaskSessionRepositoryPort repositoryPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            SafetyCommandTemplateRepositoryPort safetyTemplateRepositoryPort,
            AudioObjectConsumptionService audioConsumptionService,
            TaskSpeechRecognitionPort speechRecognitionPort,
            TaskConversationUnderstandingPort understandingPort,
            TaskPersonalizationPort personalizationPort,
            TaskContactMatcherPort contactMatcherPort,
            TaskContinuationResolver continuationResolver,
            TaskPayloadCodec payloadCodec,
            TaskCreationTransactionService transactionService,
            TaskSessionMapper mapper,
            DigestService digestService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.safetyTemplateRepositoryPort = safetyTemplateRepositoryPort;
        this.audioConsumptionService = audioConsumptionService;
        this.speechRecognitionPort = speechRecognitionPort;
        this.understandingPort = understandingPort;
        this.personalizationPort = personalizationPort;
        this.contactMatcherPort = contactMatcherPort;
        this.continuationResolver = continuationResolver;
        this.payloadCodec = payloadCodec;
        this.transactionService = transactionService;
        this.mapper = mapper;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 创建并处理当前方言任务，但不执行微信动作。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param idempotencyKey 创建幂等键，不得记录日志
     * @param command TASK 音频和客户端版本上下文
     * @return 当前处理阶段任务会话
     * @throws BusinessException 授权、模板、音频、识别、匹配或幂等冲突时抛出
     */
    public TaskSessionView create(
            UUID ownerUserId,
            String idempotencyKey,
            CreateTaskCommand command) {
        CreateTaskCommand normalized = normalize(command);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(normalized.fingerprintInput());
        TaskStoredSession replay = repositoryPort.findByCreateKey(ownerUserId, keyHash)
                .orElse(null);
        if (replay != null) {
            if (!digestService.constantTimeEquals(replay.createRequestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return mapper.toView(replay);
        }
        verifyEligibility(ownerUserId, normalized.clientContext());
        ValidatedAudioObject audio = audioConsumptionService.validateAll(
                ownerUserId, List.of(normalized.audioObjectId()), AudioPurpose.TASK).get(0);
        TaskSpeechRecognition recognition = speechRecognitionPort.recognize(
                audio, normalized.clientContext());
        validateRecognition(recognition, audio.actualDurationMs());
        TaskConversationPreferences preferences =
                personalizationPort.currentPreferences(ownerUserId);
        TaskDraftRevision interpretation = understandingPort.revise(
                null, recognition, audio.actualDurationMs(),
                TaskRevisionMode.FULL_RETRY, preferences);
        TaskSpeechRecognition effectiveRecognition = interpretation.recognition();
        validateRecognition(effectiveRecognition, audio.actualDurationMs());
        TaskIntent intent = interpretation.intent();
        List<TaskContactCandidate> matches = shouldMatchContacts(
                interpretation.outcome(), intent)
                ? contactMatcherPort.match(
                        ownerUserId, audio, effectiveRecognition,
                        normalized.clientContext())
                : List.of();
        TaskContinuationResolution continuation = resolveContinuation(
                ownerUserId, normalized, interpretation.outcome(), intent, matches);
        if (continuation != null) {
            matches = List.of(continuation.candidate());
        }
        validateMatches(matches);
        return persist(ownerUserId, keyHash, requestHash, normalized, audio,
                effectiveRecognition, intent, interpretation.outcome(), matches,
                interpretation.messageText(), interpretation.corrections(), continuation,
                interpretation.routineCommandLearningEvidence());
    }

    private TaskSessionView persist(
            UUID ownerUserId,
            byte[] keyHash,
            byte[] requestHash,
            CreateTaskCommand command,
            ValidatedAudioObject audio,
            TaskSpeechRecognition recognition,
            TaskIntent intent,
            TaskInterpretationOutcome outcome,
            List<TaskContactCandidate> matches,
            String messageText,
            List<TaskCorrectionView> corrections,
            TaskContinuationResolution continuation,
            RoutineCommandLearningEvidence routineLearningEvidence) {
        Instant now = Instant.now(clock);
        UUID sessionId = UUID.randomUUID();
        List<TaskCandidateView> candidates = matches.stream()
                .map(match -> new TaskCandidateView(
                        match.candidateId(),
                        new TaskMatchedContactView(match.publicContactId(),
                                match.displayName(), match.alias()),
                        match.scoreBand(), match.rank()))
                .toList();
        boolean uniqueMatch = matches.size() == 1
                && "UNIQUE".equals(matches.get(0).scoreBand());
        TaskMatchedContactView selected = uniqueMatch
                ? candidates.get(0).contact() : null;
        TaskState state = initialState(outcome, matches);
        String summary = selected == null ? null
                : summary(intent, selected, messageText);
        String summaryHash = summary == null ? null : hashSummary(summary);
        Set<TaskAction> actions = initialActions(state, intent);
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                intent, selected, recognition.transcript(), messageText,
                recognition.effectiveAudioRanges(), corrections,
                recognition.confidence(),
                new TaskProcessingVersionsView(
                        command.clientContext().dialectCode(),
                        command.clientContext().dialectPackageVersion(),
                        recognition.primaryAsrModelVersion(),
                        recognition.mandarinAssistModelVersion(),
                        recognition.fusionRuleVersion(),
                        recognition.alignmentVersion(),
                        command.clientContext().templateModelVersion(),
                        command.clientContext().thresholdVersion()));
        TaskConversationContext conversationContext = TaskConversationContext.initial(
                recognition.transcript(), summary);
        TaskPayload payload = new TaskPayload(
                command.clientContext(), understanding, candidates, summary, actions,
                state == TaskState.AWAITING_CONFIRMATION ? now : null, null, null,
                outcome == TaskInterpretationOutcome.READY
                        ? routineLearningEvidence : null,
                conversationContext);
        TaskStoredSession stored = new TaskStoredSession(
                sessionId, ownerUserId, audio.audioObjectId(), command.clientTaskId(),
                keyHash, requestHash, state, payloadCodec.encode(payload),
                uniqueMatch ? matches.get(0).contactId() : null,
                summaryHash, null, null, 1, now.plus(SESSION_TTL), now, now);
        List<TaskStoredCandidate> storedCandidates = matches.stream()
                .map(match -> new TaskStoredCandidate(
                        UUID.randomUUID(), sessionId, ownerUserId, match.contactId(),
                        match.scoreBand(), match.rank(), now))
                .toList();
        return transactionService.create(
                stored, storedCandidates, audio,
                continuation == null ? null : continuation.sourceSessionId());
    }

    private TaskContinuationResolution resolveContinuation(
            UUID ownerUserId,
            CreateTaskCommand command,
            TaskInterpretationOutcome outcome,
            TaskIntent intent,
            List<TaskContactCandidate> matches) {
        if (outcome != TaskInterpretationOutcome.READY
                || intent != TaskIntent.SEND_MESSAGE
                || !matches.isEmpty()
                || !StringUtils.hasText(command.previousConfirmedContactId())) {
            return null;
        }
        return continuationResolver.resolve(
                ownerUserId, command.previousConfirmedContactId()).orElse(null);
    }

    private CreateTaskCommand normalize(CreateTaskCommand command) {
        if (command == null || !within(command.clientTaskId(), 1, 64)
                || !within(command.audioObjectId(), 4, 64)
                || command.clientContext() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        TaskClientContext context = command.clientContext();
        boolean invalid = !within(context.appVersion(), 1, 40)
                || !within(context.wechatVersion(), 1, 40)
                || !within(context.ruleVersion(), 1, 40)
                || !within(context.dialectCode(), 2, 40)
                || !within(context.dialectPackageVersion(), 1, 60)
                || !within(context.mandarinAssistVersion(), 1, 60)
                || !within(context.fusionRuleVersion(), 1, 60)
                || !within(context.templateModelVersion(), 1, 60)
                || !within(context.thresholdVersion(), 1, 60);
        if (invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (command.previousConfirmedContactId() != null) {
            PublicIdCodec.parseContactId(command.previousConfirmedContactId());
        }
        return command;
    }

    private void verifyEligibility(UUID ownerUserId, TaskClientContext context) {
        if (!consentGrantQueryPort.isGranted(ownerUserId, ConsentType.TASK_AUDIO)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
        List<SafetyCommandTemplate> templates =
                safetyTemplateRepositoryPort.findActiveByOwner(ownerUserId);
        Set<SafetyCommandType> actual = EnumSet.noneOf(SafetyCommandType.class);
        templates.forEach(template -> actual.add(template.commandType()));
        if (templates.size() != 4 || actual.size() != 4) {
            throw new BusinessException(ErrorCode.SAFETY_COMMAND_REQUIRED);
        }
        boolean incompatible = templates.stream().anyMatch(template ->
                !template.hasPersistedMaterial()
                || !template.matchesVersions(
                        context.dialectCode(), context.dialectPackageVersion(),
                        context.templateModelVersion(), context.thresholdVersion()));
        if (incompatible) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
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

    /**
     * 根据有限意图与声学候选置入首个交互状态。
     *
     * @param outcome 语音解释后的有限处理结果
     * @param matches 按声学距离排序的候选
     * @return 取消、针对性重说、通用重试、选择或确认状态
     */
    static TaskState initialState(
            TaskInterpretationOutcome outcome,
            List<TaskContactCandidate> matches) {
        if (outcome == TaskInterpretationOutcome.CANCELLED) {
            return TaskState.CANCELLED;
        }
        if (outcome == TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT) {
            return TaskState.NEEDS_CONTENT_REPEAT;
        }
        if (outcome == TaskInterpretationOutcome.NEEDS_RETRY) {
            return TaskState.NEEDS_RETRY;
        }
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        boolean uniqueMatch = matches.size() == 1
                && "UNIQUE".equals(matches.get(0).scoreBand());
        return uniqueMatch
                ? TaskState.AWAITING_CONFIRMATION : TaskState.AWAITING_SELECTION;
    }

    /**
     * 判断解释结果是否允许进入 owner 范围联系人声学匹配。
     *
     * @param outcome 语音解释后的有限处理结果
     * @param intent 当前有限意图
     * @return 只有完整通信意图返回 {@code true}
     */
    static boolean shouldMatchContacts(
            TaskInterpretationOutcome outcome,
            TaskIntent intent) {
        return (outcome == TaskInterpretationOutcome.READY
                || outcome == TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT)
                && requiresContact(intent);
    }

    static Set<TaskAction> initialActions(TaskState state, TaskIntent intent) {
        if (state == TaskState.AWAITING_SELECTION) {
            return EnumSet.of(TaskAction.SELECT_CANDIDATE, TaskAction.RETRY,
                    TaskAction.CANCEL);
        }
        if (state == TaskState.AWAITING_CONFIRMATION) {
            return intent == TaskIntent.SEND_MESSAGE
                    ? EnumSet.of(TaskAction.CONFIRM_SEND, TaskAction.CORRECT,
                            TaskAction.REJECT, TaskAction.CANCEL)
                    : EnumSet.of(TaskAction.CONFIRM_CALL, TaskAction.CORRECT,
                            TaskAction.REJECT, TaskAction.CANCEL);
        }
        if (state == TaskState.NEEDS_RETRY
                || state == TaskState.NEEDS_CONTENT_REPEAT) {
            return EnumSet.of(TaskAction.RETRY, TaskAction.CANCEL);
        }
        return EnumSet.noneOf(TaskAction.class);
    }

    private String summary(TaskIntent intent, TaskMatchedContactView contact, String messageText) {
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

    private static boolean requiresContact(TaskIntent intent) {
        return intent == TaskIntent.SEND_MESSAGE || intent == TaskIntent.VOICE_CALL
                || intent == TaskIntent.VIDEO_CALL;
    }

    private boolean within(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}
