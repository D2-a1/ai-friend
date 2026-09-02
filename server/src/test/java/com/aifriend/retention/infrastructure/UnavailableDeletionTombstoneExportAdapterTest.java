package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.error.UpstreamFailureException;

class UnavailableDeletionTombstoneExportAdapterTest {

    @Test
    void shouldFailClosedWithoutCreatingLocalFallbackReceipt() {
        UnavailableDeletionTombstoneExportAdapter adapter =
                new UnavailableDeletionTombstoneExportAdapter();
        EncryptedDeletionTombstoneEnvelope envelope =
                new EncryptedDeletionTombstoneEnvelope(
                        "dr-key-v1", new byte[80], new byte[32]);

        assertThrows(
                UpstreamFailureException.class,
                () -> adapter.export(UUID.randomUUID(), envelope));
    }
}
