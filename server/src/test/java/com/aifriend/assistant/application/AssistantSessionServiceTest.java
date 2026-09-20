package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.assistant.domain.AssistantTurnRequest.State;
import com.aifriend.assistant.application.AssistantTurnRepository.Receipt;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;

/** 编排故障注入：模拟下游但执行真实受理版本/token/原截止协调，不调用外部设施。 */
class AssistantSessionServiceTest {
    private final UUID owner=id(1),sid=id(2),rid=id(3),token=id(4);
    private final Instant start=Instant.parse("2026-09-10T10:00:00Z");
    private final AssistantTurnRepository requests=mock(AssistantTurnRepository.class);
    private final AssistantResultReader reader=mock(AssistantResultReader.class);
    private final AssistantResultProtectionPort protection=mock(AssistantResultProtectionPort.class);
    private final AssistantResultRevalidator revalidator=mock(AssistantResultRevalidator.class);
    private final KnowledgeAnswerService knowledge=mock(KnowledgeAnswerService.class);
    private final GraphQueryPort graph=mock(GraphQueryPort.class);
    @SuppressWarnings("unchecked") private final Supplier<Optional<String>> profile=mock(Supplier.class);
    private final MutableClock clock=new MutableClock();
    private AssistantSessionService service;
    private Receipt admitted;
    private AssistantQuestion question;
    private AssistantResultReader.Result replay;
    private final byte[] encrypted=new byte[29];
    private static UUID id(int n) { return new UUID(0,n); }
    @BeforeEach void setup() {
        question=new AssistantQuestion(0,"question-key-0001","zh-CN",1,new AssistantQuestion.PublicText("如何开启守护"));
        bind(Purpose.PUBLIC_KNOWLEDGE);
        when(profile.get()).thenReturn(Optional.of("test-v1"));
        when(protection.encrypt(any(),any(),any())).thenReturn(encrypted);
        when(knowledge.answerWithEvidence(any())).thenReturn(new KnowledgeAnswerService.AnswerResult(
                new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NO_EVIDENCE,Mode.NONE,AssistantReason.NO_SUPPORT,"暂无资料",List.of(),List.of()),
                Optional.empty(),Optional.empty()));
        execution((deadline,operation)->operation.accept(()->{}));
    }
    private void bind(Purpose purpose) {
        var session=AssistantSession.create(sid,owner,purpose,"policy-v1",start).begin(0,rid,token,start);
        var request=new AssistantTurnRequest(rid,owner,sid,purpose,"a".repeat(64),"b".repeat(64),1,token,start,start.plusSeconds(8),
                start.plusSeconds(300),State.PROCESSING,null,null);
        admitted=new Receipt(session,request,true);
        replay=new AssistantResultReader.Result(1,rid,State.PROCESSING,null,Optional.empty());
        when(requests.admit(owner,sid,question)).thenReturn(admitted);
        when(reader.read(owner,sid,question.requestKey())).thenReturn(Optional.of(replay));
        when(reader.publicQueryHistory(eq(owner),eq(sid),eq(1L),eq("zh-CN"),eq(1),anyBoolean())).thenReturn(session.conversation());
    }
    @Test void onlyFirstReceiptProcessesAndUsesOriginalPersistentRequestAndDeadline() {
        assertThat(service.ask(owner,sid,question)).isSameAs(replay);
        var capture=ArgumentCaptor.forClass(KnowledgeAnswerService.Request.class);
        verify(knowledge).answerWithEvidence(capture.capture());
        assertThat(capture.getValue().owner()).isEqualTo(owner);
        assertThat(capture.getValue().operationId()).isEqualTo(rid);
        assertThat(capture.getValue().deadline()).isEqualTo(start.plusSeconds(8));
        assertThat(capture.getValue().generationProfile()).contains("test-v1");
        verify(requests).complete(eq(owner),eq(sid),eq(rid),eq(1L),eq(token),same(encrypted),any());
        assertThat(encrypted).containsOnly((byte)0);
        verifyNoInteractions(graph);
    }
    @Test void duplicateDoesNotReadProfileHistoryOrCallAnyProcessor() {
        when(requests.admit(owner,sid,question)).thenReturn(new Receipt(admitted.session(),admitted.request(),false));
        assertThat(service.ask(owner,sid,question)).isSameAs(replay);
        verifyNoInteractions(profile,knowledge,graph,protection,revalidator);
        verify(reader,never()).publicQueryHistory(any(),any(),anyLong(),anyString(),anyInt(),anyBoolean());
    }
    @Test void delayedAdmissionCannotResetEightSecondBudget() {
        when(requests.admit(owner,sid,question)).thenAnswer(call->{clock.now=start.plusSeconds(8);return admitted;});
        assertThat(service.ask(owner,sid,question)).isSameAs(replay);
        verifyNoInteractions(profile,knowledge,graph,protection,revalidator);
        verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
    }
    private void execution(AssistantExecutionPort port) {
        service=new AssistantSessionService(requests,reader,protection,revalidator,knowledge,graph,profile,clock,true,port,
                ()->Duration.between(start,clock.now).toNanos());
    }
    @Test void realExecutorAcceptsDatabaseClockSkewAndKeepsDatabaseDeadline() {
        for(long offset:new long[]{-5400,5400}) {
            clock.now=start.plusMillis(offset);
            try(var executor=new com.aifriend.assistant.infrastructure.BoundedAssistantExecutor(
                    new com.aifriend.assistant.infrastructure.AssistantExecutionProperties(1,1),clock)) {
                execution(executor);
                assertThat(service.ask(owner,sid,question)).isSameAs(replay);
            }
        }
        verify(knowledge,times(2)).answerWithEvidence(argThat(r->
                r.deadline().equals(start.plusSeconds(8)) && r.executionBudget().isPresent()));
        verify(requests,times(2)).complete(eq(owner),eq(sid),eq(rid),eq(1L),eq(token),any(),any());
    }
    @Test void realBoundedWorkerProcessesOnceAndDuplicateDoesNotQueue() {
        try(var executor=new com.aifriend.assistant.infrastructure.BoundedAssistantExecutor(
                new com.aifriend.assistant.infrastructure.AssistantExecutionProperties(1,1),clock)) {
            execution(executor); assertThat(service.ask(owner,sid,question)).isSameAs(replay);
            verify(knowledge).answerWithEvidence(any());
            when(requests.admit(owner,sid,question)).thenReturn(new Receipt(admitted.session(),admitted.request(),false));
            execution((deadline,operation)->{throw new AssertionError("DUPLICATE_QUEUED");});
            assertThat(service.ask(owner,sid,question)).isSameAs(replay);
            verify(requests).complete(any(),any(),any(),anyLong(),any(),any(),any());
        }
    }
    @Test void queueDelayCountsAgainstOriginalDeadlineAndDoesNotProcess() {
        execution((deadline,operation)->{
            assertThat(deadline).isEqualTo(start.plusSeconds(8)); clock.now=start.plusSeconds(8);
            operation.accept(()->{});
        });
        assertThat(service.ask(owner,sid,question)).isSameAs(replay);
        verifyNoInteractions(knowledge,graph,protection);
    }
    @Test void queueRejectionDoesNotRunOrResubmitQuestion() {
        execution((deadline,operation)->{throw new AssistantSessionException(AssistantReason.RESOURCE_LIMIT);});
        assertThatThrownBy(()->service.ask(owner,sid,question)).hasMessage("RESOURCE_LIMIT");
        verify(requests).admit(owner,sid,question); verifyNoInteractions(knowledge,graph,protection);
    }
    @Test void cancellationGateBlocksCommitEvenIfLowerLayerClearedInterrupt() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        when(knowledge.answerWithEvidence(any())).thenAnswer(call->{cancelled.set(true); Thread.interrupted();
            return new KnowledgeAnswerService.AnswerResult(new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NO_EVIDENCE,
                    Mode.NONE,AssistantReason.NO_SUPPORT,"暂无资料",List.of(),List.of()),Optional.empty(),Optional.empty());});
        execution((deadline,operation)->operation.accept(()->{if(cancelled.get()) throw new CancellationException();}));
        assertThatThrownBy(()->service.ask(owner,sid,question)).isInstanceOf(CancellationException.class);
        verifyNoInteractions(protection); verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
    }
    @Test void lateKnowledgeResultIsDiscardedWithoutSaving() {
        when(knowledge.answerWithEvidence(any())).thenAnswer(c->{clock.now=start.plusSeconds(8);return new KnowledgeAnswerService.AnswerResult(
                new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NO_EVIDENCE,Mode.NONE,AssistantReason.NO_SUPPORT,"暂无资料",List.of(),List.of()),
                Optional.empty(),Optional.empty());});
        service.ask(owner,sid,question);
        verifyNoInteractions(protection,revalidator);
        verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
    }
    @Test void commitUncertaintyIsNotBlindlyRetriedAndOwnedCipherIsCleared() {
        Arrays.fill(encrypted,(byte)7);
        when(requests.complete(any(),any(),any(),anyLong(),any(),any(),any())).thenThrow(new AssistantSessionException(AssistantReason.COMMIT_UNCERTAIN));
        assertThatThrownBy(()->service.ask(owner,sid,question)).hasMessage("COMMIT_UNCERTAIN");
        verify(knowledge,times(1)).answerWithEvidence(any());
        assertThat(encrypted).containsOnly((byte)0);
    }
    @Test void invalidHistoryCannotBeIgnoredToAnswerWithoutContext() {
        when(reader.publicQueryHistory(owner,sid,1,"zh-CN",1,true)).thenThrow(new AssistantSessionException(AssistantReason.EVIDENCE_INVALIDATED));
        assertThatThrownBy(()->service.ask(owner,sid,question)).hasMessage("EVIDENCE_INVALIDATED");
        verifyNoInteractions(knowledge,graph,profile,protection);
    }
    @Test void publicVerifiedHistoryReallyReachesKnowledgeProcessor() {
        var history=new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of(new AssistantConversation.Turn(id(9),1,"先前问题","原答案")));
        when(reader.publicQueryHistory(owner,sid,1,"zh-CN",1,true)).thenReturn(history);
        service.ask(owner,sid,question);
        verify(knowledge).answerWithEvidence(argThat(r->r.history().equals(history)));
        verify(reader,times(3)).publicQueryHistory(owner,sid,1,"zh-CN",1,true);
    }

    @Test void historyDeletedWhileAnsweringStopsBeforeEncryption() {
        var history=new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of(new AssistantConversation.Turn(id(9),1,"守护是什么","原答案")));
        when(reader.publicQueryHistory(owner,sid,1,"zh-CN",1,true)).thenReturn(history)
                .thenThrow(new AssistantSessionException(AssistantReason.EVIDENCE_INVALIDATED));
        assertThatThrownBy(()->service.ask(owner,sid,question)).hasMessage("EVIDENCE_INVALIDATED");
        verify(knowledge).answerWithEvidence(any());
        verifyNoInteractions(protection);
        verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
    }

    @Test void historyChangedDuringEncryptionClearsCipherAndDoesNotCommit() {
        Arrays.fill(encrypted,(byte)7);
        var history=new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of(new AssistantConversation.Turn(id(9),1,"守护是什么","原答案")));
        when(reader.publicQueryHistory(owner,sid,1,"zh-CN",1,true)).thenReturn(history,history,
                new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of()));
        assertThatThrownBy(()->service.ask(owner,sid,question)).hasMessage("EVIDENCE_INVALIDATED");
        verify(protection).encrypt(any(),any(),any());
        assertThat(encrypted).containsOnly((byte)0);
        verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
    }
    @Test void privateAmbiguousCandidatesNeverCallPublicPortsOrChooseOne() {
        question=new AssistantQuestion(0,"question-key-0001","zh-CN",1,new AssistantQuestion.PrivateGraph(GraphQueryType.FIND_CONTACT_BY_ALIAS,null,"测试"));
        bind(Purpose.CONTACT_GRAPH);
        var candidates=List.of(new ContactDisplay(id(20),1,List.of("测试")),new ContactDisplay(id(21),1,List.of("测试")));
        when(graph.query(any())).thenReturn(new GraphQueryPort.Result("a".repeat(64),candidates,true));
        service.ask(owner,sid,question);
        var capture=ArgumentCaptor.forClass(AssistantStoredResult.class);
        verify(protection).encrypt(any(),any(),capture.capture());
        assertThat(capture.getValue().answer().status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(capture.getValue().answer().candidates()).containsExactlyElementsOf(candidates);
        assertThat(capture.getValue().externalProcessing()).isFalse();
        verifyNoInteractions(knowledge,profile);
        verify(reader,never()).publicQueryHistory(any(),any(),anyLong(),anyString(),anyInt(),anyBoolean());
    }
    @Test void privateStorageFailureIsUnavailableNotEmptyContacts() {
        question=new AssistantQuestion(0,"question-key-0001","zh-CN",1,new AssistantQuestion.PrivateGraph(GraphQueryType.LIST_CONTACTS,null,null));
        bind(Purpose.CONTACT_GRAPH);
        when(graph.query(any())).thenThrow(new GraphSourceException(GraphSourceException.Kind.STORAGE_UNAVAILABLE));
        service.ask(owner,sid,question);
        verify(protection).encrypt(any(),any(),argThat(p->p.answer().status()==Status.UNAVAILABLE && p.graphSourceDigest()==null));
        verify(requests).complete(any(),any(),any(),anyLong(),any(),any(),eq(Optional.empty()));
        verifyNoInteractions(knowledge,profile);
    }
    @Test void blankInputProducesClarificationWithoutExternalProcessing() {
        question=new AssistantQuestion(0,"question-key-0001","zh-CN",1,new AssistantQuestion.PublicText("  "));
        bind(Purpose.PUBLIC_KNOWLEDGE);
        service.ask(owner,sid,question);
        verify(protection).encrypt(any(),any(),argThat(p->p.answer().reason()==AssistantReason.EMPTY_INPUT && !p.externalProcessing()));
        verifyNoInteractions(knowledge,profile,graph);
    }
    @Test void cancellationDoesNotStartOrConvertToBusinessFallback() {
        when(knowledge.answerWithEvidence(any())).thenThrow(new CancellationException("fixture"));
        assertThatThrownBy(()->service.ask(owner,sid,question)).isInstanceOf(CancellationException.class);
        verifyNoInteractions(protection);
        clearInvocations(requests);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(()->service.ask(owner,sid,question)).isInstanceOf(CancellationException.class); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(requests);
    }
    @Test void localConfigurationNeverFetchesModelProfile() {
        service=new AssistantSessionService(requests,reader,protection,revalidator,knowledge,graph,profile,clock,false);
        service.ask(owner,sid,question);
        verify(reader).publicQueryHistory(owner,sid,1,"zh-CN",1,false);
        verify(knowledge).answerWithEvidence(argThat(r->!r.external() && r.generationProfile().isEmpty()));
        verifyNoInteractions(profile);
    }
    private final class MutableClock extends Clock {
        Instant now=start;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
