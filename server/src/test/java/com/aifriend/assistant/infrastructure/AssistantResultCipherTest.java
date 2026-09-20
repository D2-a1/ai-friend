package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.assistant.infrastructure.AssistantResultCipher.Binding;
import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.retrieval.domain.*;
import com.aifriend.shared.security.*;

/** 真实AES/严格JSON，全部密钥和文本合成；不证明当前来源或模型同意仍然有效。 */
class AssistantResultCipherTest {
    private static UUID id(int n) { return new UUID(0,n); }
    private static SensitiveDataProtector crypto(int seed) {
        byte[] key=new byte[32]; key[0]=(byte)seed;
        return new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(key,"HmacSHA256"),
                new SecretKeySpec(key,"AES"),new SecretKeySpec(key,"HmacSHA256")));
    }
    private static Binding binding(Purpose purpose) {
        return new Binding(id(1),id(2),id(3),id(4),purpose,"policy-v1",2,"a".repeat(64),1_800_000_000_000L);
    }
    static AssistantStoredResult publicProof() {
        var evidence=new RetrievalEvidence(new KnowledgeChunk(id(10),id(11),1,0,"","说明",0,2,"v1"),1);
        var answer=new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.EVIDENCE_ONLY,Mode.EXTRACTIVE,AssistantReason.NONE,
                "说明",List.of(evidence),List.of());
        return new AssistantStoredResult(answer,new IndexVersion(id(12),1,Optional.empty(),"v1","v1"),
                RetrievalResult.Mode.KEYWORD_ONLY,null,false,null,"zh-CN",1);
    }
    private static AssistantStoredResult graphProof() {
        return new AssistantStoredResult(new AssistantAnswer(Purpose.CONTACT_GRAPH,Status.ANSWERED,Mode.TEMPLATE,AssistantReason.NONE,
                "找到一位亲友",List.of(),List.of(new ContactDisplay(id(5),1,List.of("虚构称呼")))),null,null,"b".repeat(64),false,null,"zh-CN",1);
    }
    @Test void publicAndGraphRoundTripAllEvidenceWithIndependentRandomIv() {
        var codec=new AssistantResultCipher(crypto(1));
        for(var result:List.of(publicProof(),graphProof())) {
            var scope=binding(result.answer().purpose()); byte[] a=codec.encrypt(scope,result),b=codec.encrypt(scope,result);
            assertThat(a).isNotEqualTo(b); assertThat(codec.decrypt(scope,a)).isEqualTo(result); assertThat(codec.decrypt(scope,b)).isEqualTo(result);
        }
    }
    @Test void allBindingFieldsAreAuthenticatedIncludingOriginalExpiryAndRequestDigest() {
        var codec=new AssistantResultCipher(crypto(1)); var b=binding(Purpose.PUBLIC_KNOWLEDGE); byte[] encrypted=codec.encrypt(b,publicProof());
        for(var wrong:List.of(new Binding(id(9),b.session(),b.request(),b.token(),b.purpose(),b.policy(),2,b.requestDigest(),b.expiresAtMillis()),
                new Binding(b.owner(),id(9),b.request(),b.token(),b.purpose(),b.policy(),2,b.requestDigest(),b.expiresAtMillis()),
                new Binding(b.owner(),b.session(),id(9),b.token(),b.purpose(),b.policy(),2,b.requestDigest(),b.expiresAtMillis()),
                new Binding(b.owner(),b.session(),b.request(),id(9),b.purpose(),b.policy(),2,b.requestDigest(),b.expiresAtMillis()),
                binding(Purpose.CONTACT_GRAPH),
                new Binding(b.owner(),b.session(),b.request(),b.token(),b.purpose(),"policy-v2",2,b.requestDigest(),b.expiresAtMillis()),
                new Binding(b.owner(),b.session(),b.request(),b.token(),b.purpose(),b.policy(),3,b.requestDigest(),b.expiresAtMillis()),
                new Binding(b.owner(),b.session(),b.request(),b.token(),b.purpose(),b.policy(),2,"c".repeat(64),b.expiresAtMillis()),
                new Binding(b.owner(),b.session(),b.request(),b.token(),b.purpose(),b.policy(),2,b.requestDigest(),b.expiresAtMillis()+1))) {
            assertThatThrownBy(()->codec.decrypt(wrong,encrypted)).hasMessage("DECRYPTION_FAILED").hasNoCause();
        }
    }
    @Test void tamperWrongKeyAndContextEnvelopeCannotBeUsedAsResult() {
        var protector=crypto(1); var codec=new AssistantResultCipher(protector); var b=binding(Purpose.PUBLIC_KNOWLEDGE);
        byte[] encrypted=codec.encrypt(b,publicProof());
        assertThatThrownBy(()->new AssistantResultCipher(crypto(2)).decrypt(b,encrypted)).hasMessage("DECRYPTION_FAILED");
        encrypted[encrypted.length-1]^=1;
        assertThatThrownBy(()->codec.decrypt(b,encrypted)).hasMessage("DECRYPTION_FAILED");
        var context=new AssistantConversation(Purpose.PUBLIC_KNOWLEDGE,List.of());
        byte[] other=new AssistantContextCipher(protector).encrypt(new AssistantContextCipher.Binding(id(1),id(2),Purpose.PUBLIC_KNOWLEDGE,"policy-v1",2),context);
        assertThatThrownBy(()->codec.decrypt(b,other)).hasMessage("DECRYPTION_FAILED");
    }
    @Test void authenticatedButMalformedJsonCannotBypassStrictDecoder() {
        var protector=crypto(1); var codec=new AssistantResultCipher(protector); var b=binding(Purpose.PUBLIC_KNOWLEDGE);
        String json=protector.decrypt(codec.encrypt(b,publicProof()));
        for(String invalid:List.of(json.replace("\"resultVersion\":2","\"resultVersion\":2,\"resultVersion\":2"),
                json.replace("\"resultVersion\":2","\"resultVersion\":2.0"),
                json.replace("\"resultVersion\":2","\"resultVersion\":\"2\""),
                json.replace("\"externalProcessing\":false,",""),
                json.replace("\"externalProcessing\":false","\"externalProcessing\":null"),
                json.replace("\"appVersionCode\":1","\"appVersionCode\":1,\"unknown\":true"),json+" {}")) {
            assertThatThrownBy(()->codec.decrypt(b,protector.encrypt(invalid))).hasMessage("DECRYPTION_FAILED").hasNoCause();
        }
    }
    @Test void fullPrivateUnicodeCandidateBudgetFitsMediumBlobWithoutTruncation() {
        var displays=new ArrayList<ContactDisplay>();
        for(int i=0;i<20;i++) displays.add(new ContactDisplay(id(100+i),1,Collections.nCopies(5,"😀".repeat(100))));
        var proof=new AssistantStoredResult(new AssistantAnswer(Purpose.CONTACT_GRAPH,Status.ANSWERED,Mode.TEMPLATE,AssistantReason.NONE,
                "😀".repeat(3000),List.of(),displays),null,null,"a".repeat(64),false,null,"zh-CN",1);
        var codec=new AssistantResultCipher(crypto(1)); byte[] encrypted=codec.encrypt(binding(Purpose.CONTACT_GRAPH),proof);
        assertThat(encrypted.length).isGreaterThan(65535).isLessThanOrEqualTo(AssistantTurnRequest.MAX_RESULT_BYTES);
        assertThat(codec.decrypt(binding(Purpose.CONTACT_GRAPH),encrypted)).isEqualTo(proof);
    }
    @Test void proofsCannotOmitSourcesMixPrivateWithModelOrPersistProcessingAsCompleted() {
        var publicResult=publicProof(); var graph=graphProof();
        assertThatThrownBy(()->new AssistantStoredResult(publicResult.answer(),null,null,null,false,null,"zh-CN",1)).hasMessage("INVALID_KNOWLEDGE_PROOF");
        assertThatThrownBy(()->new AssistantStoredResult(graph.answer(),null,null,null,false,null,"zh-CN",1)).hasMessage("INVALID_GRAPH_PROOF");
        assertThatThrownBy(()->new AssistantStoredResult(graph.answer(),null,null,graph.graphSourceDigest(),true,"profile-v1","zh-CN",1)).hasMessage("INVALID_GRAPH_PROOF");
        var processing=new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.PROCESSING,Mode.NONE,AssistantReason.NONE,"",List.of(),List.of());
        assertThatThrownBy(()->new AssistantStoredResult(processing,null,null,null,false,null,"zh-CN",1)).hasMessage("INVALID_STORED_RESULT");
        assertThatThrownBy(()->new AssistantResultCipher(crypto(1)).encrypt(binding(Purpose.PUBLIC_KNOWLEDGE),graph)).hasMessage("RESULT_BINDING_MISMATCH");
    }
    @Test void excessiveCipherRejectedBeforeCryptoAndBorrowedBufferIsNeverCleared() {
        var mocked=mock(SensitiveDataProtector.class); var codec=new AssistantResultCipher(mocked); var b=binding(Purpose.PUBLIC_KNOWLEDGE);
        assertThatThrownBy(()->codec.decrypt(b,new byte[AssistantTurnRequest.MAX_RESULT_BYTES+1])).hasMessage("DECRYPTION_FAILED"); verifyNoInteractions(mocked);
        var real=crypto(1); byte[] borrowed=new AssistantResultCipher(real).encrypt(b,publicProof()), original=borrowed.clone();
        var copied=new AtomicReference<byte[]>(); var plain=new AtomicReference<byte[]>();
        when(mocked.decryptBytes(any())).thenAnswer(c->{byte[] input=c.getArgument(0); copied.set(input); byte[] value=real.decryptBytes(input); plain.set(value); return value;});
        assertThat(codec.decrypt(b,borrowed)).isEqualTo(publicProof()); assertThat(borrowed).isEqualTo(original);
        assertThat(copied.get()).containsOnly((byte)0); assertThat(plain.get()).containsOnly((byte)0);
    }
    @Test void encryptionFailureClearsOwnedPlaintextWithoutReturningPrivateException() {
        var protector=mock(SensitiveDataProtector.class); var plain=new AtomicReference<byte[]>();
        when(protector.encryptBytes(any())).thenAnswer(c->{plain.set(c.getArgument(0)); throw new IllegalStateException("PRIVATE_VALUE");});
        assertThatThrownBy(()->new AssistantResultCipher(protector).encrypt(binding(Purpose.PUBLIC_KNOWLEDGE),publicProof()))
                .hasMessage("STORAGE_UNAVAILABLE").hasNoCause(); assertThat(plain.get()).containsOnly((byte)0);
    }
    @Test void storedProofRejectsRepeatedEvidenceMixedChunkerAndHybridWithoutVectorProfile() {
        var proof=publicProof(); var citation=proof.answer().citations().get(0);
        var repeated=new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.EVIDENCE_ONLY,Mode.EXTRACTIVE,AssistantReason.NONE,
                "说明",List.of(citation,citation),List.of());
        assertThatThrownBy(()->new AssistantStoredResult(repeated,proof.evidenceVersion(),proof.retrievalMode(),null,false,null,"zh-CN",1))
                .hasMessage("MIXED_RETRIEVAL_EVIDENCE");
        var mixed=new IndexVersion(id(12),1,Optional.empty(),"v1","v2");
        assertThatThrownBy(()->new AssistantStoredResult(proof.answer(),mixed,proof.retrievalMode(),null,false,null,"zh-CN",1))
                .hasMessage("MIXED_RETRIEVAL_EVIDENCE");
        assertThatThrownBy(()->new AssistantStoredResult(proof.answer(),proof.evidenceVersion(),RetrievalResult.Mode.HYBRID,null,true,null,"zh-CN",1))
                .hasMessage("INVALID_RETRIEVAL_RESULT");
    }
    @Test void repeatedPrivateCandidateCannotBePersistedAsMultiplePeople() {
        var proof=graphProof(); var person=proof.answer().candidates().get(0);
        var duplicate=new AssistantAnswer(Purpose.CONTACT_GRAPH,Status.ANSWERED,Mode.TEMPLATE,AssistantReason.NONE,
                "两位亲友",List.of(),List.of(person,person));
        assertThatThrownBy(()->new AssistantStoredResult(duplicate,null,null,proof.graphSourceDigest(),false,null,"zh-CN",1))
                .hasMessage("DUPLICATE_GRAPH_CANDIDATE");
    }
}
