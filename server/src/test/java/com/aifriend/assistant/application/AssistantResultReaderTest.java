package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.assistant.application.AssistantTurnRepository.Receipt;
import com.aifriend.assistant.infrastructure.AssistantResultCipher;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.application.ContactGraphSourcePort;
import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.*;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.*;

/** 实际AES信封、实际复验器和读取应用服务；会话账本/知识权威源模拟。 */
class AssistantResultReaderTest {
    private final UUID owner=id(1),sid=id(2),rid=id(3),token=id(4);
    private final Instant now=Instant.parse("2026-09-10T10:00:00Z");
    private final AssistantSessionRepository sessions=mock(AssistantSessionRepository.class);
    private final AssistantTurnRepository requests=mock(AssistantTurnRepository.class);
    private final KnowledgeRepositoryPort knowledge=mock(KnowledgeRepositoryPort.class);
    private final ContactGraphSourcePort graph=mock(ContactGraphSourcePort.class);
    private final ConsentGrantQueryPort consents=mock(ConsentGrantQueryPort.class);
    private final KnowledgeAccessPolicy access=new KnowledgeAccessPolicy(consents);
    private final AssistantResultRevalidator validator=new AssistantResultRevalidator(knowledge,graph,access,()->Optional.of("v1"));
    private AssistantResultCipher cipher;
    private AssistantResultReader reader;
    private AssistantStoredResult proof;
    private Receipt stored;
    private final IndexVersion version=new IndexVersion(id(10),1,Optional.empty(),"v1","v1");
    private final KnowledgeChunk chunk=new KnowledgeChunk(id(11),id(12),1,0,"","说明",0,2,"v1");
    private static UUID id(int n) { return new UUID(0,n); }
    @BeforeEach void setup() {
        byte[] key=new byte[32]; key[0]=11;
        cipher=spy(new AssistantResultCipher(new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(key,"HmacSHA256"),
                new SecretKeySpec(key,"AES"),new SecretKeySpec(key,"HmacSHA256")))));
        reader=new AssistantResultReader(sessions,requests,cipher,validator,access);
        proof=new AssistantStoredResult(new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.EVIDENCE_ONLY,Mode.EXTRACTIVE,
                AssistantReason.NONE,"说明",List.of(new RetrievalEvidence(chunk,1)),List.of()),version,RetrievalResult.Mode.KEYWORD_ONLY,null,false,null,"zh-CN",1);
        stored=store(proof,"说明"); bind(stored);
        when(knowledge.readActive()).thenReturn(Optional.of(new KnowledgeRepositoryPort.Snapshot(version,
                List.of(new KnowledgeDocument(id(12),"guide",1,"测试指南","zh-CN",1,2,"说明")),List.of(chunk))));
        when(knowledge.isCurrent(version,List.of(chunk))).thenReturn(true);
        when(consents.isGrantedForPolicy(any(),any(),anyString())).thenReturn(true);
        clearInvocations(cipher);
    }
    private Receipt store(AssistantStoredResult result,String summary) {
        var initial=AssistantSession.create(sid,owner,result.answer().purpose(),"policy-v1",now);
        var pending=initial.begin(0,rid,token,now);
        var request=new AssistantTurnRequest(rid,owner,sid,result.answer().purpose(),"a".repeat(64),"b".repeat(64),1,token,
                now,now.plusSeconds(8),now.plusSeconds(300),AssistantTurnRequest.State.PROCESSING,null,null);
        byte[] encrypted=cipher.encrypt(pending,request,result);
        try {
            return new Receipt(pending.complete(1,token,Optional.of(new AssistantConversation.Turn(rid,2,"问题",summary)),now.plusSeconds(1)),
                    request.complete(token,1,encrypted,now.plusSeconds(1)),false);
        } finally { Arrays.fill(encrypted,(byte)0); }
    }
    private void bind(Receipt receipt) {
        when(requests.findRequest(owner,sid,"original-key")).thenReturn(Optional.of(receipt));
        when(requests.findRequestById(owner,sid,rid)).thenReturn(Optional.of(receipt));
        when(sessions.find(owner,sid)).thenReturn(Optional.of(receipt.session()));
    }
    @Test void replayAndHistoryUseActualCipherAndSourceWithoutNewAdmission() {
        assertThat(reader.read(owner,sid,"original-key").orElseThrow().answer()).contains(proof.answer());
        assertThat(reader.read(owner,sid,"original-key").orElseThrow().retrievalMode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        assertThat(reader.history(owner,sid,2)).isEqualTo(stored.session().conversation());
        assertThat(reader.publicModelHistory(owner,sid,2).turns()).hasSize(1);
        verify(requests,never()).admit(any(),any(),any());
        verify(requests,never()).complete(any(),any(),any(),anyLong(),any(),any(),any());
        verifyNoInteractions(graph);
    }
    @Test void deletedEvidenceBlocksReplayAndWholeHistory() {
        when(knowledge.isCurrent(version,List.of(chunk))).thenReturn(false);
        assertThatThrownBy(()->reader.read(owner,sid,"original-key")).hasMessage("EVIDENCE_INVALIDATED");
        assertThatThrownBy(()->reader.history(owner,sid,2)).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void closeOrInvalidationWhileSourceIsReadBlocksAnswer() {
        when(knowledge.isCurrent(version,List.of(chunk))).thenAnswer(c->{
            when(requests.findRequestById(owner,sid,rid)).thenReturn(Optional.of(new Receipt(stored.session(),
                    stored.request().invalidate(true,now.plusSeconds(2)),false))); return true;
        });
        assertThatThrownBy(()->reader.read(owner,sid,"original-key")).hasMessage("RESULT_STALE");
    }
    @Test void sessionVersionChangedDuringValidationCannotReturnOldHistory() {
        var advanced=stored.session().begin(2,id(30),id(31),now.plusSeconds(2));
        when(sessions.find(owner,sid)).thenReturn(Optional.of(stored.session()),Optional.of(advanced));
        assertThatThrownBy(()->reader.history(owner,sid,2)).hasMessage("RESULT_STALE");
    }
    @Test void databaseExpiryAtFinalReadIsNotIgnored() {
        when(requests.findRequestById(owner,sid,rid)).thenThrow(new AssistantSessionException(AssistantReason.SESSION_EXPIRED));
        assertThatThrownBy(()->reader.read(owner,sid,"original-key")).hasMessage("SESSION_EXPIRED");
    }
    @Test void brokenHistoryReferenceAndChangedSummaryAreRejected() {
        when(requests.findRequestById(owner,sid,rid)).thenReturn(Optional.empty());
        assertThatThrownBy(()->reader.history(owner,sid,2)).hasMessage("RESULT_STALE");
        stored=store(proof,"伪造的摘要"); bind(stored);
        assertThatThrownBy(()->reader.history(owner,sid,2)).hasMessage("EVIDENCE_INVALIDATED");
    }
    @Test void privateSessionCannotBePreparedForExternalModelEvenIfConsented() {
        var privateSession=AssistantSession.create(sid,owner,Purpose.CONTACT_GRAPH,"contact-graph-v1",now);
        when(sessions.find(owner,sid)).thenReturn(Optional.of(privateSession));
        assertThatThrownBy(()->reader.publicModelHistory(owner,sid,0)).hasMessage("INVALID_REQUEST");
        verifyNoInteractions(requests,knowledge,graph,cipher,consents);
    }
    @Test void localEvidenceHistoryNeedsSeparateConsentBeforeExternalReuse() {
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(false);
        assertThat(reader.history(owner,sid,2).turns()).hasSize(1);
        clearInvocations(requests,cipher);
        assertThatThrownBy(()->reader.publicModelHistory(owner,sid,2)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(requests,cipher);
    }
    @Test void externalConsentWithdrawnDuringFinalLedgerReadBlocksReplay() {
        var external=new AssistantStoredResult(proof.answer(),version,proof.retrievalMode(),null,true,"v1","zh-CN",1);
        stored=store(external,"说明"); bind(stored);
        when(requests.findRequestById(owner,sid,rid)).thenAnswer(c->{
            when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(false);
            return Optional.of(stored);
        });
        assertThatThrownBy(()->reader.read(owner,sid,"original-key")).isInstanceOf(BusinessException.class);
    }
    @Test void processingAndExpiredRequestNeverDecryptOrReadEvidence() {
        var initial=AssistantSession.create(sid,owner,Purpose.PUBLIC_KNOWLEDGE,"policy-v1",now);
        var pending=initial.begin(0,rid,token,now);
        var request=new AssistantTurnRequest(rid,owner,sid,Purpose.PUBLIC_KNOWLEDGE,"a".repeat(64),"b".repeat(64),1,token,
                now,now.plusSeconds(8),now.plusSeconds(300),AssistantTurnRequest.State.PROCESSING,null,null);
        bind(new Receipt(pending,request,false));
        assertThat(reader.read(owner,sid,"original-key").orElseThrow().answer()).isEmpty();
        bind(new Receipt(pending.timeout(1,token,now.plusSeconds(8)),request.expire(now.plusSeconds(8)),false));
        assertThat(reader.read(owner,sid,"original-key").orElseThrow().state()).isEqualTo(AssistantTurnRequest.State.EXPIRED);
        verifyNoInteractions(cipher,knowledge,graph);
    }
    @Test void decryptFailureCleansOwnedCipherAndPreservesStoredBytes() {
        byte[][] captured={null}; byte[] original=stored.request().encryptedResult();
        doAnswer(c->{ captured[0]=c.getArgument(2); throw new AssistantSessionException(AssistantReason.DECRYPTION_FAILED); })
                .when(cipher).decrypt(any(AssistantSession.class),any(AssistantTurnRequest.class),any(byte[].class));
        assertThatThrownBy(()->reader.read(owner,sid,"original-key")).hasMessage("DECRYPTION_FAILED");
        assertThat(captured[0]).containsOnly((byte)0);
        assertThat(stored.request().encryptedResult()).isEqualTo(original);
        verifyNoInteractions(knowledge);
    }
    @Test void wrongOwnerVersionAndCancellationStopBeforeDecryption() {
        when(sessions.find(id(99),sid)).thenReturn(Optional.of(stored.session()));
        assertThatThrownBy(()->reader.history(id(99),sid,2)).hasMessage("RESULT_STALE");
        assertThatThrownBy(()->reader.history(owner,sid,1)).hasMessage("RESULT_STALE");
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(()->reader.read(owner,sid,"original-key")).isInstanceOf(CancellationException.class); }
        finally { Thread.interrupted(); }
        verifyNoInteractions(cipher,knowledge,requests);
    }
    @Test void summaryUsesOriginalUnicodeCodePointsNotModelRewrite() {
        String text="😀".repeat(361);
        var answer=new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NEEDS_CLARIFICATION,Mode.NONE,AssistantReason.MISSING_CONTEXT,
                text,List.of(),List.of());
        assertThat(AssistantResultReader.summary(answer)).isEqualTo("😀".repeat(360));
    }

    @Test void queryHistoryRequiresSameLocaleAndAppVersionWithoutSkippingSourceChecks() {
        assertThat(reader.publicQueryHistory(owner,sid,2,"zh-CN",1,false)).isEqualTo(stored.session().conversation());
        assertThat(reader.publicQueryHistory(owner,sid,2,"en",1,false).turns()).isEmpty();
        assertThat(reader.publicQueryHistory(owner,sid,2,"zh-CN",2,true).turns()).isEmpty();
        when(knowledge.isCurrent(version,List.of(chunk))).thenReturn(false);
        assertThatThrownBy(()->reader.publicQueryHistory(owner,sid,2,"en",2,false)).hasMessage("EVIDENCE_INVALIDATED");
    }

    @Test void latestUnansweredQuestionCannotSupplyAnAssumedTopic() {
        var unanswered=new AssistantStoredResult(new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NO_EVIDENCE,
                Mode.NONE,AssistantReason.NO_SUPPORT,"没有相关说明",List.of(),List.of()),null,null,null,false,null,"zh-CN",1);
        stored=store(unanswered,"没有相关说明"); bind(stored);
        when(knowledge.readActive()).thenReturn(Optional.empty());
        assertThat(reader.history(owner,sid,2).turns()).hasSize(1);
        assertThat(reader.publicQueryHistory(owner,sid,2,"zh-CN",1,false).turns()).isEmpty();
    }

    @Test void queryHistoryRejectsPrivatePurposeBeforeDecryptEvenWithoutExternalModel() {
        when(sessions.find(owner,sid)).thenReturn(Optional.of(AssistantSession.create(sid,owner,Purpose.CONTACT_GRAPH,"contact-graph-v1",now)));
        assertThatThrownBy(()->reader.publicQueryHistory(owner,sid,0,"zh-CN",1,false)).hasMessage("INVALID_REQUEST");
        verifyNoInteractions(requests,knowledge,graph,cipher,consents);
    }

    @Test void scopedHistoryStillChecksConsentAndAuthoritativeExpiry() {
        when(consents.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).thenReturn(false);
        assertThatThrownBy(()->reader.publicQueryHistory(owner,sid,2,"en",2,true)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(requests,cipher);
        when(requests.findRequestById(owner,sid,rid)).thenThrow(new AssistantSessionException(AssistantReason.SESSION_EXPIRED));
        assertThatThrownBy(()->reader.publicQueryHistory(owner,sid,2,"zh-CN",1,false)).hasMessage("SESSION_EXPIRED");
    }
}
