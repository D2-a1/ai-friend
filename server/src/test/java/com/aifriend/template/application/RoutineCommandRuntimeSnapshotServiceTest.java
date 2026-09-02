package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.domain.TaskIntent;

class RoutineCommandRuntimeSnapshotServiceTest {

    private final RoutineCommandRuntimeStorePort storePort =
            mock(RoutineCommandRuntimeStorePort.class);
    private final RoutineCommandRuntimeSnapshotService service =
            new RoutineCommandRuntimeSnapshotService(storePort);
    private final UUID ownerUserId = UUID.randomUUID();

    @Test
    void ownerWithoutNamespaceMustNotReadTemplates() {
        when(storePort.findNamespaceVersionForUpdate(ownerUserId))
                .thenReturn(Optional.empty());

        Optional<RoutineCommandRuntimeSnapshot> snapshot =
                service.snapshot(ownerUserId);

        assertTrue(snapshot.isEmpty());
        verify(storePort, never()).findActiveByOwner(ownerUserId);
    }

    @Test
    void moreThanThirtyActiveTemplatesMustFailClosed() {
        List<RoutineCommandTemplateRecord> records = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            records.add(record());
        }
        when(storePort.findNamespaceVersionForUpdate(ownerUserId))
                .thenReturn(Optional.of(7L));
        when(storePort.findActiveByOwner(ownerUserId)).thenReturn(records);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.snapshot(ownerUserId));

        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
    }

    @Test
    void namespaceRecheckMustRequireExactVersion() {
        when(storePort.findNamespaceVersion(ownerUserId))
                .thenReturn(Optional.of(7L), Optional.of(8L));

        assertTrue(service.isCurrent(ownerUserId, 7L));
        assertFalse(service.isCurrent(ownerUserId, 7L));
    }

    private RoutineCommandTemplateRecord record() {
        Instant now = Instant.parse("2026-08-24T03:00:00Z");
        return new RoutineCommandTemplateRecord(
                UUID.randomUUID(), TaskIntent.SEND_MESSAGE,
                "zh-Hans-CN-x-wugang", "dialect-v1", "mfcc-v1",
                "threshold-v1", new byte[] {1}, new byte[32],
                1, now, 0L, now);
    }
}
