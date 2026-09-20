package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertType;
import com.aifriend.shared.error.UpstreamFailureException;

class UnavailableAccountClosureAlertDeliveryAdapterTest {

    @Test
    void shouldFailClosedWithoutCreatingSubmissionOrFakeReceipt() {
        Instant now = Instant.parse("2026-08-20T14:00:00Z");
        AccountClosureAlertDelivery pending = new AccountClosureAlertDelivery(
                UUID.randomUUID(),
                AccountClosureAlertAudience.ON_CALL,
                AccountClosureAlertType.ACCOUNT_CLOSURE_DELAY_WARNING,
                now,
                null,
                0);
        AccountClosureAlertDelivery submitted = new AccountClosureAlertDelivery(
                pending.deliveryId(),
                pending.audience(),
                pending.type(),
                pending.occurredAt(),
                null,
                0,
                "1234567890abcdef1234567890abcdef");
        UnavailableAccountClosureAlertDeliveryAdapter adapter =
                new UnavailableAccountClosureAlertDeliveryAdapter();

        assertThrows(UpstreamFailureException.class, () -> adapter.submit(pending));
        assertThrows(UpstreamFailureException.class, () -> adapter.verify(submitted));
    }
}