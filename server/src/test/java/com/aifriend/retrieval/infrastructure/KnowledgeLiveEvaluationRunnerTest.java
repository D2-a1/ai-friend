package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

/** 生产协议适配器和问答管线，传输全部为内存替身；绝不构造 HTTP 客户端。 */
class KnowledgeLiveEvaluationRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void validModelAbstentionsCannotBecomeAnAutomaticQualityPass() throws Exception {
        var report = replay("{\"status\":\"NO_EVIDENCE\",\"sentences\":[]}");
        assertThat(report.path("samples").size()).isEqualTo(28);
        assertThat(report.path("qualityDecision").asText()).isEqualTo("PENDING_MANUAL_REVIEW_NOT_AUTOMATIC_PASS");
        assertThat(report.path("generationProtocol").path("validDrafts").asInt()).isPositive();
        assertThat(report.path("generationProtocol").path("protocolErrors").asInt()).isZero();
        assertThat(report.path("recallAt4").path("denominator").asInt()).isEqualTo(20);
        assertThat(report.path("realDatabaseUsed").asBoolean()).isFalse();
        for (var sample : report.path("samples")) {
            assertThat(sample.path("mode").asText()).isNotEqualTo("GENERATED");
            assertThat(sample.path("humanEvidenceSupport").asText()).isEqualTo("PENDING_MANUAL_REVIEW");
        }
    }

    @Test void malformedModelProtocolIsCountedAndFallbackNeverCountsAsGeneration() throws Exception {
        var report = replay("not-json");
        assertThat(report.path("samples").size()).isEqualTo(28);
        assertThat(report.path("generationProtocol").path("protocolErrors").asInt()).isPositive();
        assertThat(report.path("generationProtocol").path("validDrafts").asInt()).isZero();
        for (var sample : report.path("samples")) { assertThat(sample.path("mode").asText()).isNotEqualTo("GENERATED"); }
        assertThat(report.path("chatTransport").path("requests").asInt()).isBetween(1, 56);
        assertThat(report.path("embeddingTransport").path("requests").asInt()).isBetween(1, 64);
        assertThat(report.path("chatTransport").path("billedCost").isNull()).isTrue();
    }

    @Test void frozenInputsAndConfigurationIdentityCannotBeSilentlySubstituted() throws Exception {
        assertThatThrownBy(() -> KnowledgeLiveEvaluationRunner.rows("corpus-v1.jsonl", "0".repeat(64)))
                .hasMessage("FROZEN_EVALUATION_DATA_CHANGED");
        assertThat(KnowledgeLiveEvaluationRunner.fingerprint("model-a", "profile", "2"))
                .isNotEqualTo(KnowledgeLiveEvaluationRunner.fingerprint("model-b", "profile", "2"));
    }

    private com.fasterxml.jackson.databind.JsonNode replay(String content) throws Exception {
        var config = KnowledgeLiveEvaluationConfig.load(KnowledgeLiveEvaluationSafetyTest.environment()::get);
        try (var chatTransport = new KnowledgeEvaluationTransport(new Fake(false, content), config.maxChatRequests(), true);
             var embeddingTransport = new KnowledgeEvaluationTransport(new Fake(true, content), config.maxEmbeddingRequests(), false)) {
            var chat = new ChatCompletionsKnowledgeAnswerAdapter(config.chat(), chatTransport, mapper,
                    CircuitBreaker.ofDefaults("fixture-chat"), Bulkhead.ofDefaults("fixture-chat"));
            var embedding = new EmbeddingsHttpAdapter(config.embedding(), embeddingTransport, mapper,
                    CircuitBreaker.ofDefaults("fixture-embedding"), Bulkhead.ofDefaults("fixture-embedding"));
            var report = new KnowledgeLiveEvaluationRunner().run(config, embedding, chat);
            report.put("chatTransport", chatTransport.metrics()); report.put("embeddingTransport", embeddingTransport.metrics());
            String json = mapper.writeValueAsString(report);
            assertThat(json).doesNotContain("fake-key-only", "vendor.net");
            return mapper.readTree(json);
        }
    }

    private final class Fake implements KnowledgeModelTransport {
        private final boolean embedding;
        private final String content;
        private Fake(boolean embedding, String content) { this.embedding = embedding; this.content = content; }
        @Override public byte[] post(byte[] payload, Duration budget) {
            try {
                var request = mapper.readTree(payload);
                assertThat(request.has("ownerId")).isFalse();
                assertThat(request.has("sessionId")).isFalse();
                assertThat(request.has("contacts")).isFalse();
                if (embedding) {
                    var vectors = new ArrayList<Map<String, Object>>();
                    for (int index = 0; index < request.path("input").size(); index++) {
                        vectors.add(Map.of("index", index, "embedding", List.of(1, 0)));
                    }
                    return mapper.writeValueAsBytes(Map.of("model", "fixture-embedding", "data", vectors));
                }
                return mapper.writeValueAsBytes(Map.of("model", "fixture-model", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))));
            } catch (java.io.IOException failure) { throw new IllegalStateException("INVALID_SYNTHETIC_FIXTURE"); }
        }
        @Override public void close() { }
    }
}
