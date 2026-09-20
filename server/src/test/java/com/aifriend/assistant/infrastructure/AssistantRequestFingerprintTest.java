package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantQuestion;
import com.aifriend.assistant.domain.AssistantQuestion.PrivateGraph;
import com.aifriend.assistant.domain.AssistantQuestion.PublicText;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

/** 合成数据HMAC，不读取生产密钥、不连接数据库。 */
class AssistantRequestFingerprintTest {
    private static final UUID OWNER = new UUID(0,1), SESSION = new UUID(0,2);
    private static final String KEY = "request-key-00001";
    private static AssistantRequestFingerprint codec(int seed) {
        byte[] key = new byte[32]; key[0] = (byte) seed;
        return new AssistantRequestFingerprint(new SensitiveDataProtector(new SecurityKeyMaterial(
                new SecretKeySpec(key,"HmacSHA256"),new SecretKeySpec(key,"AES"),new SecretKeySpec(key,"HmacSHA256"))));
    }
    private static AssistantQuestion question(AssistantQuestion.Payload payload) {
        return new AssistantQuestion(0,KEY,"zh-CN",1,payload);
    }
    @Test void sameRequestAcrossCodecInstancesProducesSameFingerprint() {
        var input = question(new PublicText("怎样使用小友？"));
        assertThat(codec(1).question(OWNER,SESSION,input)).isEqualTo(codec(1).question(OWNER,SESSION,input));
    }
    @Test void everyBodyFieldChangesDigestButNotLookupKey() {
        var codec = codec(1); var original = codec.question(OWNER,SESSION,question(new PublicText("原始问题")));
        for (var changed : List.of(new AssistantQuestion(1,KEY,"zh-CN",1,new PublicText("原始问题")),
                new AssistantQuestion(0,KEY,"en-US",1,new PublicText("原始问题")),
                new AssistantQuestion(0,KEY,"zh-CN",2,new PublicText("原始问题")),
                question(new PublicText("不同问题")), question(new PublicText("原始问题 ")),
                question(new PrivateGraph(GraphQueryType.LIST_CONTACTS,null,null)))) {
            var fingerprint = codec.question(OWNER,SESSION,changed);
            assertThat(fingerprint.keyHash()).isEqualTo(original.keyHash());
            assertThat(fingerprint.requestDigest()).isNotEqualTo(original.requestDigest());
        }
    }
    @Test void ownerSessionKeyAndSecretAreIsolated() {
        var input = question(new PublicText("公开问题")); var codec = codec(1);
        var original = codec.question(OWNER,SESSION,input);
        for (var other : List.of(codec.question(new UUID(0,3),SESSION,input),
                codec.question(OWNER,new UUID(0,3),input), codec(2).question(OWNER,SESSION,input),
                codec.question(OWNER,SESSION,new AssistantQuestion(0,"request-key-00002","zh-CN",1,input.payload())))) {
            assertThat(other.keyHash()).isNotEqualTo(original.keyHash());
            assertThat(other.requestDigest()).isNotEqualTo(original.requestDigest());
        }
    }
    @Test void graphTypeContactAndAliasAreAllPartOfFingerprint() {
        var codec = codec(1);
        var fingerprints = List.of(new PrivateGraph(GraphQueryType.LIST_CONTACTS,null,null),
                new PrivateGraph(GraphQueryType.LIST_ALIASES,OWNER,null),
                new PrivateGraph(GraphQueryType.LIST_ALIASES,SESSION,null),
                new PrivateGraph(GraphQueryType.FIND_CONTACT_BY_ALIAS,null,"虚构甲"),
                new PrivateGraph(GraphQueryType.FIND_CONTACT_BY_ALIAS,null,"虚构乙"))
                .stream().map(p -> codec.question(OWNER,SESSION,question(p))).toList();
        assertThat(fingerprints.stream().map(AssistantRequestFingerprint.Fingerprint::keyHash).distinct()).hasSize(1);
        assertThat(fingerprints.stream().map(AssistantRequestFingerprint.Fingerprint::requestDigest).distinct()).hasSize(5);
    }
    @Test void creationSameKeyDifferentPurposeConflictsAndQuestionHasDifferentNamespace() {
        var codec = codec(1); var publicCreate = codec.creation(OWNER,KEY,Purpose.PUBLIC_KNOWLEDGE);
        var privateCreate = codec.creation(OWNER,KEY,Purpose.CONTACT_GRAPH);
        assertThat(publicCreate.keyHash()).isEqualTo(privateCreate.keyHash());
        assertThat(publicCreate.requestDigest()).isNotEqualTo(privateCreate.requestDigest());
        assertThat(codec.question(OWNER,SESSION,question(new PublicText(KEY))).keyHash()).isNotEqualTo(publicCreate.keyHash());
    }
    @Test void unicodeKeysAndTextKeepExactOriginalMeaningWithoutNormalization() {
        var codec = codec(1); var a = question(new PublicText("Ａ😀e\u0301")); var b = question(new PublicText("A😀é"));
        assertThat(codec.question(OWNER,SESSION,a).requestDigest()).isNotEqualTo(codec.question(OWNER,SESSION,b).requestDigest());
        assertThat(AssistantQuestion.requireKey("😀".repeat(16))).isEqualTo("😀".repeat(16));
        assertThat(AssistantQuestion.requireKey("😀".repeat(128))).isNotEmpty();
        for (String bad : List.of("a".repeat(15),"a".repeat(129)," ".repeat(16),"a".repeat(15)+"\u0000")) {
            assertThatThrownBy(() -> AssistantQuestion.requireKey(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void payloadUnionRejectsInvalidParameterCombinationsAndLengths() {
        assertThat(question(new PublicText(" "))).isNotNull();
        assertThat(question(new PublicText("😀".repeat(500)))).isNotNull();
        assertThatThrownBy(() -> new PublicText("😀".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicText("")).hasMessage("EMPTY_INPUT");
        assertThatThrownBy(() -> new PrivateGraph(GraphQueryType.LIST_CONTACTS,OWNER,null)).hasMessage("INVALID_GRAPH_QUERY");
        assertThatThrownBy(() -> new PrivateGraph(GraphQueryType.LIST_ALIASES,null,null)).hasMessage("INVALID_GRAPH_QUERY");
        assertThatThrownBy(() -> new PrivateGraph(GraphQueryType.FIND_CONTACT_BY_ALIAS,null," ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantQuestion(Long.MAX_VALUE,KEY,"zh-CN",1,new PublicText("问题")))
                .hasMessage("INVALID_ASSISTANT_QUESTION");
        assertThatThrownBy(() -> new AssistantQuestion(0,KEY,"../cn",1,new PublicText("问题")))
                .hasMessage("INVALID_ASSISTANT_QUESTION");
    }
    @Test void logsNeverExposeQuestionsKeysOrDigests() {
        var input = question(new PublicText("禁止记录的测试问题"));
        assertThat(input.toString()).doesNotContain(KEY,"禁止记录");
        var fingerprint = codec(1).question(OWNER,SESSION,input);
        assertThat(fingerprint.toString()).doesNotContain(fingerprint.keyHash(),fingerprint.requestDigest());
    }
}
