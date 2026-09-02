package com.aifriend.template.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.template.application.EnrollSafetyCommandsCommand;
import com.aifriend.template.application.RoutineCommandDeletionCommand;
import com.aifriend.template.application.RoutineCommandDeletionService;
import com.aifriend.template.application.RoutineCommandDeletionView;
import com.aifriend.template.application.SafetyCommandEnrollmentResult;
import com.aifriend.template.application.SafetyCommandEnrollmentService;
import com.aifriend.template.application.VoiceTemplateQueryService;
import com.aifriend.template.application.VoiceTemplateSummary;
import com.aifriend.template.domain.SafetyCommandType;

class VoiceTemplatesControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-13T05:00:00Z");

    @Test
    void shouldUseAuthenticatedOwnerForSafetyCommandEnrollment() {
        UUID ownerUserId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        SafetyCommandEnrollmentService enrollmentService =
                mock(SafetyCommandEnrollmentService.class);
        when(enrollmentService.enroll(
                eq(ownerUserId), eq("01JSAFETYCONTROLLER000000001"), any()))
                .thenReturn(new SafetyCommandEnrollmentResult(
                        true, summaries()));
        VoiceTemplatesController controller = new VoiceTemplatesController(
                mock(VoiceTemplateQueryService.class), enrollmentService,
                mock(RoutineCommandDeletionService.class));

        ApiResponse<SafetyCommandEnrollmentResp> response =
                controller.enrollSafetyCommands(
                        jwt,
                        "01JSAFETYCONTROLLER000000001",
                        request());

        assertEquals("OK", response.code());
        assertEquals(true, response.data().complete());
        assertEquals(4, response.data().templates().size());
        verify(enrollmentService).enroll(
                eq(ownerUserId),
                eq("01JSAFETYCONTROLLER000000001"),
                any(EnrollSafetyCommandsCommand.class));
    }

    @Test
    void shouldMergeOnlyTheAuthenticatedOwnerVoiceTemplateMetadata() {
        UUID ownerUserId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        VoiceTemplateQueryService queryService = mock(VoiceTemplateQueryService.class);
        when(queryService.list(ownerUserId)).thenReturn(summaries());
        VoiceTemplatesController controller = new VoiceTemplatesController(
                queryService, mock(SafetyCommandEnrollmentService.class),
                mock(RoutineCommandDeletionService.class));

        ApiResponse<List<VoiceTemplateResp>> response =
                controller.listMyVoiceTemplates(jwt);

        assertEquals(4, response.data().size());
        assertEquals("SAFETY_COMMAND", response.data().get(0).category());
        verify(queryService).list(ownerUserId);
    }

    @Test
    void shouldUseAuthenticatedOwnerForRoutineCommandDeletion() {
        UUID ownerUserId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        RoutineCommandDeletionService deletionService =
                mock(RoutineCommandDeletionService.class);
        when(deletionService.deleteAll(
                eq(ownerUserId), eq("01JROUTINECONTROLLER0000001"), any()))
                .thenReturn(new RoutineCommandDeletionView(2, NOW));
        VoiceTemplatesController controller = new VoiceTemplatesController(
                mock(VoiceTemplateQueryService.class),
                mock(SafetyCommandEnrollmentService.class), deletionService);

        ApiResponse<RoutineTemplateDeletionResp> response =
                controller.deleteMyRoutineCommandTemplates(
                        jwt,
                        "01JROUTINECONTROLLER0000001",
                        new RoutineTemplateDeletionReq(true, 3L));

        assertEquals("OK", response.code());
        assertEquals(2, response.data().deletedCount());
        assertEquals(NOW, response.data().deletedAt());
        verify(deletionService).deleteAll(
                eq(ownerUserId),
                eq("01JROUTINECONTROLLER0000001"),
                eq(new RoutineCommandDeletionCommand(true, 3L)));
    }

    private SafetyCommandEnrollmentReq request() {
        List<SafetyCommandEnrollmentItemReq> commands =
                java.util.Arrays.stream(SafetyCommandType.values())
                        .map(type -> new SafetyCommandEnrollmentItemReq(
                                type,
                                PublicIdCodec.audioObjectId(UUID.randomUUID()),
                                PublicIdCodec.audioObjectId(UUID.randomUUID())))
                        .toList();
        return new SafetyCommandEnrollmentReq(commands, "voice-template-v1");
    }

    private List<VoiceTemplateSummary> summaries() {
        return java.util.Arrays.stream(SafetyCommandType.values())
                .map(type -> new VoiceTemplateSummary(
                        PublicIdCodec.voiceTemplateId(UUID.randomUUID()),
                        "SAFETY_COMMAND", null, null, type, null,
                        "zh-Hans-CN-x-wugang", "wugang-test-v1",
                        "content-template-test-v1", "registration-threshold-test-v1",
                        "COMPATIBLE", NOW))
                .toList();
    }
}
