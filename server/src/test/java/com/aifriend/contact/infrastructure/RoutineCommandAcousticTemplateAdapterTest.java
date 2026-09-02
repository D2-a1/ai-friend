package com.aifriend.contact.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandAudioSnapshot;
import com.aifriend.template.application.RoutineCommandLearningJob;

class RoutineCommandAcousticTemplateAdapterTest {

    @Test
    void missingVerifiedDialectPackageMustFailClosedBeforeReadingAudio() {
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.empty());
        RoutineCommandAcousticTemplateAdapter adapter =
                new RoutineCommandAcousticTemplateAdapter(registry);
        RoutineCommandLearningJob job = new RoutineCommandLearningJob(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), TaskIntent.VOICE_CALL, 100, 500,
                "zh-Hans-CN-x-wugang", "dialect-v1", "mfcc-v1",
                "threshold-v1", 1, UUID.randomUUID(),
                Instant.parse("2026-08-24T01:00:00Z"),
                Instant.parse("2026-08-24T00:00:00Z"));

        BusinessException exception;
        try (RoutineCommandAudioSnapshot audio =
                new RoutineCommandAudioSnapshot(
                        "audio/wav", 1_000, new byte[] {1, 2, 3})) {
            exception = assertThrows(
                    BusinessException.class,
                    () -> adapter.extract(job, audio));
        }

        assertEquals(ErrorCode.TEMPLATE_INCOMPATIBLE, exception.errorCode());
    }
}
