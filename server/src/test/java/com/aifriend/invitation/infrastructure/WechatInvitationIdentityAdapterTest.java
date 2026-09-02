package com.aifriend.invitation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.aifriend.invitation.application.WechatInvitationIdentity;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产微信邀请身份 HTTP 适配器隔离测试。
 *
 * <p>全部 HTTP 响应由 Spring 本地桩拦截，不访问微信或其他真实网络地址。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatInvitationIdentityAdapterTest {

    private static final String APP_ID = "wx-invitation-app-id";
    private static final String APP_SECRET = "invitation-app-secret-0123456789";
    private static final String AUTHORIZATION_CODE = "invitation-code-123";
    private static final URI CALLBACK =
            URI.create("https://api.ai-friend.asia/api/v1/oauth/wechat/invitation-callback");

    @Test
    void shouldExchangeCodeForNamespacedOpenIdWithoutRetainingTokens() {
        TestFixture fixture = fixture();
        fixture.server().expect(once(), request -> assertOfficialRequest(request.getURI()))
                .andRespond(withSuccess("""
                        {
                          "access_token": "must-not-be-retained",
                          "refresh_token": "must-not-be-retained-either",
                          "expires_in": 7200,
                          "openid": "relative-openid-123",
                          "scope": "snsapi_base"
                        }
                        """, MediaType.APPLICATION_JSON));

        WechatInvitationIdentity identity =
                fixture.adapter().exchangeCode(AUTHORIZATION_CODE);

        assertThat(identity.subject())
                .isEqualTo("wechat-openid:" + APP_ID + ":relative-openid-123")
                .doesNotContain("must-not-be-retained");
        fixture.server().verify();
    }

    @Test
    void shouldMapInvalidOrUsedCodeToControlledValidationFailure() {
        TestFixture fixture = fixture();
        fixture.server().expect(once(), request -> assertOfficialRequest(request.getURI()))
                .andRespond(withSuccess(
                        """
                        {"errcode":40163,"errmsg":"code been used"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.adapter().exchangeCode(AUTHORIZATION_CODE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).doesNotContain(AUTHORIZATION_CODE, APP_SECRET);
                });
        fixture.server().verify();
    }

    @Test
    void shouldFailClosedWithoutLeakingCredentialsWhenWechatIsUnavailable() {
        TestFixture fixture = fixture();
        fixture.server().expect(once(), request -> assertOfficialRequest(request.getURI()))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.adapter().exchangeCode(AUTHORIZATION_CODE))
                .isInstanceOf(UpstreamFailureException.class)
                .hasMessageNotContaining(APP_ID)
                .hasMessageNotContaining(APP_SECRET)
                .hasMessageNotContaining(AUTHORIZATION_CODE);
        fixture.server().verify();
    }

    @Test
    void shouldRejectUnsafeCodeBeforeAnyHttpRequest() {
        TestFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.adapter().exchangeCode("code\nwith-control"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        fixture.server().verify();
    }

    private TestFixture fixture() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        WechatInvitationIdentityAdapter adapter = new WechatInvitationIdentityAdapter(
                properties(),
                restClientBuilder.build(),
                new ObjectMapper(),
                CircuitBreaker.ofDefaults("wechat-invitation-test"),
                Bulkhead.ofDefaults("wechat-invitation-test"));
        return new TestFixture(adapter, server);
    }

    private WechatInvitationOAuthProperties properties() {
        return new WechatInvitationOAuthProperties(
                true,
                APP_ID,
                APP_SECRET,
                WechatInvitationOAuthProperties.OFFICIAL_AUTHORIZATION_ENDPOINT,
                WechatInvitationOAuthProperties.OFFICIAL_TOKEN_ENDPOINT,
                CALLBACK,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }

    private void assertOfficialRequest(URI requestUri) {
        assertThat(requestUri.getScheme()).isEqualTo("https");
        assertThat(requestUri.getHost()).isEqualTo("api.weixin.qq.com");
        assertThat(requestUri.getPath()).isEqualTo("/sns/oauth2/access_token");
        var query = UriComponentsBuilder.fromUri(requestUri).build().getQueryParams();
        assertThat(query.getFirst("appid")).isEqualTo(APP_ID);
        assertThat(query.getFirst("secret")).isEqualTo(APP_SECRET);
        assertThat(query.getFirst("code")).isEqualTo(AUTHORIZATION_CODE);
        assertThat(query.getFirst("grant_type")).isEqualTo("authorization_code");
    }

    private record TestFixture(
            WechatInvitationIdentityAdapter adapter,
            MockRestServiceServer server) {
    }
}
