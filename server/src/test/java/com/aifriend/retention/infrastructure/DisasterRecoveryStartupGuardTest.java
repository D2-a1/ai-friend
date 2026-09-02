package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.aifriend.retention.application.DeletionTombstoneRestoreCoordinator;
import com.aifriend.retention.application.DisasterRecoveryProperties;

class DisasterRecoveryStartupGuardTest {

    @Test
    void shouldAllowNormalStartup() {
        DeletionTombstoneRestoreCoordinator coordinator =
                mock(DeletionTombstoneRestoreCoordinator.class);
        DisasterRecoveryStartupGuard guard = new DisasterRecoveryStartupGuard(
                new DisasterRecoveryProperties(false, "", false, "", ""),
                coordinator);

        assertDoesNotThrow(guard::afterSingletonsInstantiated);
        verifyNoInteractions(coordinator);
    }

    @Test
    void shouldRequireCurrentBootRestoreVerificationInRestoreMode() {
        DeletionTombstoneRestoreCoordinator coordinator =
                mock(DeletionTombstoneRestoreCoordinator.class);
        DisasterRecoveryStartupGuard guard = new DisasterRecoveryStartupGuard(
                new DisasterRecoveryProperties(
                        true,
                        "snapshot-20260820",
                        false,
                        "dr-key-v1",
                        Base64.getEncoder().encodeToString(new byte[32])),
                coordinator);

        assertDoesNotThrow(guard::afterSingletonsInstantiated);
        verify(coordinator).restoreAndVerify();
    }
}
