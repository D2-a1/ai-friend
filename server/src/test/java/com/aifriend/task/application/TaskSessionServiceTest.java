package com.aifriend.task.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskAction;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskState;
import com.aifriend.template.application.SafetyCommandTemplateRepositoryPort;
import com.aifriend.template.application.RoutineCommandLearningQueuePort;
import com.aifriend.template.application.RoutineCommandLearningRequest;
import com.aifriend.template.application.RoutineCommandTemplateStorePort;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;
import com.aifriend.template.domain.SafetyCommandType;

class TaskSessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    private TaskSessionRepositoryPort repositoryPort;
    private SafetyCommandTemplateRepositoryPort templateRepositoryPort;
    private TaskPayloadCodec payloadCodec;
    private RoutineCommandTemplateStorePort routineTemplateStorePort;
    private RoutineCommandLearningQueuePort routineLearningQueuePort;
    private TaskSessionMapper mapper;
    private WechatActionContactProjectionPort contactProjectionPort;
    private WechatActionPlanProofIssuer proofIssuer;
    private TaskSessionService service;
    private TaskPayload payload;
    private TaskStoredSession session;
    private UUID ownerUserId;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(TaskSessionRepositoryPort.class);
        templateRepositoryPort = mock(SafetyCommandTemplateRepositoryPort.class);
        payloadCodec = mock(TaskPayloadCodec.class);
        mapper = mock(TaskSessionMapper.class);
        routineTemplateStorePort = mock(RoutineCommandTemplateStorePort.class);
        routineLearningQueuePort = mock(RoutineCommandLearningQueuePort.class);
        contactProjectionPort = mock(WechatActionContactProjectionPort.class);
        proofIssuer = mock(WechatActionPlanProofIssuer.class);
        ownerUserId = UUID.randomUUID();
        payload = payload(NOW.minusSeconds(1));
        session = session(TaskState.AWAITING_CONFIRMATION, 1);
        when(repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, session.id()))
                .thenReturn(Optional.of(session));
        when(payloadCodec.decode(any())).thenReturn(payload);
        when(payloadCodec.encode(any())).thenReturn(new byte[] {9});
        when(repositoryPort.saveSession(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.toView(any())).thenAnswer(invocation -> {
            TaskStoredSession value = invocation.getArgument(0);
            return new TaskSessionView(
                    PublicIdCodec.taskSessionId(value.id()), value.sessionVersion(),
                    value.state(), payload.understanding(), payload.candidates(),
                    payload.spokenSummary(), value.summaryHash(), Set.of(), null,
                    value.expiresAt());
        });
        when(routineTemplateStorePort.lockNamespace(ownerUserId, NOW))
                .thenReturn(3L);
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                enabledWechatExecution(), new DebugMvpDemoProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void cancelMustUseCancelTemplateAndBecomeIrreversibleTerminalState() {
        SafetyCommandTemplate template = template(SafetyCommandType.CANCEL);
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CANCEL, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(template.id()), NOW.minusSeconds(1));

        TaskConfirmationResult result = service.confirm(
                ownerUserId, PublicIdCodec.taskSessionId(session.id()),
                "01JTASKCONFIRM00000000000001", command);

        assertEquals(TaskState.CANCELLED, result.session().state());
        assertNull(result.actionPlan());
        verify(repositoryPort).saveOperation(any());
    }

    @Test
    void staleRecognitionMustExpireWithoutChangingSession() {
        verify(routineLearningQueuePort, never()).enqueue(any(), any(Long.class), any());
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CANCEL, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(UUID.randomUUID()), NOW.minusSeconds(11));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRM00000000000002", command));

        assertEquals(ErrorCode.SESSION_EXPIRED, exception.errorCode());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void wrongSummaryMustFailBeforeTemplateLookup() {
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CANCEL, 1, "other-summary-hash",
                PublicIdCodec.voiceTemplateId(UUID.randomUUID()), NOW.minusSeconds(1));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRM00000000000003", command));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(templateRepositoryPort, never()).findActiveByOwnerAndId(eq(ownerUserId), any());
    }

    @Test
    void confirmedSendMustBindVerifiedContactAndSignedLocatorProof() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        UUID contactId = PublicIdCodec.parseContactId(
                payload.understanding().contact().id());
        WechatActionContactSnapshot contact = new WechatActionContactSnapshot(
                contactId, 7, "verified-stable-locator", "1", "rule-v1");
        WechatTargetLocatorProofView proof = new WechatTargetLocatorProofView(
                WechatActionPlanProofIssuer.PROOF_VERSION, "test-key", 7,
                "1", "rule-v1", "a".repeat(32), "b".repeat(64), NOW,
                NOW.plusSeconds(30), "c".repeat(86));
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        when(contactProjectionPort.findVerifiedForUpdate(
                ownerUserId, contactId, "1", "rule-v1", true))
                .thenReturn(Optional.of(contact));
        when(proofIssuer.issue(any(), eq("verified-stable-locator")))
                .thenReturn(proof);
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CONFIRM_SEND, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(template.id()), NOW.minusSeconds(1));

        TaskConfirmationResult result = service.confirm(
                ownerUserId, PublicIdCodec.taskSessionId(session.id()),
                "01JTASKCONFIRM00000000000004", command);

        assertEquals(TaskState.EXECUTING, result.session().state());
        assertNotNull(result.actionPlan());
        assertEquals(proof, result.actionPlan().targetLocatorProof());
        assertEquals(payload.understanding().contact().id(), result.actionPlan().contactId());
        ArgumentCaptor<WechatActionPlanProofClaims> claimsCaptor =
                ArgumentCaptor.forClass(WechatActionPlanProofClaims.class);
        verify(proofIssuer).issue(claimsCaptor.capture(), eq("verified-stable-locator"));
        assertEquals(payload.context().wechatVersion(), claimsCaptor.getValue().wechatVersion());
        verify(routineTemplateStorePort).lockNamespace(ownerUserId, NOW);
        verify(routineLearningQueuePort).enqueue(
                any(RoutineCommandLearningRequest.class), eq(3L), eq(NOW));
    }

    @Test
    void confirmedVoiceCallMustAllowDifferentWechatVersionAndBindCurrentVersionProof() {
        assertCallPlan(
                TaskIntent.VOICE_CALL,
                "START_VOICE_CALL",
                "01JTASKCONFIRMVOICE0000000001");
    }

    @Test
    void confirmedVideoCallMustAllowDifferentWechatVersionAndBindCurrentVersionProof() {
        assertCallPlan(
                TaskIntent.VIDEO_CALL,
                "START_VIDEO_CALL",
                "01JTASKCONFIRMVIDEO0000000001");
    }

    @Test
    void messageMustRejectWhenCurrentWechatVersionDiffersFromApprovedVersion() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                new WechatExecutionProperties(true, List.of(
                        new WechatExecutionProperties.ApprovedClientCombination(
                                "1", "8.0.56", "rule-v1"))),
                new DebugMvpDemoProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(
                        ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRMMESSAGE00000001",
                        new ConfirmTaskCommand(
                                TaskAction.CONFIRM_SEND,
                                1,
                                "summary-hash",
                                PublicIdCodec.voiceTemplateId(template.id()),
                                NOW.minusSeconds(1))));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(contactProjectionPort, never()).findVerifiedForUpdate(
                any(), any(), any(), any(), anyBoolean());
        verify(proofIssuer, never()).issue(any(), any());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void messageMustRejectWhenContactVerifiedWechatVersionDiffersFromCurrentVersion() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        UUID contactId = PublicIdCodec.parseContactId(
                payload.understanding().contact().id());
        WechatActionContactSnapshot contact = new WechatActionContactSnapshot(
                contactId, 7, "verified-stable-locator", "8.0.56", "rule-v1");
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        when(contactProjectionPort.findVerifiedForUpdate(
                ownerUserId, contactId, "1", "rule-v1", true))
                .thenReturn(Optional.of(contact));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(
                        ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRMMESSAGE00000002",
                        new ConfirmTaskCommand(
                                TaskAction.CONFIRM_SEND,
                                1,
                                "summary-hash",
                                PublicIdCodec.voiceTemplateId(template.id()),
                                NOW.minusSeconds(1))));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(proofIssuer, never()).issue(any(), any());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void missingVerifiedContactMustNotTransitionTaskToExecuting() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        UUID contactId = PublicIdCodec.parseContactId(
                payload.understanding().contact().id());
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        when(contactProjectionPort.findVerifiedForUpdate(
                ownerUserId, contactId, "1", "rule-v1", true))
                .thenReturn(Optional.empty());
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CONFIRM_SEND, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(template.id()), NOW.minusSeconds(1));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRM00000000000005", command));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(repositoryPort, never()).saveSession(any());
        verify(proofIssuer, never()).issue(any(), any());
        verify(routineTemplateStorePort, never()).lockNamespace(any(), any());
        verify(routineLearningQueuePort, never()).enqueue(any(), any(Long.class), any());
    }

    @Test
    void disabledWechatExecutionMustFailBeforeReadingContactLocator() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                new WechatExecutionProperties(false, List.of()),
                new DebugMvpDemoProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CONFIRM_SEND, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(template.id()), NOW.minusSeconds(1));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRM00000000000006", command));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(contactProjectionPort, never()).findVerifiedForUpdate(
                any(), any(), any(), any(), anyBoolean());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void ruleMismatchMustNotUsePartialClientCombinationMatch() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                new WechatExecutionProperties(true, List.of(
                        new WechatExecutionProperties.ApprovedClientCombination(
                                "1", "1", "rule-v2"))),
                new DebugMvpDemoProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ConfirmTaskCommand command = new ConfirmTaskCommand(
                TaskAction.CONFIRM_SEND, 1, "summary-hash",
                PublicIdCodec.voiceTemplateId(template.id()), NOW.minusSeconds(1));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirm(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCONFIRM00000000000007", command));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(contactProjectionPort, never()).findVerifiedForUpdate(
                any(), any(), any(), any(), anyBoolean());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void approvedAppAndRuleMustAllowDifferentWechatVersionForCalls() {
        WechatExecutionProperties properties = new WechatExecutionProperties(true, List.of(
                new WechatExecutionProperties.ApprovedClientCombination(
                        "1", "8.0.56", WechatSemanticCallContract.RULE_VERSION)));

        TaskClientContext context = contextWithWechatAndRuleVersion(
                "8.0.76", WechatSemanticCallContract.RULE_VERSION);
        assertTrue(properties.supports(context, "START_VOICE_CALL"));
        assertTrue(properties.supports(context, "START_VIDEO_CALL"));
    }

    @Test
    void appOrRuleMismatchMustRemainRejected() {
        TaskClientContext context = contextWithWechatAndRuleVersion(
                "8.0.76", WechatSemanticCallContract.RULE_VERSION);
        WechatExecutionProperties appMismatch = new WechatExecutionProperties(true, List.of(
                new WechatExecutionProperties.ApprovedClientCombination(
                        "2", "8.0.76", WechatSemanticCallContract.RULE_VERSION)));
        WechatExecutionProperties ruleMismatch = new WechatExecutionProperties(true, List.of(
                new WechatExecutionProperties.ApprovedClientCombination(
                        "1", "8.0.76", "rule-v2")));
        WechatExecutionProperties misconfiguredLegacyRule =
                new WechatExecutionProperties(true, List.of(
                        new WechatExecutionProperties.ApprovedClientCombination(
                                "1", "8.0.76", "rule-v1")));

        assertFalse(appMismatch.supports(context, "START_VOICE_CALL"));
        assertFalse(ruleMismatch.supports(context, "START_VOICE_CALL"));
        assertFalse(misconfiguredLegacyRule.supports(
                contextWithWechatAndRuleVersion("8.0.76", "rule-v1"),
                "START_VOICE_CALL"));
    }

    @Test
    void lateChannelResultMustNeverReviveCancelledSession() {
        session = session(TaskState.CANCELLED, 2);
        when(repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, session.id()))
                .thenReturn(Optional.of(session));
        ReportTaskChannelResultCommand command = new ReportTaskChannelResultCommand(
                "wp_late", "summary-hash", "SENT",
                List.of(new TaskChannelPartView("TEXT", "SENT", "trusted-v1")),
                "rule-v1", NOW);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.reportChannelResult(ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCHANNEL00000000000001", command));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void semanticCallMustRejectCallStartedClaim() {
        useCallPayload(TaskIntent.VOICE_CALL);
        WechatTargetLocatorProofView proof = new WechatTargetLocatorProofView(
                WechatActionPlanProofIssuer.PROOF_VERSION,
                "test-key",
                7,
                payload.context().wechatVersion(),
                WechatSemanticCallContract.LOCATOR_VERSION,
                "a".repeat(32),
                "b".repeat(64),
                NOW.minusSeconds(1),
                NOW.plusSeconds(30),
                "c".repeat(86));
        WechatActionPlanView plan = new WechatActionPlanView(
                "wp_semantic_call",
                "START_VOICE_CALL",
                payload.understanding().contact().id(),
                null,
                "summary-hash",
                WechatSemanticCallContract.RULE_VERSION,
                NOW.plusSeconds(30),
                "wxid_demo123",
                proof);
        payload = new TaskPayload(
                payload.context(),
                payload.understanding(),
                payload.candidates(),
                payload.spokenSummary(),
                payload.allowedActions(),
                payload.confirmationStartedAt(),
                plan,
                null,
                payload.routineCommandLearningEvidence());
        session = session(TaskState.EXECUTING, 2);
        when(repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, session.id()))
                .thenReturn(Optional.of(session));
        when(payloadCodec.decode(any())).thenReturn(payload);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.reportChannelResult(
                        ownerUserId,
                        PublicIdCodec.taskSessionId(session.id()),
                        "01JTASKCHANNELSEMANTIC000001",
                        new ReportTaskChannelResultCommand(
                                plan.planId(),
                                plan.summaryHash(),
                                "CALL_STARTED",
                                List.of(new TaskChannelPartView(
                                        "CALL", "CALL_STARTED", "semantic-v1")),
                                plan.minimumRuleVersion(),
                                NOW)));

        assertEquals(ErrorCode.ACTION_UNSUPPORTED, exception.errorCode());
        verify(repositoryPort, never()).saveSession(any());
    }

    @Test
    void debugBasicExperienceContactMustFinishAsSimulatedWithoutWechatPlan() {
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_SEND);
        TaskClientContext currentContext = payload.context();
        TaskClientContext debugContext = new TaskClientContext(
                currentContext.appVersion(), currentContext.wechatVersion(),
                currentContext.ruleVersion(), currentContext.dialectCode(),
                currentContext.dialectPackageVersion(),
                currentContext.mandarinAssistVersion(),
                currentContext.fusionRuleVersion(),
                currentContext.templateModelVersion(),
                currentContext.thresholdVersion(),
                new TaskClientRecognitionEvidence(
                        "告诉女儿晚点回家", 0.91, "vosk-basic-v1",
                        "a".repeat(64), List.of()));
        payload = new TaskPayload(
                debugContext, payload.understanding(), payload.candidates(),
                payload.spokenSummary(), payload.allowedActions(),
                payload.confirmationStartedAt(), null, null,
                payload.routineCommandLearningEvidence());
        session = session(TaskState.AWAITING_CONFIRMATION, 1);
        when(repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, session.id()))
                .thenReturn(Optional.of(session));
        when(payloadCodec.decode(any())).thenReturn(payload);
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        when(contactProjectionPort.isDebugDemoContact(
                ownerUserId, session.selectedContactId())).thenReturn(true);
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                new WechatExecutionProperties(false, List.of()),
                new DebugMvpDemoProperties(true),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskConfirmationResult result = service.confirm(
                ownerUserId, PublicIdCodec.taskSessionId(session.id()),
                "01JTASKCONFIRM00000000000008",
                new ConfirmTaskCommand(
                        TaskAction.CONFIRM_SEND, 1, "summary-hash",
                        PublicIdCodec.voiceTemplateId(template.id()),
                        NOW.minusSeconds(1)));

        assertEquals(TaskState.SIMULATED, result.session().state());
        assertNull(result.actionPlan());
        verify(contactProjectionPort, never()).findVerifiedForUpdate(
                any(), any(), any(), any(), anyBoolean());
        verify(proofIssuer, never()).issue(any(), any());
        verify(routineLearningQueuePort, never()).enqueue(any(), any(Long.class), any());
    }

    @Test
    void recentResultsMustReturnOnlyMinimalTerminalTaskFields() {
        TaskStoredSession completed = session(TaskState.COMPLETED, 3);
        TaskStoredSession failed = session(TaskState.FAILED, 2);
        when(repositoryPort.listRecentResultsByOwner(ownerUserId))
                .thenReturn(List.of(completed, failed));

        List<RecentTaskResultView> results = service.listRecentResults(ownerUserId);

        assertEquals(2, results.size());
        assertEquals(PublicIdCodec.taskSessionId(completed.id()), results.get(0).sessionId());
        assertEquals(TaskState.COMPLETED, results.get(0).state());
        assertEquals(TaskIntent.SEND_MESSAGE, results.get(0).intent());
        assertEquals(completed.createdAt(), results.get(0).createdAt());
        assertEquals(completed.updatedAt(), results.get(0).updatedAt());
        assertEquals(TaskState.FAILED, results.get(1).state());
        verify(repositoryPort).listRecentResultsByOwner(ownerUserId);
    }

    private TaskStoredSession session(TaskState state, long version) {
        return new TaskStoredSession(
                UUID.randomUUID(), ownerUserId, UUID.randomUUID(), "client-task",
                new byte[32], new byte[32], state, new byte[] {1},
                PublicIdCodec.parseContactId(payload.understanding().contact().id()),
                "summary-hash", null, null, version,
                NOW.plusSeconds(120), NOW.minusSeconds(2), NOW.minusSeconds(1));
    }

    private TaskPayload payload(Instant confirmationStartedAt) {
        TaskClientContext context = new TaskClientContext(
                "1", "1", "rule-v1", "zh-Hans-CN-x-wugang",
                "dialect-v1", "mandarin-v1", "fusion-v1", "mfcc-v1", "t-v1");
        TaskMatchedContactView contact = new TaskMatchedContactView(
                PublicIdCodec.contactId(UUID.randomUUID()), "女儿", "妹伢");
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                TaskIntent.SEND_MESSAGE, contact, "告诉女儿晚点回家",
                "晚点回家",
                List.of(new TaskAudioRange(0, 900)), List.of(), 0.9,
                new TaskProcessingVersionsView(
                        context.dialectCode(), context.dialectPackageVersion(),
                        "asr-v1", context.mandarinAssistVersion(),
                        context.fusionRuleVersion(), "align-v1",
                        context.templateModelVersion(), context.thresholdVersion()));
        return new TaskPayload(
                context, understanding, List.of(), "给女儿发送消息：晚点回家",
                Set.of(TaskAction.CONFIRM_SEND, TaskAction.REJECT, TaskAction.CANCEL),
                confirmationStartedAt, null, null,
                new RoutineCommandLearningEvidence(
                        TaskIntent.SEND_MESSAGE, new TaskAudioRange(0, 300)));
    }

    private void assertCallPlan(
            TaskIntent intent,
            String expectedPlanAction,
            String idempotencyKey) {
        useCallPayload(intent);
        SafetyCommandTemplate template = template(SafetyCommandType.CONFIRM_CALL);
        UUID contactId = PublicIdCodec.parseContactId(
                payload.understanding().contact().id());
        WechatActionContactSnapshot contact = new WechatActionContactSnapshot(
                contactId, 7, "wxid_demo123", "8.0.56",
                WechatSemanticCallContract.LOCATOR_VERSION);
        WechatTargetLocatorProofView proof = new WechatTargetLocatorProofView(
                WechatActionPlanProofIssuer.PROOF_VERSION, "test-key", 7,
                payload.context().wechatVersion(), WechatSemanticCallContract.LOCATOR_VERSION,
                "a".repeat(32),
                "b".repeat(64), NOW, NOW.plusSeconds(30), "c".repeat(86));
        when(templateRepositoryPort.findActiveByOwnerAndId(ownerUserId, template.id()))
                .thenReturn(Optional.of(template));
        when(contactProjectionPort.findVerifiedForUpdate(
                ownerUserId, contactId, payload.context().wechatVersion(),
                WechatSemanticCallContract.LOCATOR_VERSION, false))
                .thenReturn(Optional.of(contact));
        when(proofIssuer.issue(any(), eq("wxid_demo123")))
                .thenReturn(proof);
        service = new TaskSessionService(
                repositoryPort, templateRepositoryPort, routineTemplateStorePort,
                routineLearningQueuePort, payloadCodec, mapper,
                new DigestService(), contactProjectionPort, proofIssuer,
                new WechatExecutionProperties(true, List.of(
                        new WechatExecutionProperties.ApprovedClientCombination(
                                "1", "8.0.56",
                                WechatSemanticCallContract.RULE_VERSION))),
                new DebugMvpDemoProperties(false),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskConfirmationResult result = service.confirm(
                ownerUserId,
                PublicIdCodec.taskSessionId(session.id()),
                idempotencyKey,
                new ConfirmTaskCommand(
                        TaskAction.CONFIRM_CALL,
                        1,
                        "summary-hash",
                        PublicIdCodec.voiceTemplateId(template.id()),
                        NOW.minusSeconds(1)));

        assertEquals(TaskState.EXECUTING, result.session().state());
        assertNotNull(result.actionPlan());
        assertEquals(expectedPlanAction, result.actionPlan().action());
        assertNull(result.actionPlan().audioObjectId());
        assertEquals("wxid_demo123", result.actionPlan().targetSearchLocator());
        assertEquals(proof, result.actionPlan().targetLocatorProof());
        ArgumentCaptor<WechatActionPlanProofClaims> claimsCaptor =
                ArgumentCaptor.forClass(WechatActionPlanProofClaims.class);
        verify(proofIssuer).issue(claimsCaptor.capture(), eq("wxid_demo123"));
        assertEquals(payload.context().wechatVersion(), claimsCaptor.getValue().wechatVersion());
        assertEquals(expectedPlanAction, claimsCaptor.getValue().action());
        assertEquals(WechatSemanticCallContract.RULE_VERSION,
                claimsCaptor.getValue().minimumRuleVersion());
        assertEquals(WechatSemanticCallContract.LOCATOR_VERSION,
                claimsCaptor.getValue().locatorVersion());
    }

    private void useCallPayload(TaskIntent intent) {
        TaskClientContext currentContext = payload.context();
        TaskClientContext callContext = new TaskClientContext(
                currentContext.appVersion(),
                currentContext.wechatVersion(),
                WechatSemanticCallContract.RULE_VERSION,
                currentContext.dialectCode(),
                currentContext.dialectPackageVersion(),
                currentContext.mandarinAssistVersion(),
                currentContext.fusionRuleVersion(),
                currentContext.templateModelVersion(),
                currentContext.thresholdVersion(),
                currentContext.basicRecognition());
        TaskUnderstandingView current = payload.understanding();
        TaskUnderstandingView callUnderstanding = new TaskUnderstandingView(
                intent,
                current.contact(),
                current.transcript(),
                null,
                current.effectiveAudioRanges(),
                current.corrections(),
                current.confidence(),
                current.processingVersions());
        String spokenSummary = intent == TaskIntent.VOICE_CALL
                ? "给女儿发起微信语音通话"
                : "给女儿发起微信视频通话";
        payload = new TaskPayload(
                callContext,
                callUnderstanding,
                List.of(),
                spokenSummary,
                Set.of(TaskAction.CONFIRM_CALL, TaskAction.REJECT, TaskAction.CANCEL),
                NOW.minusSeconds(1),
                null,
                null,
                new RoutineCommandLearningEvidence(intent, new TaskAudioRange(0, 300)));
        session = session(TaskState.AWAITING_CONFIRMATION, 1);
        when(repositoryPort.findByOwnerAndIdForUpdate(ownerUserId, session.id()))
                .thenReturn(Optional.of(session));
        when(payloadCodec.decode(any())).thenReturn(payload);
    }

    private SafetyCommandTemplate template(SafetyCommandType type) {
        UUID id = UUID.randomUUID();
        return new SafetyCommandTemplate(
                id, UUID.randomUUID(), ownerUserId, type,
                payload.context().dialectCode(), payload.context().dialectPackageVersion(),
                payload.context().templateModelVersion(), payload.context().thresholdVersion(),
                new byte[] {1}, new byte[32], SafetyCommandTemplateStatus.ACTIVE,
                0, NOW.minusSeconds(20), NOW.minusSeconds(20), null);
    }

    private TaskClientContext contextWithWechatVersion(String wechatVersion) {
        return contextWithWechatAndRuleVersion(
                wechatVersion,
                payload.context().ruleVersion());
    }

    private TaskClientContext contextWithWechatAndRuleVersion(
            String wechatVersion,
            String ruleVersion) {
        TaskClientContext context = payload.context();
        return new TaskClientContext(
                context.appVersion(), wechatVersion, ruleVersion,
                context.dialectCode(), context.dialectPackageVersion(),
                context.mandarinAssistVersion(), context.fusionRuleVersion(),
                context.templateModelVersion(), context.thresholdVersion(),
                context.basicRecognition());
    }

    private WechatExecutionProperties enabledWechatExecution() {
        return new WechatExecutionProperties(true, List.of(
                new WechatExecutionProperties.ApprovedClientCombination(
                        "1", "1", "rule-v1")));
    }
}
