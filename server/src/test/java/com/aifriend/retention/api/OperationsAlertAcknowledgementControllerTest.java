package com.aifriend.retention.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aifriend.retention.application.AccountClosureAlertAcknowledgement;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementService;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementTiming;
import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class OperationsAlertAcknowledgementControllerTest {

    private static final UUID DELIVERY_ID = UUID.fromString(
            "12345678-1234-1234-1234-123456789abc");

    private AccountClosureAlertAcknowledgementService acknowledgementService;
    private OperationsAlertAcknowledgementController controller;

    @BeforeEach
    void setUp() {
        acknowledgementService = mock(AccountClosureAlertAcknowledgementService.class);
        controller = new OperationsAlertAcknowledgementController(
                acknowledgementService,
                new OperationsAlertAcknowledgementPageRenderer());
    }

    @Test
    void shouldRenderChineseNoStoreFormWithoutDeliveryIdentifier() {
        ResponseEntity<String> response = controller.form(DELIVERY_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Referrer-Policy"))
                .isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("form-action 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(response.getBody())
                .contains("确认接手告警", "动态验证码")
                .doesNotContain(DELIVERY_ID.toString());
    }

    @Test
    void shouldRenderSuccessWithoutCredentialOrInternalIdentifier() {
        when(acknowledgementService.acknowledge(any())).thenReturn(
                new AccountClosureAlertAcknowledgement(
                        UUID.randomUUID(),
                        AccountClosureAlertAudience.ON_CALL,
                        AccountClosureAlertAcknowledgementTiming.TIMELY,
                        Instant.parse("2026-08-27T00:15:00Z"),
                        Instant.parse("2026-08-27T00:10:00Z")));

        ResponseEntity<String> response =
                controller.acknowledge(DELIVERY_ID, "287082");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("接手成功", "已在截止时间前完成接手")
                .doesNotContain("287082")
                .doesNotContain(DELIVERY_ID.toString());
    }

    @Test
    void shouldRejectMalformedOrRateLimitedCodeWithGenericChinesePage() {
        ResponseEntity<String> malformed =
                controller.acknowledge(DELIVERY_ID, "12345");

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody()).contains("无法确认接手");
        verifyNoInteractions(acknowledgementService);

        when(acknowledgementService.acknowledge(any()))
                .thenThrow(new BusinessException(ErrorCode.RATE_LIMITED));
        ResponseEntity<String> rateLimited =
                controller.acknowledge(DELIVERY_ID, "287082");

        assertThat(rateLimited.getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimited.getBody())
                .contains("无法确认接手")
                .doesNotContain("RATE_LIMITED", "287082");
    }
}
