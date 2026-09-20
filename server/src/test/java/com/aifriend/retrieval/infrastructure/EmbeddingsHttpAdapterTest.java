package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.domain.EmbeddingProfile;

class EmbeddingsHttpAdapterTest {
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private final KnowledgeEmbeddingProperties properties = new KnowledgeEmbeddingProperties(true,
            URI.create("https://embed.vendor.net/v1/embeddings"), Set.of("embed.vendor.net"),
            "fake-model", "fake-api-key", 2, "test-v1", 4, Duration.ofSeconds(1), BUDGET);

    @Test void restoresInputOrderFromExplicitIndicesAndSendsMinimalRequest() throws Exception {
        var transport = new FakeTransport("{\"data\":[{\"index\":1,\"embedding\":[0,2]},"
                + "{\"index\":0,\"embedding\":[1,0]}],\"model\":\"fake-model\"}");
        var batch = adapter(transport).embed(properties.profile(), List.of("公开甲", "公开乙"), BUDGET);
        assertThat(batch.vector(0)).containsExactly(1, 0);
        assertThat(batch.vector(1)).containsExactly(0, 2);
        var body = new ObjectMapper().readTree(transport.request);
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("input").get(0).asText()).isEqualTo("公开甲");
        assertThat(body.get("encoding_format").asText()).isEqualTo("float");
        assertThat(new String(transport.request, StandardCharsets.UTF_8)).doesNotContain("owner", "contact", "fake-api-key");
    }

    @Test void malformedBatchIsNeverPartiallyAcceptedOrRetried() {
        for (String response : new String[] {
                "{\"data\":[]}",
                "{\"data\":[{\"index\":1,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0.0,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1e50,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[\"1\",0]}]}",
                "{\"data\":[{\"index\":0,\"index\":0,\"embedding\":[1,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[NaN,0]}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0],\"tool\":\"call\"}]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"model\":\"different-model\"}",
                "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]} {}"}) {
            var transport = new FakeTransport(response);
            assertThatThrownBy(() -> adapter(transport).embed(properties.profile(), List.of("公开说明"), BUDGET))
                    .isInstanceOf(KnowledgeGatewayException.class).hasMessage("PROTOCOL");
            assertThat(transport.calls).hasValue(1);
        }
    }

    @Test void duplicateIndicesCannotHideMissingRows() {
        var transport = new FakeTransport("{\"data\":[{\"index\":0,\"embedding\":[1,0]},"
                + "{\"index\":0,\"embedding\":[0,1]}]}");
        assertThatThrownBy(() -> adapter(transport).embed(properties.profile(), List.of("甲", "乙"), BUDGET))
                .hasMessage("PROTOCOL");
    }

    @Test void sameDimensionsWithDifferentProfileAndInvalidInputNeverCallTransport() {
        var transport = new FakeTransport("");
        var adapter = adapter(transport);
        assertThatThrownBy(() -> adapter.embed(new EmbeddingProfile("other-v1", 2), List.of("公开"), BUDGET))
                .hasMessage("CONFIGURATION");
        for (List<String> input : List.of(List.<String>of(), List.of(" "), List.of("x".repeat(601)),
                java.util.Collections.nCopies(5, "公开"))) {
            assertThatThrownBy(() -> adapter.embed(properties.profile(), input, BUDGET)).hasMessage("CONFIGURATION");
        }
        assertThat(transport.calls).hasValue(0);
    }

    @Test void defensiveInputSnapshotPreventsConcurrentCallerMutation() {
        var input = new ArrayList<>(List.of("公开"));
        var transport = new FakeTransport("{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}");
        transport.duringPost = input::clear;
        assertThat(adapter(transport).embed(properties.profile(), input, BUDGET).size()).isEqualTo(1);
    }

    @Test void circuitOpenAndBulkheadFullDoNotCallTransport() {
        var transport = new FakeTransport("");
        var circuit = CircuitBreaker.ofDefaults("embedding-test");
        var bulkhead = Bulkhead.of("embedding-test", BulkheadConfig.custom().maxConcurrentCalls(1).build());
        var adapter = new EmbeddingsHttpAdapter(properties, transport, new ObjectMapper(), circuit, bulkhead);
        circuit.transitionToOpenState();
        assertThatThrownBy(() -> adapter.embed(properties.profile(), List.of("公开"), BUDGET)).hasMessage("BUSY");
        circuit.transitionToClosedState();
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        try {
            assertThatThrownBy(() -> adapter.embed(properties.profile(), List.of("公开"), BUDGET)).hasMessage("BUSY");
        } finally { bulkhead.releasePermission(); }
        assertThat(transport.calls).hasValue(0);
    }

    private EmbeddingsHttpAdapter adapter(FakeTransport transport) {
        return new EmbeddingsHttpAdapter(properties, transport, new ObjectMapper(),
                CircuitBreaker.ofDefaults("embedding-test"), Bulkhead.ofDefaults("embedding-test"));
    }

    private static final class FakeTransport implements KnowledgeModelTransport {
        private final byte[] response;
        private final AtomicInteger calls = new AtomicInteger();
        private byte[] request;
        private Runnable duringPost = () -> { };
        FakeTransport(String response) { this.response = response.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] post(byte[] payload, Duration budget) {
            request = payload.clone();
            calls.incrementAndGet();
            duringPost.run();
            return response;
        }
        @Override public void close() { }
    }
}
