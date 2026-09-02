package com.aifriend.identity.infrastructure;

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

import com.aifriend.identity.application.WechatIdentityPort;
import com.aifriend.identity.application.WechatIdentityProperties;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * 生产微信移动应用 OAuth 身份适配器。
 *
 * <p>仅将一次性 code、AppID 与 AppSecret 发送到固定微信官方 HTTPS 端点，
 * 响应只提取应用作用域 openid。access_token、refresh_token、微信错误文本和
 * 原始响应均不记录、不持久化，也不返回客户端。一次性 code 调用不自动重试，
 * 避免上游已经消费 code 但本地未收到响应时发生不确定重放。
 *
 * @author Codex
 * @since 1.0.0
 */
public class WechatIdentityAdapter implements WechatIdentityPort {

    /** Resilience4j 微信身份实例名称。 */
    static final String RESILIENCE_INSTANCE_NAME = "wechatIdentity";
    /** 微信响应体最大允许字节数。 */
    private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024;
    /** 微信无效或已消费 code 错误码。 */
    private static final int INVALID_CODE = 40029;
    /** 微信 code 已使用错误码。 */
    private static final int USED_CODE = 40163;

    private final WechatIdentityProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    /**
     * 创建生产微信身份适配器。
     *
     * @param properties 固定官方端点、凭据和超时配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param objectMapper 受限微信响应 JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 并发舱壁注册表
     */
    public WechatIdentityAdapter(
            WechatIdentityProperties properties,
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

    WechatIdentityAdapter(
            WechatIdentityProperties properties,
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
     * 使用一次性微信 code 兑换应用作用域内的最小稳定主体。
     *
     * @param code 微信一次性授权 code，不得记录或持久化
     * @return 由 AppID 命名空间和 openid 构成的受保护前最小主体
     * @throws BusinessException 当 code 格式无效、已使用或已失效时抛出
     * @throws UpstreamFailureException 当微信接口、舱壁、熔断器或响应校验不可用时抛出
     */
    @Override
    public WechatIdentity exchange(String code) {
        validateCode(code);
        Supplier<WechatIdentity> remoteExchange = () -> exchangeRemote(code);
        Supplier<WechatIdentity> bulkheadExchange = Bulkhead.decorateSupplier(bulkhead, remoteExchange);
        Supplier<WechatIdentity> protectedExchange =
                CircuitBreaker.decorateSupplier(circuitBreaker, bulkheadExchange);
        try {
            return protectedExchange.get();
        } catch (WechatAuthorizationCodeRejectedException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信授权信息已使用或失效");
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private static RestClient buildRestClient(
            WechatIdentityProperties properties,
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
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信授权信息无效");
        }
    }

    private WechatIdentity exchangeRemote(String code) {
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
        } catch (WechatAuthorizationCodeRejectedException | UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private WechatIdentity parseResponse(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new UpstreamFailureException();
        }
        byte[] responseBody = readBoundedBody(response);
        WechatTokenResponse tokenResponse = objectMapper.readValue(responseBody, WechatTokenResponse.class);
        Integer errorCode = tokenResponse.errorCode();
        if (errorCode != null && errorCode != 0) {
            if (errorCode == INVALID_CODE || errorCode == USED_CODE) {
                throw new WechatAuthorizationCodeRejectedException();
            }
            throw new UpstreamFailureException();
        }
        String openId = tokenResponse.openId();
        if (!StringUtils.hasText(openId)
                || openId.length() > 128
                || openId.chars().anyMatch(character -> character <= 0x20 || character > 0x7E)) {
            throw new UpstreamFailureException();
        }
        return new WechatIdentity("wechat-openid:" + properties.appId() + ":" + openId);
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
 * 微信明确拒绝一次性授权 code 的内部信号。
 *
 * <p>该异常不携带 code、微信错误文本或其他上游响应内容，并由熔断器忽略。
 */
class WechatAuthorizationCodeRejectedException extends RuntimeException {
}
