package com.aifriend.invitation.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.invitation.application.WechatInvitationIdentity;
import com.aifriend.invitation.application.WechatInvitationIdentityPort;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产微信邀请网页 OAuth 身份适配器。
 *
 * <p>仅将一次性 code、独立公众号 AppID 与 AppSecret 发送到固定微信官方 HTTPS
 * 端点。响应只提取当前 AppID 范围 openid；访问令牌、刷新令牌、微信错误文本和
 * 原始响应均不记录、不持久化。一次性 code 调用不自动重试。
 *
 * @author Codex
 * @since 1.0.0
 */
public class WechatInvitationIdentityAdapter implements WechatInvitationIdentityPort {

    /** Resilience4j 微信邀请身份实例名称。 */
    static final String RESILIENCE_INSTANCE_NAME = "wechatInvitationIdentity";
    /** 微信响应体最大允许字节数。 */
    private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024;
    /** 微信无效 code 错误码。 */
    private static final int INVALID_CODE = 40029;
    /** 微信 code 已使用错误码。 */
    private static final int USED_CODE = 40163;

    private final WechatInvitationOAuthProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    /**
     * 创建生产微信邀请身份适配器。
     *
     * @param properties 邀请网页 OAuth 配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper 受限响应 JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 并发舱壁注册表
     */
    public WechatInvitationIdentityAdapter(
            WechatInvitationOAuthProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry) {
        this(
                properties,
                buildRestClient(properties, restClientBuilder),
                objectMapper,
                circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME),
                bulkheadRegistry.bulkhead(RESILIENCE_INSTANCE_NAME));
    }

    WechatInvitationIdentityAdapter(
            WechatInvitationOAuthProperties properties,
            RestClient restClient,
            ObjectMapper objectMapper,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    /**
     * 使用一次性 code 兑换邀请会话的最小微信主体。
     *
     * @param code 当前邀请回调的一次性 code，不得记录或持久化
     * @return 当前公众号 AppID 命名空间内的最小微信主体
     * @throws BusinessException 当 code 无效、已使用或已失效时抛出
     * @throws UpstreamFailureException 当微信接口或响应校验不可用时抛出
     */
    @Override
    public WechatInvitationIdentity exchangeCode(String code) {
        validateCode(code);
        Supplier<WechatInvitationIdentity> remoteExchange = () -> exchangeRemote(code);
        Supplier<WechatInvitationIdentity> bulkheadExchange =
                Bulkhead.decorateSupplier(bulkhead, remoteExchange);
        Supplier<WechatInvitationIdentity> protectedExchange =
                CircuitBreaker.decorateSupplier(circuitBreaker, bulkheadExchange);
        try {
            return protectedExchange.get();
        } catch (WechatInvitationAuthorizationCodeRejectedException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信邀请授权信息已使用或失效");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private static RestClient buildRestClient(
            WechatInvitationOAuthProperties properties,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    private void validateCode(String code) {
        if (!StringUtils.hasText(code)
                || code.length() > 512
                || code.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信邀请授权信息无效");
        }
    }

    private WechatInvitationIdentity exchangeRemote(String code) {
        URI exchangeUri = UriComponentsBuilder.fromUri(properties.tokenEndpoint())
                .queryParam("appid", properties.appId())
                .queryParam("secret", properties.appSecret())
                .queryParam("code", code)
                .queryParam("grant_type", "authorization_code")
                .build()
                .encode()
                .toUri();
        try {
            return restClient.get()
                    .uri(exchangeUri)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> parseResponse(response));
        } catch (WechatInvitationAuthorizationCodeRejectedException | UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private WechatInvitationIdentity parseResponse(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new UpstreamFailureException();
        }
        byte[] responseBody = readBoundedBody(response);
        WechatTokenResponse tokenResponse = objectMapper.readValue(responseBody, WechatTokenResponse.class);
        Integer errorCode = tokenResponse.errorCode();
        if (errorCode != null && errorCode != 0) {
            if (errorCode == INVALID_CODE || errorCode == USED_CODE) {
                throw new WechatInvitationAuthorizationCodeRejectedException();
            }
            throw new UpstreamFailureException();
        }
        String openId = tokenResponse.openId();
        if (!StringUtils.hasText(openId)
                || openId.length() > 128
                || openId.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new UpstreamFailureException();
        }
        return new WechatInvitationIdentity(
                "wechat-openid:" + properties.appId() + ":" + openId);
    }

    private byte[] readBoundedBody(ClientHttpResponse response) throws IOException {
        try (InputStream responseBody = response.getBody()) {
            byte[] boundedBody = responseBody.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (boundedBody.length == 0 || boundedBody.length > MAXIMUM_RESPONSE_BYTES) {
                throw new UpstreamFailureException();
            }
            return boundedBody;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WechatTokenResponse(
            @JsonProperty("openid") String openId,
            @JsonProperty("errcode") Integer errorCode) {
    }
}

/**
 * 微信明确拒绝邀请一次性授权 code 的内部信号。
 *
 * <p>该异常不携带 code、微信错误文本或其他上游响应内容，并由熔断器忽略。
 */
class WechatInvitationAuthorizationCodeRejectedException extends RuntimeException {
}
