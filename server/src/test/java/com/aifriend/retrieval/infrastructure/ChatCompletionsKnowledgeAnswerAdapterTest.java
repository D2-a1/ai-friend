package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.domain.*;

class ChatCompletionsKnowledgeAnswerAdapterTest {
    private static final Duration BUDGET = Duration.ofSeconds(1);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sendsOnlyPublicQuestionAndTemporaryEvidenceLabelsInTwoMessages() throws Exception {
        var transport = new Fake();
        var adapter = adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT, KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport);
        var draft = adapter.generate("怎样开启守护？", evidence(), false, BUDGET);
        var body = mapper.readTree(transport.request);
        assertThat(draft.status()).isEqualTo(KnowledgeAnswerDraft.Status.ANSWER);
        assertThat(adapter.profileId()).isEqualTo("chat-v1");
        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.has("tools")).isFalse();
        assertThat(body.get("stream").booleanValue()).isFalse();
        var input = mapper.readTree(body.get("messages").get(1).get("content").textValue());
        assertThat(input.get("question").textValue()).isEqualTo("怎样开启守护？");
        assertThat(input.get("evidence").get(0).get("id").textValue()).isEqualTo("e1");
        assertThat(input.get("evidence").get(0).get("text").textValue()).isEqualTo("首页开启守护");
        String request = new String(transport.request, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(request).doesNotContain("fake-api-key", "00000000", "owner", "quality-report");
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.budget).isLessThanOrEqualTo(BUDGET).isPositive();
    }

    @ParameterizedTest @EnumSource(KnowledgeChatProperties.ThinkingMode.class)
    void honorsExplicitThinkingAndTokenDialects(KnowledgeChatProperties.ThinkingMode mode) throws Exception {
        for (var token : KnowledgeChatProperties.TokenLimitField.values()) {
            var transport = new Fake();
            adapter(properties(mode, token), transport).generate("怎样开启守护？", evidence(), false, BUDGET);
            var body = mapper.readTree(transport.request);
            assertThat(body.has("max_tokens")).isEqualTo(token == KnowledgeChatProperties.TokenLimitField.MAX_TOKENS);
            assertThat(body.has("max_completion_tokens")).isEqualTo(token == KnowledgeChatProperties.TokenLimitField.MAX_COMPLETION_TOKENS);
            assertThat(body.has("enable_thinking")).isEqualTo(mode == KnowledgeChatProperties.ThinkingMode.ENABLED
                    || mode == KnowledgeChatProperties.ThinkingMode.DISABLED);
            assertThat(body.has("thinking")).isEqualTo(mode == KnowledgeChatProperties.ThinkingMode.OBJECT_ENABLED
                    || mode == KnowledgeChatProperties.ThinkingMode.OBJECT_DISABLED);
            if (body.has("thinking")) {
                assertThat(body.get("thinking").get("type").textValue()).isEqualTo(
                        mode == KnowledgeChatProperties.ThinkingMode.OBJECT_ENABLED ? "enabled" : "disabled");
            }
            assertThat(body.has("temperature")).isFalse();
        }
    }

    @Test void repairOnlySimplifiesSystemInstructionAndDoesNotIncludePreviousOutput() throws Exception {
        var transport = new Fake();
        var adapter = adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT, KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport);
        adapter.generate("如何开启", evidence(), false, BUDGET);
        var first = mapper.readTree(transport.request);
        adapter.generate("如何开启", evidence(), true, BUDGET);
        var second = mapper.readTree(transport.request);
        assertThat(second.get("messages").get(1)).isEqualTo(first.get("messages").get(1));
        assertThat(second.get("messages").get(0).get("content").textValue()).contains("上次未满足协议");
        assertThat(transport.calls).isEqualTo(2); // Two explicit calls; never an internal retry.
    }

    @Test void protocolOrTransportFailuresNeverRetryInternally() {
        for (var kind : KnowledgeGatewayException.Kind.values()) {
            var transport = new Fake();
            transport.failure = new KnowledgeGatewayException(kind);
            assertThatThrownBy(() -> adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT,
                    KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport).generate("开启", evidence(), false, BUDGET))
                    .hasMessage(kind.name());
            assertThat(transport.calls).isEqualTo(1);
        }
        var transport = new Fake();
        transport.content = "not json";
        assertThatThrownBy(() -> adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT,
                KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport).generate("开启", evidence(), false, BUDGET))
                .hasMessage("PROTOCOL");
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test void missingEvidenceInvalidQuestionAndBudgetProduceZeroRequests() {
        var transport = new Fake();
        var adapter = adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT, KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport);
        assertThatThrownBy(() -> adapter.generate(" ", evidence(), false, BUDGET)).hasMessage("CONFIGURATION");
        assertThatThrownBy(() -> adapter.generate("字".repeat(501), evidence(), false, BUDGET)).hasMessage("CONFIGURATION");
        assertThatThrownBy(() -> adapter.generate("开启", evidence(), false, Duration.ofSeconds(5))).hasMessage("CONFIGURATION");
        assertThatThrownBy(() -> adapter.generate("开启",
                new RetrievalResult(evidence().indexVersion(), RetrievalResult.Mode.NONE, List.of()), false, BUDGET)).hasMessage("CONFIGURATION");
        assertThat(transport.calls).isZero();
    }

    @Test void cancellationBeforeAndDuringResponseCannotReturnAnAnswer() {
        var transport = new Fake();
        var adapter = adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT, KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> adapter.generate("开启", evidence(), false, BUDGET)).isInstanceOf(CancellationException.class);
            assertThat(transport.calls).isZero();
        } finally { Thread.interrupted(); }
        transport.interrupt = true;
        try {
            assertThatThrownBy(() -> adapter.generate("开启", evidence(), false, BUDGET)).isInstanceOf(CancellationException.class);
            assertThat(transport.calls).isEqualTo(1);
        } finally { Thread.interrupted(); }
    }

    @Test void openCircuitAndFullBulkheadMakeZeroRequests() {
        var transport = new Fake();
        var breaker = CircuitBreaker.ofDefaults("fixture-breaker");
        var bulkhead = Bulkhead.of("fixture-bulkhead", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        var adapter = new ChatCompletionsKnowledgeAnswerAdapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT,
                KnowledgeChatProperties.TokenLimitField.MAX_TOKENS), transport, mapper, breaker, bulkhead);
        breaker.transitionToOpenState();
        assertThatThrownBy(() -> adapter.generate("开启", evidence(), false, BUDGET)).hasMessage("BUSY");
        breaker.reset();
        bulkhead.acquirePermission();
        try { assertThatThrownBy(() -> adapter.generate("开启", evidence(), false, BUDGET)).hasMessage("BUSY"); }
        finally { bulkhead.onComplete(); }
        assertThat(transport.calls).isZero();
        adapter.close();
        assertThat(transport.closed).isTrue();
    }

    @Test void publicHistoryIsMinimalDataAndPrivateHistoryIsRejectedBeforeTransport() throws Exception {
        var transport=new Fake();
        var adapter=adapter(properties(KnowledgeChatProperties.ThinkingMode.OMIT,KnowledgeChatProperties.TokenLimitField.MAX_TOKENS),transport);
        var history=new com.aifriend.assistant.domain.AssistantConversation(com.aifriend.assistant.domain.AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                List.of(new com.aifriend.assistant.domain.AssistantConversation.Turn(new java.util.UUID(0,9876),2,"先前问题","原答案摘要")));
        adapter.generate("守护如何开启",evidence(),history,false,BUDGET);
        var body=mapper.readTree(transport.request);
        var input=mapper.readTree(body.get("messages").get(1).get("content").textValue());
        assertThat(input.get("history").size()).isEqualTo(1);
        assertThat(input.get("history").get(0).get("question").textValue()).isEqualTo("先前问题");
        assertThat(input.get("history").get(0).get("answerSummary").textValue()).isEqualTo("原答案摘要");
        assertThat(input.get("history").get(0).size()).isEqualTo(2);
        assertThat(new String(transport.request,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("requestId","resultVersion","00000000-");
        var privateHistory=new com.aifriend.assistant.domain.AssistantConversation(com.aifriend.assistant.domain.AssistantAnswer.Purpose.CONTACT_GRAPH,List.of());
        assertThatThrownBy(()->adapter.generate("问题",evidence(),privateHistory,false,BUDGET)).hasMessage("CONFIGURATION");
        assertThat(transport.calls).isEqualTo(1);
    }

    static KnowledgeChatProperties properties(KnowledgeChatProperties.ThinkingMode mode, KnowledgeChatProperties.TokenLimitField token) {
        return new KnowledgeChatProperties(true, URI.create("https://chat.vendor.net/v1/chat/completions"), Set.of("chat.vendor.net"),
                "fixture", "fake-api-key", "chat-v1", "fixture-quality-report", token, 512, mode, null,
                Duration.ofSeconds(1), Duration.ofSeconds(4));
    }

    static RetrievalResult evidence() {
        return new RetrievalResult(new IndexVersion(new UUID(0, 1), 1, Optional.empty(), "token-v1", "chunk-v1"),
                RetrievalResult.Mode.KEYWORD_ONLY, List.of(new RetrievalEvidence(new KnowledgeChunk(new UUID(0, 2),
                        new UUID(0, 3), 1, 0, "指南", "首页开启守护", 0, 6, "chunk-v1"), 1)));
    }

    private ChatCompletionsKnowledgeAnswerAdapter adapter(KnowledgeChatProperties properties, Fake transport) {
        return new ChatCompletionsKnowledgeAnswerAdapter(properties, transport, mapper,
                CircuitBreaker.ofDefaults("fixture"), Bulkhead.ofDefaults("fixture"));
    }
    private static class Fake implements KnowledgeModelTransport {
        byte[] request;
        int calls;
        Duration budget;
        boolean closed, interrupt;
        RuntimeException failure;
        String content = "{\"status\":\"ANSWER\",\"sentences\":[{\"text\":\"首页开启守护\",\"evidenceIds\":[\"e1\"]}]}";
        @Override public byte[] post(byte[] payload, Duration budget) {
            calls++; request = payload.clone(); this.budget = budget;
            if (failure != null) { throw failure; }
            if (interrupt) { Thread.currentThread().interrupt(); }
            try { return new ObjectMapper().writeValueAsBytes(java.util.Map.of("model", "fixture", "choices",
                    List.of(java.util.Map.of("index", 0, "finish_reason", "stop", "message",
                            java.util.Map.of("role", "assistant", "content", content))))); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        @Override public void close() { closed = true; }
    }
}
