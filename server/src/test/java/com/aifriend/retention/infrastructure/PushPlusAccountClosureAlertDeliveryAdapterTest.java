package com.aifriend.retention.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertDeliveryVerification;
import com.aifriend.retention.application.AccountClosureAlertSubmission;
import com.aifriend.retention.application.AccountClosureAlertType;
import com.aifriend.retention.application.AccountClosureAlertVerificationStatus;
import com.aifriend.retention.application.PushPlusAlertProperties;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.shared.error.UpstreamFailureException;

class PushPlusAccountClosureAlertDeliveryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private static final String TOKEN = "1234567890abcdef1234567890abcdef";
    private static final String SECRET_KEY = "abcdef1234567890abcdef1234567890";
    private static final String ACCESS_KEY =
            "fedcba0987654321fedcba0987654321";
    private static final String SHORT_CODE =
            "00112233445566778899aabbccddeeff";
    private static final URI SEND_URI = URI.create("https://pushplus.test/send");
    private static final URI ACCESS_KEY_URI =
            URI.create("https://pushplus.test/getAccessKey");
    private static final URI RESULT_URI =
            URI.create("https://pushplus.test/sendMessageResult");
    private static final URI MESSAGE_LIST_URI =
            URI.create("https://pushplus.test/message/list");

    @Test
    void shouldCheckExistingMessageThenSubmitOnlyAnonymousChineseAppAlert() {
        TestFixture fixture = fixture();
        expectAccessKey(fixture.server());
        expectMessageList(fixture.server(), "[]");
        fixture.server().expect(once(), request -> {
            assertThat(request.getURI()).isEqualTo(SEND_URI);
            assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
        }).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "token":"1234567890abcdef1234567890abcdef",
                          "title":"AI好友系统告警（12345678-1234-1234-1234-123456789abc）",
                          "template":"txt",
                          "channel":"app",
                          "timestamp":1787789400000
                        }
                        """, false))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("确认接手："))))
                .andRespond(withSuccess("""
                        {"code":200,"msg":"请求成功",
                         "data":"00112233445566778899aabbccddeeff"}
                        """, MediaType.APPLICATION_JSON));

        AccountClosureAlertSubmission submission = fixture.adapter().submit(pendingDelivery());

        assertThat(submission.providerReference()).isEqualTo(SHORT_CODE);
        fixture.server().verify();
    }

    @Test
    void shouldRecoverExistingShortCodeWithoutDuplicateSubmission() {
        TestFixture fixture = fixture();
        expectAccessKey(fixture.server());
        expectMessageList(fixture.server(), """
                [{
                  "title":"AI好友系统告警（12345678-1234-1234-1234-123456789abc）",
                  "shortCode":"00112233445566778899aabbccddeeff"
                }]
                """);

        AccountClosureAlertSubmission submission = fixture.adapter().submit(pendingDelivery());

        assertThat(submission.providerReference()).isEqualTo(SHORT_CODE);
        fixture.server().verify();
    }

    @Test
    void shouldAcquireAccessKeyAndConfirmOnlyFinalDeliveredStatus() {
        TestFixture fixture = fixture();
        expectAccessKey(fixture.server());
        fixture.server().expect(once(), request ->
                assertThat(request.getURI().getQuery()).isEqualTo("shortCode=" + SHORT_CODE))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("access-key", ACCESS_KEY))
                .andRespond(withSuccess("""
                        {"code":200,"data":{"status":2,"errorMessage":""}}
                        """, MediaType.APPLICATION_JSON));

        AccountClosureAlertDeliveryVerification verification =
                fixture.adapter().verify(submittedDelivery());

        assertThat(verification.status())
                .isEqualTo(AccountClosureAlertVerificationStatus.DELIVERED);
        assertThat(new String(
                verification.receiptProof(),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("pushplus-app-delivered:" + SHORT_CODE);
        fixture.server().verify();
    }

    @Test
    void shouldKeepPendingAndExposeExplicitProviderFailureWithoutFakeReceipt() {
        TestFixture pendingFixture = fixture();
        expectAccessKey(pendingFixture.server());
        expectResult(pendingFixture.server(), 1);

        AccountClosureAlertDeliveryVerification pending =
                pendingFixture.adapter().verify(submittedDelivery());

        assertThat(pending.status())
                .isEqualTo(AccountClosureAlertVerificationStatus.PENDING);
        assertThat(pending.receiptProof()).isNull();
        pendingFixture.server().verify();

        TestFixture failedFixture = fixture();
        expectAccessKey(failedFixture.server());
        expectResult(failedFixture.server(), 3);

        AccountClosureAlertDeliveryVerification failed =
                failedFixture.adapter().verify(submittedDelivery());

        assertThat(failed.status())
                .isEqualTo(AccountClosureAlertVerificationStatus.FAILED);
        assertThat(failed.receiptProof()).isNull();
        failedFixture.server().verify();
    }

    @Test
    void shouldFailClosedOnSubmissionErrorWithoutLeakingCredentials() {
        TestFixture fixture = fixture();
        expectAccessKey(fixture.server());
        expectMessageList(fixture.server(), "[]");
        fixture.server().expect(once(), request ->
                assertThat(request.getURI()).isEqualTo(SEND_URI))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.adapter().submit(pendingDelivery()))
                .isInstanceOf(UpstreamFailureException.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining(SECRET_KEY);
        fixture.server().verify();
    }

    private void expectAccessKey(MockRestServiceServer server) {
        server.expect(once(), request -> assertThat(request.getURI()).isEqualTo(ACCESS_KEY_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "token":"1234567890abcdef1234567890abcdef",
                          "secretKey":"abcdef1234567890abcdef1234567890"
                        }
                        """, true))
                .andRespond(withSuccess("""
                        {"code":200,"data":{"accessKey":"fedcba0987654321fedcba0987654321",
                        "expiresIn":7200}}
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectMessageList(MockRestServiceServer server, String messagesJson) {
        server.expect(once(), request -> assertThat(request.getURI()).isEqualTo(MESSAGE_LIST_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("access-key", ACCESS_KEY))
                .andExpect(content().json("""
                        {"current":1,"pageSize":50}
                        """, true))
                .andRespond(withSuccess(
                        "{\"code\":200,\"data\":{\"list\":" + messagesJson + "}}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectResult(MockRestServiceServer server, int status) {
        server.expect(once(), request ->
                assertThat(request.getURI().getQuery()).isEqualTo("shortCode=" + SHORT_CODE))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("access-key", ACCESS_KEY))
                .andRespond(withSuccess(
                        "{\"code\":200,\"data\":{\"status\":" + status + "}}",
                        MediaType.APPLICATION_JSON));
    }


    @Test
    void shouldIncludeMinimalAcknowledgementLinkForEnabledOnCallP0Alert() {
        TestFixture fixture = fixture(true);
        expectAccessKey(fixture.server());
        expectMessageList(fixture.server(), "[]");
        fixture.server().expect(once(), request ->
                assertThat(request.getURI()).isEqualTo(SEND_URI))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "确认接手：https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/"
                                + "12345678-1234-1234-1234-123456789abc/"
                                + "acknowledgement")))
                .andRespond(withSuccess("""
                        {"code":200,
                         "data":"00112233445566778899aabbccddeeff"}
                        """,
                        MediaType.APPLICATION_JSON));

        AccountClosureAlertSubmission submission =
                fixture.adapter().submit(pendingDelivery());

        assertThat(submission.providerReference()).isEqualTo(SHORT_CODE);
        fixture.server().verify();
    }
    private TestFixture fixture() {
        return fixture(false);
    }

    private TestFixture fixture(boolean operationsEnabled) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        PushPlusAccountClosureAlertDeliveryAdapter adapter =
                new PushPlusAccountClosureAlertDeliveryAdapter(
                        properties(),
                        restClientBuilder.build(),
                        new ObjectMapper(),
                        operationsProperties(operationsEnabled),
                        CircuitBreaker.ofDefaults("pushplus-alert-test"),
                        Bulkhead.ofDefaults("pushplus-alert-test"),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        SEND_URI,
                        ACCESS_KEY_URI,
                        RESULT_URI,
                        MESSAGE_LIST_URI);
        return new TestFixture(adapter, server);
    }

    private PushPlusAlertProperties properties() {
        return new PushPlusAlertProperties(
                true,
                TOKEN,
                SECRET_KEY,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }

    private OperationsTotpProperties operationsProperties(boolean enabled) {
        return new OperationsTotpProperties(
                enabled,
                enabled ? "on-call-operator-01" : "",
                enabled ? "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" : "",
                URI.create(enabled
                        ? "https://api.ai-friend.asia/api/v1/operations/"
                                + "account-closure-alert-deliveries/"
                        : "https://api.example.com/api/v1/operations/"
                                + "account-closure-alert-deliveries/"));
    }

    private AccountClosureAlertDelivery pendingDelivery() {
        return new AccountClosureAlertDelivery(
                UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                AccountClosureAlertAudience.ON_CALL,
                AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED,

                NOW.minusSeconds(60),
                NOW.plusSeconds(15 * 60),
                0);
    }

    private AccountClosureAlertDelivery submittedDelivery() {
        AccountClosureAlertDelivery pending = pendingDelivery();
        return new AccountClosureAlertDelivery(
                pending.deliveryId(),
                pending.audience(),
                pending.type(),
                pending.occurredAt(),
                pending.acknowledgementDueAt(),
                0,
                SHORT_CODE);
    }

    private record TestFixture(
            PushPlusAccountClosureAlertDeliveryAdapter adapter,
            MockRestServiceServer server) {
    }
}
