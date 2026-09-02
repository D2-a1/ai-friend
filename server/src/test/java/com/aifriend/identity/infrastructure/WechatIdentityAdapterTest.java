package com.aifriend.identity.infrastructure;

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

import com.aifriend.identity.application.WechatIdentityProperties;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产微信身份 HTTP 适配器隔离测试。
 *
 * <p>全部 HTTP 响应由 Spring 本地桩拦截，不访问微信或其他真实网络地址。
 *
 * @author Codex
 * @since 1.0.0
 */
class WechatIdentityAdapterTest {

    private static final String APP_ID = "wx1234567890abcdef";
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String AUTHORIZATION_CODE = "authorization-code-123";

    @Test
    void shouldExchangeCodeForNamespacedOpenIdWithoutRetainingTokens() {
        TestFixture fixture = fixture();
        fixture.server().expect(once(), request -> assertOfficialRequest(request.getURI()))
                .andRespond(withSuccess("""
                        {
                          "access_token": "must-not-be-retained",
                          "refresh_token": "must-not-be-retained-either",
                          "expires_in": 7200,
                          "openid": "openid-123",
                          "scope": "snsapi_base"
                        }
                        """, MediaType.APPLICATION_JSON));

        WechatIdentity identity = fixture.adapter().exchange(AUTHORIZATION_CODE);

        assertThat(identity.subject())
                .isEqualTo("wechat-openid:" + APP_ID + ":openid-123")
                .doesNotContain("must-not-be-retained");
        fixture.server().verify();
    }

    @Test
    void shouldMapInvalidOrUsedCodeToControlledValidationFailure() {
        TestFixture fixture = fixture();
        fixture.server().expect(once(), request -> assertOfficialRequest(request.getURI()))
                .andRespond(withSuccess("""
                        {"errcode": 40163, "errmsg": "code been used"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.adapter().exchange(AUTHORIZATION_CODE))
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

        assertThatThrownBy(() -> fixture.adapter().exchange(AUTHORIZATION_CODE))
                .isInstanceOf(UpstreamFailureException.class)
                .hasMessageNotContaining(APP_ID)
                .hasMessageNotContaining(APP_SECRET)
                .hasMessageNotContaining(AUTHORIZATION_CODE);
        fixture.server().verify();
    }

    @Test
    void shouldRejectUnsafeCodeBeforeAnyHttpRequest() {
        TestFixture fixture = fixture();

        assertThatThrownBy(() -> fixture.adapter().exchange("code\nwith-control"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        fixture.server().verify();
    }

    private TestFixture fixture() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        WechatIdentityAdapter adapter = new WechatIdentityAdapter(
                properties(),
                restClientBuilder.build(),
                new ObjectMapper(),
                CircuitBreaker.ofDefaults("wechat-identity-test"),
                Bulkhead.ofDefaults("wechat-identity-test"));
        return new TestFixture(adapter, server);
    }

    private WechatIdentityProperties properties() {
        return new WechatIdentityProperties(
                true,
                APP_ID,
                APP_SECRET,
                WechatIdentityProperties.OFFICIAL_TOKEN_ENDPOINT,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }

    private void assertOfficialRequest(URI requestUri) {
        assertThat(requestUri.getScheme()).isEqualTo("https");
        assertThat(requestUri.getHost()).isEqualTo("api.weixin.qq.com");
        assertThat(requestUri.getPath()).isEqualTo("/sns/oauth2/access_token");
        var queryParameters = UriComponentsBuilder.fromUri(requestUri).build().getQueryParams();
        assertThat(queryParameters.getFirst("appid")).isEqualTo(APP_ID);
        assertThat(queryParameters.getFirst("secret")).isEqualTo(APP_SECRET);
        assertThat(queryParameters.getFirst("code")).isEqualTo(AUTHORIZATION_CODE);
        assertThat(queryParameters.getFirst("grant_type")).isEqualTo("authorization_code");
    }

    private record TestFixture(WechatIdentityAdapter adapter, MockRestServiceServer server) {
    }
}
