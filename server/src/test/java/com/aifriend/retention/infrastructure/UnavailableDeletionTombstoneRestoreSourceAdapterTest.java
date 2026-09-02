package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.UpstreamFailureException;

class UnavailableDeletionTombstoneRestoreSourceAdapterTest {

    @Test
    void shouldFailClosedWithoutReadingLocalFallback() {
        UnavailableDeletionTombstoneRestoreSourceAdapter adapter =
                new UnavailableDeletionTombstoneRestoreSourceAdapter();

        assertThrows(
                UpstreamFailureException.class,
                () -> adapter.openSnapshot("snapshot-20260820"));
        assertThrows(
                UpstreamFailureException.class,
                () -> adapter.readBatch("snapshot-20260820", null, 100));
    }
}
