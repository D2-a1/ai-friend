package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.application.ContactGraphSourcePort;
import com.aifriend.knowledge.domain.*;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.*;
import com.aifriend.shared.error.BusinessException;

/** 合成来源/模拟授权，验证已保存结果不可凭旧证明直接重放；不访问数据库或模型。 */
class AssistantResultRevalidatorTest {
    private final UUID owner=id(1);
    private final KnowledgeRepositoryPort knowledge=mock(KnowledgeRepositoryPort.class);
    private final ContactGraphSourcePort graph=mock(ContactGraphSourcePort.class);
    private final ConsentGrantQueryPort consents=mock(ConsentGrantQueryPort.class);
    @SuppressWarnings("unchecked") private final Supplier<Optional<String>> profile=mock(Supplier.class);
    private final AssistantResultRevalidator service=new AssistantResultRevalidator(knowledge,graph,new KnowledgeAccessPolicy(consents),profile);
    private final KnowledgeChunk chunk=new KnowledgeChunk(id(10),id(11),1,0,"","说明",0,2,"v1");
    private final IndexVersion version=new IndexVersion(id(12),1,Optional.empty(),"v1","v1");
    private final ContactDisplay display=new ContactDisplay(id(20),1,List.of("测试亲友"));
    private static UUID id(int n) { return new UUID(0,n); }
    @BeforeEach void setup() {
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(true);
        when(consents.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).thenReturn(true);
        when(profile.get()).thenReturn(Optional.of("model-v1"));
        when(knowledge.readActive()).thenReturn(Optional.of(snapshot("zh-CN",1,3,"说明")));
        when(knowledge.isCurrent(eq(version),anyList())).thenReturn(true);
        when(graph.snapshot(owner)).thenReturn(graphSnapshot(owner,1));
        when(graph.displayCurrent(owner,"a".repeat(64),List.of(id(20)))).thenReturn(List.of(display));
    }
    private KnowledgeRepositoryPort.Snapshot snapshot(String locale,int min,int max,String text) {
        return new KnowledgeRepositoryPort.Snapshot(version,List.of(new KnowledgeDocument(id(11),"guide",1,"测试",locale,min,max,text)),
                List.of(new KnowledgeChunk(id(10),id(11),1,0,"",text,0,text.length(),"v1")));
    }
    private AssistantStoredResult publicResult(boolean external) {
        return new AssistantStoredResult(new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.EVIDENCE_ONLY,Mode.EXTRACTIVE,
                AssistantReason.NONE,"说明",List.of(new RetrievalEvidence(chunk,1)),List.of()),version,RetrievalResult.Mode.KEYWORD_ONLY,
                null,external,external?"model-v1":null,"zh-CN",1);
    }
    private AssistantStoredResult graphResult() {
        return new AssistantStoredResult(new AssistantAnswer(Purpose.CONTACT_GRAPH,Status.ANSWERED,Mode.TEMPLATE,AssistantReason.NONE,
                "找到亲友",List.of(),List.of(display)),null,null,"a".repeat(64),false,null,"zh-CN",1);
    }
    private AssistantStoredResult empty(Purpose purpose,String digest) {
        return new AssistantStoredResult(new AssistantAnswer(purpose,Status.NO_EVIDENCE,Mode.NONE,AssistantReason.NO_SUPPORT,
                "没有结果",List.of(),List.of()),null,null,digest,false,null,"zh-CN",1);
    }
    private GraphSnapshot graphSnapshot(UUID user,long contactVersion) {
        var gen=id(5);
        return new GraphSnapshot(user,gen,"a".repeat(64),List.of(
                new GraphNode(user,gen,id(6),GraphNode.Type.USER,user,1),
                new GraphNode(user,gen,id(7),GraphNode.Type.CONTACT,id(20),contactVersion),
                new GraphNode(user,gen,id(8),GraphNode.Type.ALIAS,id(30),1)),List.of(
                new GraphEdge(user,gen,id(9),id(6),id(7),GraphEdge.Type.HAS_CONTACT),
                new GraphEdge(user,gen,id(10),id(7),id(8),GraphEdge.Type.HAS_ALIAS)));
    }
    @Test void localEvidenceNeedsNoExternalConsentOrProfile() {
        service.requireCurrent(owner,publicResult(false));
        verifyNoInteractions(consents,profile,graph);
        verify(knowledge).isCurrent(version,List.of(chunk));
    }
    @Test void scopedKnowledgeCapabilityChecksEvidenceAndStillRechecksConsent() {
        var scoped=mock(com.aifriend.retrieval.application.KnowledgeEvidencePort.class);
        var fast=new AssistantResultRevalidator(scoped,graph,new KnowledgeAccessPolicy(consents),profile);
        when(scoped.isCurrentForScope(version,List.of(chunk),"zh-CN",1)).thenReturn(true);
        fast.requireCurrent(owner,publicResult(false));
        verify(scoped).isCurrentForScope(version,List.of(chunk),"zh-CN",1);
        verifyNoMoreInteractions(scoped);
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1"))
                .thenReturn(true,false);
        assertThatThrownBy(()->fast.requireCurrent(owner,publicResult(true))).isInstanceOf(BusinessException.class);
        when(scoped.isCurrentForScope(version,List.of(chunk),"zh-CN",1)).thenReturn(false);
        assertThatThrownBy(()->fast.requireCurrent(owner,publicResult(false))).hasMessage("EVIDENCE_INVALIDATED");
    }

    @Test void actualKnowledgePortFailuresKeepFiniteReasonInsteadOfGenericServerError() {
        for(var kind:com.aifriend.retrieval.application.KnowledgeRetrievalException.Kind.values()) {
            doThrow(new com.aifriend.retrieval.application.KnowledgeRetrievalException(kind)).when(knowledge).readActive();
            String expected=kind==com.aifriend.retrieval.application.KnowledgeRetrievalException.Kind.INDEX_INVALID?"INDEX_INVALID":"EVIDENCE_INVALIDATED";
            assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false)))
                    .isInstanceOf(AssistantSessionException.class).hasMessage(expected);
        }
    }
    @Test void actualGraphPortFailuresKeepFiniteReasonWithoutLeakingOldCandidates() {
        for(var kind:com.aifriend.knowledge.application.GraphSourceException.Kind.values()) {
            doThrow(new com.aifriend.knowledge.application.GraphSourceException(kind)).when(graph).snapshot(owner);
            String expected=kind==com.aifriend.knowledge.application.GraphSourceException.Kind.SOURCE_CHANGED?"EVIDENCE_INVALIDATED":kind.name();
            assertThatThrownBy(()->service.requireCurrent(owner,graphResult()))
                    .isInstanceOf(AssistantSessionException.class).hasMessage(expected);
        }
    }
    @Test void removedIndexCannotReplayEvidence() {
        when(knowledge.readActive()).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false))).hasMessage("EVIDENCE_INVALIDATED");
        verify(knowledge,never()).isCurrent(any(),anyList());
    }
    @Test void sameChunkIdButChangedTextCannotReplay() {
        when(knowledge.readActive()).thenReturn(Optional.of(snapshot("zh-CN",1,3,"新版")));
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false))).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void localeAndBothApplicationBoundariesAreRechecked() {
        for(var doc:List.of(snapshot("en-US",1,3,"说明"),snapshot("zh-CN",2,3,"说明"))) {
            when(knowledge.readActive()).thenReturn(Optional.of(doc));
            assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false))).hasMessage("EVIDENCE_INVALIDATED");
        }
        var result=publicResult(false);
        var tooNew=new AssistantStoredResult(result.answer(),version,result.retrievalMode(),null,false,null,"zh-CN",4);
        when(knowledge.readActive()).thenReturn(Optional.of(snapshot("zh-CN",1,3,"说明")));
        assertThatThrownBy(()->service.requireCurrent(owner,tooNew)).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void deletionDuringValidationFailsFinalFreshRead() {
        when(knowledge.isCurrent(eq(version),anyList())).thenReturn(false);
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false))).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void publishedIndexInvalidatesOldNoIndexAnswer() {
        assertThatThrownBy(()->service.requireCurrent(owner,empty(Purpose.PUBLIC_KNOWLEDGE,null))).hasMessage("EVIDENCE_INVALIDATED");
        when(knowledge.readActive()).thenReturn(Optional.empty());
        service.requireCurrent(owner,empty(Purpose.PUBLIC_KNOWLEDGE,null));
    }
    @Test void externalConsentCheckedBeforeReadingKnowledge() {
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(false);
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(true))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(knowledge,profile,graph);
    }
    @Test void consentWithdrawnDuringEvidenceReadBlocksReturn() {
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(true,false);
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(true))).isInstanceOf(BusinessException.class);
        verify(knowledge).isCurrent(version,List.of(chunk));
    }
    @Test void profileChangeOrRemovalBlocksOldGeneratedScope() {
        when(profile.get()).thenReturn(Optional.of("model-v1"),Optional.of("model-v2"));
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(true))).hasMessage("PROFILE_CHANGED");
        when(profile.get()).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(true))).hasMessage("PROFILE_CHANGED");
    }
    @Test void graphRevalidatesWithoutAnyPublicOrModelPort() {
        service.requireCurrent(owner,graphResult());
        verify(graph,times(2)).snapshot(owner);
        verifyNoInteractions(knowledge,profile);
    }
    @Test void detailedSourceAvoidsRedundantSnapshotsButStillChecksAllEvidence() {
        var detailed=mock(com.aifriend.knowledge.application.ContactGraphEvidencePort.class);
        var fast=new AssistantResultRevalidator(knowledge,detailed,new KnowledgeAccessPolicy(consents),profile);
        var valid=new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(
                graphSnapshot(owner,1),graphSnapshot(owner,1),List.of(display));
        when(detailed.displayEvidence(owner,"a".repeat(64),List.of(id(20)))).thenReturn(valid);
        fast.requireCurrent(owner,graphResult());
        verify(detailed,never()).snapshot(any());
        verify(detailed,never()).displayCurrent(any(),anyString(),anyList());
        var bad=List.of(
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(graphSnapshot(id(99),1),valid.after(),valid.displays()),
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(valid.before(),graphSnapshot(id(99),1),valid.displays()),
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(valid.before(),graphSnapshot(owner,2),valid.displays()),
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(valid.before(),valid.after(),List.of()),
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(valid.before(),valid.after(),List.of(display,display)),
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(valid.before(),valid.after(),List.of(new ContactDisplay(id(20),1,List.of("别的称呼")))));
        for(var evidence:bad) {
            when(detailed.displayEvidence(owner,"a".repeat(64),List.of(id(20)))).thenReturn(evidence);
            assertThatThrownBy(()->fast.requireCurrent(owner,graphResult())).hasMessage("EVIDENCE_INVALIDATED");
        }
        verifyNoInteractions(knowledge,profile);
    }

    @Test void detailedSourceFinalConsentCheckStillPreventsReturn() {
        var detailed=mock(com.aifriend.knowledge.application.ContactGraphEvidencePort.class);
        var fast=new AssistantResultRevalidator(knowledge,detailed,new KnowledgeAccessPolicy(consents),profile);
        when(detailed.displayEvidence(owner,"a".repeat(64),List.of(id(20)))).thenReturn(
                new com.aifriend.knowledge.application.ContactGraphEvidencePort.Evidence(graphSnapshot(owner,1),graphSnapshot(owner,1),List.of(display)));
        when(consents.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).thenReturn(true,false);
        assertThatThrownBy(()->fast.requireCurrent(owner,graphResult())).isInstanceOf(BusinessException.class);
    }

    @Test void graphRevocationPreventsReadingAliases() {
        when(consents.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).thenReturn(false);
        assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(graph,knowledge,profile);
    }
    @Test void graphRevocationAfterDisplayStillBlocksReturn() {
        when(consents.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).thenReturn(true,false);
        assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).isInstanceOf(BusinessException.class);
        verify(graph,times(2)).snapshot(owner);
    }
    @Test void changedAliasOrMissingOrDuplicateCandidateFailsWholeAnswer() {
        for(var displays:List.of(List.of(new ContactDisplay(id(20),1,List.of("新称呼"))),List.<ContactDisplay>of(),List.of(display,display))) {
            when(graph.displayCurrent(owner,"a".repeat(64),List.of(id(20)))).thenReturn(displays);
            assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).hasMessage("EVIDENCE_INVALIDATED");
        }
    }
    @Test void wrongOwnerAndChangedFactsBehindSameDigestAreRejected() {
        when(graph.snapshot(owner)).thenReturn(graphSnapshot(id(99),1));
        assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).hasMessage("EVIDENCE_INVALIDATED");
        when(graph.snapshot(owner)).thenReturn(graphSnapshot(owner,1),graphSnapshot(owner,2));
        assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void noMatchGraphAnswerStillNeedsProofAndFreshSource() {
        assertThatThrownBy(()->service.requireCurrent(owner,empty(Purpose.CONTACT_GRAPH,null))).hasMessage("EVIDENCE_INVALIDATED");
        service.requireCurrent(owner,empty(Purpose.CONTACT_GRAPH,"a".repeat(64)));
        verify(graph).snapshot(owner);
        verify(graph,never()).displayCurrent(any(),anyString(),anyList());
    }
    @Test void emptyGraphStillReadsAgainForEachPhaseAndRejectsChangedSource() {
        var original=graphSnapshot(owner,1);
        var changed=new GraphSnapshot(owner,original.generation(),"b".repeat(64),original.nodes(),original.edges());
        when(graph.snapshot(owner)).thenReturn(original,changed);
        var result=empty(Purpose.CONTACT_GRAPH,"a".repeat(64));
        service.requireCurrent(owner,result);
        assertThatThrownBy(()->service.requireCurrent(owner,result)).hasMessage("EVIDENCE_INVALIDATED");
        verify(graph,times(2)).snapshot(owner);
        verify(graph,never()).displayCurrent(any(),anyString(),anyList());
    }
    @Test void emptyGraphRevokedDuringFreshReadCannotReturn() {
        when(consents.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).thenReturn(true,false);
        assertThatThrownBy(()->service.requireCurrent(owner,empty(Purpose.CONTACT_GRAPH,"a".repeat(64))))
                .isInstanceOf(BusinessException.class);
        verify(graph).snapshot(owner);
    }
    @Test void infrastructureErrorIsNotMistakenForEmptyKnowledge() {
        when(knowledge.readActive()).thenThrow(new IllegalStateException("simulated-storage-failure"));
        assertThatThrownBy(()->service.requireCurrent(owner,publicResult(false))).isInstanceOf(IllegalStateException.class);
        verify(knowledge,never()).isCurrent(any(),anyList());
    }
    @Test void cancellationDoesNotContinueReadingOrClearInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(()->service.requireCurrent(owner,graphResult())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(consents,knowledge,graph,profile);
        } finally { Thread.interrupted(); }
    }
}
