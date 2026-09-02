package com.aifriend.voice.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.application.AudioUploadTarget;
import com.aifriend.voice.application.AudioUploadTicketService;
import com.aifriend.voice.application.CreateAudioUploadTicketCommand;
import com.aifriend.voice.application.CreatedAudioUploadTicket;
import com.aifriend.voice.domain.AudioPurpose;

class AudioUploadTicketsControllerTest {

    @Test
    void shouldUseJwtOwnerAndReturnNoStoreCreatedResponse() {
        UUID ownerUserId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(PublicIdCodec.userId(ownerUserId));
        AudioUploadTicketService service = mock(AudioUploadTicketService.class);
        Instant expiresAt = Instant.parse("2026-08-10T03:10:00Z");
        when(service.create(eq(ownerUserId), eq("01JAUDIOUPLOAD00000000000001"), any()))
                .thenReturn(new CreatedAudioUploadTicket(
                        PublicIdCodec.audioObjectId(UUID.randomUUID()),
                        new AudioUploadTarget(
                                URI.create("http://10.0.2.2:8080/api/v1/dev/audio-objects/au_01"),
                                "PUT",
                                Map.of("X-Audio-Upload-Token", "upload-secret")),
                        expiresAt,
                        "temporary/private-object"));
        AudioUploadTicketsController controller = new AudioUploadTicketsController(service);
        CreateAudioUploadTicketReq request = new CreateAudioUploadTicketReq(
                AudioPurpose.TASK,
                "audio/mp4",
                1_024,
                2_000,
                "11".repeat(32));

        ResponseEntity<ApiResponse<AudioUploadTicketResp>> response =
                controller.createAudioUploadTicket(
                        jwt, "01JAUDIOUPLOAD00000000000001", request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        assertEquals(expiresAt, response.getBody().data().expiresAt());
        ArgumentCaptor<CreateAudioUploadTicketCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateAudioUploadTicketCommand.class);
        verify(service).create(
                eq(ownerUserId), eq("01JAUDIOUPLOAD00000000000001"),
                commandCaptor.capture());
        assertEquals(AudioPurpose.TASK, commandCaptor.getValue().purpose());
    }
}
