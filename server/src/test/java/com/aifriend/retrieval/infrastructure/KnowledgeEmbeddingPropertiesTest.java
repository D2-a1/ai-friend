package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

class KnowledgeEmbeddingPropertiesTest {
    @Test void disabledHasNoRequiredCredentialsAndCannotCreateClient() {
        var disabled = new KnowledgeEmbeddingProperties(false, null, null, null, null, 0, null, 0, null, null);
        assertThat(disabled.allowedHosts()).isEmpty();
        assertThatThrownBy(() -> new PinnedModelHttpTransport(disabled))
                .hasMessage("CONFIGURATION");
    }

    @Test void enabledRejectsPartialConfigurationAndUnsafeEndpoints() {
        for (String endpoint : new String[] {"http://embed.vendor.net/v1/embeddings",
                "https://127.0.0.1/v1/embeddings", "https://embed.vendor.net:8443/v1/embeddings",
                "https://embed.vendor.net/v1/embeddings?key=x", "https://user@embed.vendor.net/v1/embeddings",
                "https://embed.vendor.net/x/../v1/embeddings", "https://embed.vendor.net/v1/%65mbeddings",
                "https://embed.vendor.net/v1/embeddings#fragment"}) {
            assertThatThrownBy(() -> properties(endpoint, "test-key-only", 2)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> properties("https://embed.vendor.net/v1/embeddings", null, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void secretsAndModelConfigurationAreNotLogged() {
        var valid = properties("https://embed.vendor.net/v1/embeddings", "test-key-only", 2);
        assertThat(valid.toString()).doesNotContain("test-key-only", "vendor", "fake-model");
        assertThat(valid.profile().id()).isEqualTo("test-v1");
        assertThatThrownBy(() -> properties("https://embed.vendor.net/v1/embeddings", "key\r\ninjected", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("https://embed.vendor.net/v1/embeddings", "test-key-only", 4097))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private KnowledgeEmbeddingProperties properties(String endpoint, String key, int dimension) {
        return new KnowledgeEmbeddingProperties(true, URI.create(endpoint), Set.of("embed.vendor.net"),
                "fake-model", key, dimension, "test-v1", 4, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
}
