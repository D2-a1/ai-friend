package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KnowledgeLiveEvaluationSafetyTest {
    @Test void authorizationIsCheckedBeforeAnyCredentialRead() {
        var reads = new AtomicInteger();
        assertThatThrownBy(() -> KnowledgeLiveEvaluationConfig.load(name -> {
            reads.incrementAndGet();
            if (name.endsWith("API_KEY")) { throw new AssertionError("KEY_READ_BEFORE_AUTHORIZATION"); }
            return null;
        })).hasMessage("LIVE_EVALUATION_NOT_AUTHORIZED");
        assertThat(reads.get()).isEqualTo(1);
    }
    @Test void validIndependentConfigDoesNotReadProductionVariablesOrEchoSecrets() {
        var values = environment();
        var config = KnowledgeLiveEvaluationConfig.load(name -> {
            assertThat(name).startsWith(KnowledgeLiveEvaluationConfig.PREFIX); return values.get(name);
        });
        assertThat(config.profileLabel()).isEqualTo("B");
        assertThat(config.embedding().profile().id()).isEqualTo("eval-space");
        assertThat(config.toString()).doesNotContain("fake-key", "vendor.net", "fixture-model");
    }
    @Test void malformedSecretLikeInputMissingFieldsAndExcessiveLimitsAreRedactedAndRejected() {
        for (var invalid : Map.of("CHAT_ENDPOINT", "https://secret-token@bad host", "MAX_CHAT_REQUESTS", "57",
                "MAX_EMBEDDING_REQUESTS", "129", "CHAT_THINKING_MODE", "secret-token", "PROFILE_LABEL", "C").entrySet()) {
            var values = environment(); values.put(KnowledgeLiveEvaluationConfig.PREFIX + invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> KnowledgeLiveEvaluationConfig.load(values::get))
                    .hasMessage("INVALID_LIVE_EVALUATION_CONFIGURATION").hasNoCause();
        }
        var missing = environment(); missing.remove(KnowledgeLiveEvaluationConfig.PREFIX + "EMBEDDING_API_KEY");
        assertThatThrownBy(() -> KnowledgeLiveEvaluationConfig.load(missing::get)).hasMessage("INVALID_LIVE_EVALUATION_CONFIGURATION");
    }
    @Test void failedRequestsConsumeCapAndMissingUsageIsNotReportedAsFree() {
        var calls = new AtomicInteger();
        var transport = new KnowledgeEvaluationTransport(new KnowledgeModelTransport() {
            @Override public byte[] post(byte[] payload, Duration budget) {
                if (calls.incrementAndGet() == 1) { throw new IllegalStateException("synthetic-network-failure"); }
                return "{}".getBytes(StandardCharsets.UTF_8);
            }
            @Override public void close() { }
        }, 2, true);
        assertThatThrownBy(() -> transport.post(new byte[]{1}, Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        transport.post(new byte[]{1}, Duration.ofSeconds(1));
        assertThatThrownBy(() -> transport.post(new byte[]{1}, Duration.ofSeconds(1))).hasMessage("BUSY");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(transport.metrics()).containsEntry("requests", 2).containsEntry("requestsWithUnknownUsage", 2).containsEntry("billedCost", null);
    }
    @Test void usageObservationDoesNotAlterResponseOrTreatMalformedCountsAsKnown() {
        var values = java.util.List.of("{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}",
                "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":3}}",
                "{\"usage\":{\"prompt_tokens\":12,\"prompt_tokens\":14,\"completion_tokens\":3}}");
        var index = new AtomicInteger();
        var transport = new KnowledgeEvaluationTransport(new KnowledgeModelTransport() {
            @Override public byte[] post(byte[] payload, Duration budget) { return values.get(index.getAndIncrement()).getBytes(StandardCharsets.UTF_8); }
            @Override public void close() { }
        }, 3, true);
        for (String value : values) { assertThat(new String(transport.post(new byte[]{1}, Duration.ofSeconds(1)), StandardCharsets.UTF_8)).isEqualTo(value); }
        assertThat(transport.metrics()).containsEntry("reportedPromptTokens", 12L).containsEntry("reportedCompletionTokens", 3L)
                .containsEntry("responsesWithUsage", 1).containsEntry("requestsWithUnknownUsage", 2);
    }
    static Map<String, String> environment() {
        var values = new HashMap<String, String>();
        Map.of("ENABLED", "true", "PUBLIC_DATA_APPROVED", "true", "RUN_ID", "synthetic-run", "PROFILE_LABEL", "B",
                "MAX_CHAT_REQUESTS", "56", "MAX_EMBEDDING_REQUESTS", "64").forEach((key,value) -> values.put(KnowledgeLiveEvaluationConfig.PREFIX + key, value));
        Map.of("ENDPOINT", "https://chat.vendor.net/v1/chat/completions", "ALLOWED_HOSTS", "chat.vendor.net",
                "MODEL", "fixture-model", "API_KEY", "fake-key-only", "PROFILE_ID", "eval-chat", "TOKEN_LIMIT_FIELD", "MAX_TOKENS",
                "MAX_OUTPUT_TOKENS", "512", "THINKING_MODE", "OMIT", "TEMPERATURE", "OMIT").forEach((key,value) -> values.put(KnowledgeLiveEvaluationConfig.PREFIX + "CHAT_" + key, value));
        Map.of("ENDPOINT", "https://embedding.vendor.net/v1/embeddings", "ALLOWED_HOSTS", "embedding.vendor.net",
                "MODEL", "fixture-embedding", "API_KEY", "fake-key-only", "PROFILE_ID", "eval-space", "DIMENSION", "2",
                "BATCH_SIZE", "64").forEach((key,value) -> values.put(KnowledgeLiveEvaluationConfig.PREFIX + "EMBEDDING_" + key, value));
        for (String kind : java.util.List.of("CHAT", "EMBEDDING")) {
            values.put(KnowledgeLiveEvaluationConfig.PREFIX + kind + "_CONNECT_TIMEOUT_MS", "1000");
            values.put(KnowledgeLiveEvaluationConfig.PREFIX + kind + "_READ_TIMEOUT_MS", "4000");
        }
        return values;
    }
}
