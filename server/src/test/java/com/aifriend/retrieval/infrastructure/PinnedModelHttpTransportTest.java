package com.aifriend.retrieval.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.application.KnowledgeGatewayException.Kind;

class PinnedModelHttpTransportTest {
    private WireMockServer server;
    private PinnedModelHttpTransport transport;
    private final byte[] request = "{}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void start() {
        server = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        server.start();
        transport = new PinnedModelHttpTransport(URI.create(server.baseUrl() + "/v1/embeddings"), "fake-test-key",
                HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableContentCompression().build(),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
    @AfterEach void stop() {
        transport.close();
        server.stop();
    }

    @Test void completesExactlyOneBoundedJsonPost() {
        server.stubFor(post("/v1/embeddings").willReturn(okJson("{\"data\":[]}")));
        assertThat(new String(transport.post(request, Duration.ofSeconds(2)), StandardCharsets.UTF_8))
                .isEqualTo("{\"data\":[]}");
        server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer fake-test-key")));
    }

    @Test void httpFailuresAreTypedWithoutAutomaticRetry() {
        for (int status : new int[] {401, 403, 429, 500, 503}) {
            server.resetAll();
            server.stubFor(post("/v1/embeddings").willReturn(aResponse().withStatus(status).withBody("secret-upstream")));
            Kind expected = status == 429 || status >= 500 ? Kind.TEMPORARY : Kind.CONFIGURATION;
            assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(1)))
                    .isInstanceOf(KnowledgeGatewayException.class).hasMessage(expected.name())
                    .hasNoCause();
            server.verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")));
        }
    }

    @Test void redirectNeverReachesItsDestination() {
        server.stubFor(post("/v1/embeddings").willReturn(aResponse().withStatus(302)
                .withHeader("Location", server.baseUrl() + "/forbidden")));
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(1)))
                .hasMessage("ENDPOINT_REJECTED");
        server.verify(0, anyRequestedFor(urlEqualTo("/forbidden")));
    }

    @Test void totalDeadlineStopsSlowStreamingBody() {
        server.stubFor(post("/v1/embeddings").willReturn(okJson("{\"data\":[]}")
                .withChunkedDribbleDelay(10, 2000)));
        long start = System.nanoTime();
        assertThatThrownBy(() -> transport.post(request, Duration.ofMillis(100)))
                .hasMessage("TEMPORARY");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test void rejectsOversizeBodyAndNonJson() {
        server.stubFor(post("/v1/embeddings").willReturn(ok("not-json")));
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(2))).hasMessage("PROTOCOL");
        server.resetAll();
        server.stubFor(post("/v1/embeddings").willReturn(okJson("x".repeat(8 * 1024 * 1024 + 1))));
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(4))).hasMessage("PROTOCOL");
    }

    @Test void invalidBudgetAndClosedExecutorNeverSend() {
        assertThatThrownBy(() -> transport.post(request, Duration.ZERO)).hasMessage("CONFIGURATION");
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(9))).hasMessage("CONFIGURATION");
        transport.close();
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(1))).hasMessage("BUSY");
        server.verify(0, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test void chatResponseLimitIsEnforcedBeforeDecoding() {
        transport.close();
        transport = new PinnedModelHttpTransport(URI.create(server.baseUrl() + "/v1/chat/completions"), "fake-test-key",
                HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableContentCompression().build(),
                Duration.ofSeconds(1), Duration.ofSeconds(2), 65536);
        server.stubFor(post("/v1/chat/completions").willReturn(okJson("x".repeat(65537))));
        assertThatThrownBy(() -> transport.post(request, Duration.ofSeconds(2))).hasMessage("PROTOCOL");
        server.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }
}
