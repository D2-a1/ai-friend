package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.UpstreamFailureException;

class UnavailableAccountClosureAlertResponderIdentityAdapterTest {

    @Test
    void shouldFailClosedAndClearCredentialWithoutFallbackIdentity() {
        byte[] credential = new byte[] {1, 2, 3};
        UnavailableAccountClosureAlertResponderIdentityAdapter adapter =
                new UnavailableAccountClosureAlertResponderIdentityAdapter();

        assertThrows(
                UpstreamFailureException.class,
                () -> adapter.verify(UUID.randomUUID(), credential));

        assertArrayEquals(new byte[3], credential);
    }
}
