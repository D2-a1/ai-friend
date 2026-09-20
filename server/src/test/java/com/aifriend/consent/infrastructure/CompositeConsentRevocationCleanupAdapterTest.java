package com.aifriend.consent.infrastructure;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;

class CompositeConsentRevocationCleanupAdapterTest {

    @Test
    void shouldInvokeAllDomainCleanupHandlersInOrder() {
        ConsentRevocationCleanupHandler first = mock(ConsentRevocationCleanupHandler.class);
        ConsentRevocationCleanupHandler second = mock(ConsentRevocationCleanupHandler.class);
        CompositeConsentRevocationCleanupAdapter adapter =
                new CompositeConsentRevocationCleanupAdapter(List.of(first, second));
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-04T15:00:00Z");

        adapter.cleanup(owner, ConsentType.PERSONAL_MEMORY, now);

        InOrder order = inOrder(first, second);
        order.verify(first).cleanup(owner, ConsentType.PERSONAL_MEMORY, now);
        order.verify(second).cleanup(owner, ConsentType.PERSONAL_MEMORY, now);
    }
}
