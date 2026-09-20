package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertDeliveryPort;
import com.aifriend.retention.application.AccountClosureAlertDeliveryVerification;
import com.aifriend.retention.application.AccountClosureAlertSubmission;
import com.aifriend.retention.application.AccountClosureAlertType;
import com.aifriend.retention.application.AccountClosureAlertVerificationStatus;
import com.aifriend.retention.application.PushPlusAlertProperties;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * PushPlus App 账号注销匿名告警生产适配器。
 *
 * <p>消息提交只返回供应商流水号，不视为送达；后续使用开放接口 AccessKey 查询最终
 * 状态，只有状态 2 才生成稳定回执。个人 Token、SecretKey、AccessKey、供应商流水号和
 * 原始响应均不得记录日志。AccessKey 只缓存在当前进程内，并在过期前刷新。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public class PushPlusAccountClosureAlertDeliveryAdapter
        implements AccountClosureAlertDeliveryPort {

    /** Resilience4j PushPlus 告警实例名称。 */
    static final String RESILIENCE_INSTANCE_NAME = "pushPlusAlert";
    /** PushPlus 响应体最大允许字节数。 */
    private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024;
    /** AccessKey 提前刷新窗口。 */
    private static final Duration ACCESS_KEY_REFRESH_SKEW = Duration.ofMinutes(1);
    /** 过期消息截止窗口。 */
    private static final Duration MESSAGE_EXPIRY = Duration.ofMinutes(10);

    private final PushPlusAlertProperties properties;
    /** 个人运维接手页面配置。 */
    private final OperationsTotpProperties operationsTotpProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Clock clock;
    private final URI sendEndpoint;
    private final URI accessKeyEndpoint;
    private final URI resultEndpoint;
    private final URI messageListEndpoint;
    private volatile AccessKeyState accessKeyState;

    /**
     * 创建 PushPlus App 注销告警生产适配器。
     *
     * @param properties PushPlus 个人告警配置
     * @param restClientBuilder Spring HTTP 客户端构造器
     * @param operationsTotpProperties 个人运维接手页面配置
     * @param objectMapper 受限 JSON 解析器
     * @param circuitBreakerRegistry 熔断器注册表
     * @param bulkheadRegistry 舱壁注册表
     * @param clock UTC 时钟
     */
    public PushPlusAccountClosureAlertDeliveryAdapter(
            PushPlusAlertProperties properties,
            OperationsTotpProperties operationsTotpProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            BulkheadRegistry bulkheadRegistry,
            Clock clock) {
        this(
                properties,
                buildRestClient(properties, restClientBuilder),
                objectMapper,
                operationsTotpProperties,
                circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE_NAME),
                bulkheadRegistry.bulkhead(RESILIENCE_INSTANCE_NAME),
                clock,
                PushPlusAlertProperties.SEND_ENDPOINT,
                PushPlusAlertProperties.ACCESS_KEY_ENDPOINT,
                PushPlusAlertProperties.RESULT_ENDPOINT,
                PushPlusAlertProperties.MESSAGE_LIST_ENDPOINT);
    }

    PushPlusAccountClosureAlertDeliveryAdapter(
            PushPlusAlertProperties properties,
            RestClient restClient,
            ObjectMapper objectMapper,
            OperationsTotpProperties operationsTotpProperties,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            Clock clock,
            URI sendEndpoint,
            URI accessKeyEndpoint,
            URI resultEndpoint,
            URI messageListEndpoint) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.operationsTotpProperties = operationsTotpProperties;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.clock = clock;
        this.sendEndpoint = sendEndpoint;
        this.accessKeyEndpoint = accessKeyEndpoint;
        this.resultEndpoint = resultEndpoint;
        this.messageListEndpoint = messageListEndpoint;
    }

    /** {@inheritDoc} */
    @Override
    public AccountClosureAlertSubmission submit(AccountClosureAlertDelivery delivery) {
        if (delivery == null || delivery.hasProviderReference()) {
            throw new IllegalArgumentException("只能提交尚未获得供应商流水号的告警");
        }
        return protectedCall(() -> submitRemote(delivery));
    }

    /** {@inheritDoc} */
    @Override
    public AccountClosureAlertDeliveryVerification verify(
            AccountClosureAlertDelivery delivery) {
        if (delivery == null || !delivery.hasProviderReference()) {
            throw new IllegalArgumentException("只能复验已经提交的告警");
        }
        return protectedCall(() -> verifyRemote(delivery.providerReference()));
    }

    private static RestClient buildRestClient(
            PushPlusAlertProperties properties,
            RestClient.Builder restClientBuilder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    private <T> T protectedCall(Supplier<T> remoteCall) {
        Supplier<T> limitedCall = Bulkhead.decorateSupplier(bulkhead, remoteCall);
        Supplier<T> protectedCall = CircuitBreaker.decorateSupplier(circuitBreaker, limitedCall);
        try {
            return protectedCall.get();
        } catch (UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private AccountClosureAlertSubmission submitRemote(
            AccountClosureAlertDelivery delivery) {
        String existingReference = findExistingSubmission(delivery.deliveryId());
        if (existingReference != null) {
            return new AccountClosureAlertSubmission(existingReference);
        }
        PushPlusSendRequest request = new PushPlusSendRequest(
                properties.userToken(),
                title(delivery.deliveryId()),
                content(delivery),
                "txt",
                "app",
                Instant.now(clock).plus(MESSAGE_EXPIRY).toEpochMilli());
        try {
            return restClient.post()
                    .uri(sendEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> parseSubmission(response));
        } catch (UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private String findExistingSubmission(java.util.UUID deliveryId) {
        try {
            return queryMessageList(deliveryId, currentAccessKey());
        } catch (PushPlusAccessKeyRejectedException exception) {
            invalidateAccessKey();
            return queryMessageList(deliveryId, currentAccessKey());
        }
    }

    private String queryMessageList(java.util.UUID deliveryId, String accessKey) {
        PushPlusMessageListRequest request = new PushPlusMessageListRequest(1, 50);
        try {
            return restClient.post()
                    .uri(messageListEndpoint)
                    .header("access-key", accessKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) ->
                            parseExistingSubmission(response, title(deliveryId)));
        } catch (PushPlusAccessKeyRejectedException | UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private AccountClosureAlertDeliveryVerification verifyRemote(
            String providerReference) {
        try {
            return queryResult(providerReference, currentAccessKey());
        } catch (PushPlusAccessKeyRejectedException exception) {
            invalidateAccessKey();
            return queryResult(providerReference, currentAccessKey());
        }
    }

    private AccountClosureAlertDeliveryVerification queryResult(
            String providerReference,
            String accessKey) {
        URI queryUri = UriComponentsBuilder.fromUri(resultEndpoint)
                .queryParam("shortCode", providerReference)
                .build()
                .encode()
                .toUri();
        try {
            return restClient.get()
                    .uri(queryUri)
                    .header("access-key", accessKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) ->
                            parseVerification(response, providerReference));
        } catch (PushPlusAccessKeyRejectedException | UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private synchronized String currentAccessKey() {
        Instant now = Instant.now(clock);
        AccessKeyState current = accessKeyState;
        if (current != null
                && current.expiresAt().isAfter(now.plus(ACCESS_KEY_REFRESH_SKEW))) {
            return current.value();
        }
        AccessKeyState refreshed = requestAccessKey(now);
        accessKeyState = refreshed;
        return refreshed.value();
    }

    private synchronized void invalidateAccessKey() {
        accessKeyState = null;
    }

    private AccessKeyState requestAccessKey(Instant now) {
        PushPlusAccessKeyRequest request = new PushPlusAccessKeyRequest(
                properties.userToken(), properties.secretKey());
        try {
            return restClient.post()
                    .uri(accessKeyEndpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, response) -> parseAccessKey(response, now));
        } catch (UpstreamFailureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UpstreamFailureException();
        }
    }

    private AccountClosureAlertSubmission parseSubmission(
            ClientHttpResponse response) throws IOException {
        JsonNode root = readSuccessfulJson(response);
        if (root.path("code").asInt(-1) != 200 || !root.path("data").isTextual()) {
            throw new UpstreamFailureException();
        }
        return new AccountClosureAlertSubmission(root.path("data").textValue());
    }

    private String parseExistingSubmission(
            ClientHttpResponse response,
            String expectedTitle) throws IOException {
        int httpStatus = response.getStatusCode().value();
        if (httpStatus == 401 || httpStatus == 403) {
            throw new PushPlusAccessKeyRejectedException();
        }
        JsonNode root = readSuccessfulJson(response);
        JsonNode messages = root.path("data").path("list");
        if (root.path("code").asInt(-1) != 200 || !messages.isArray()) {
            throw new UpstreamFailureException();
        }
        String recoveredReference = null;
        for (JsonNode message : messages) {
            if (!expectedTitle.equals(message.path("title").asText())) {
                continue;
            }
            String candidate = new AccountClosureAlertSubmission(
                    message.path("shortCode").asText("")).providerReference();
            if (recoveredReference != null && !recoveredReference.equals(candidate)) {
                throw new UpstreamFailureException();
            }
            recoveredReference = candidate;
        }
        return recoveredReference;
    }

    private AccessKeyState parseAccessKey(
            ClientHttpResponse response,
            Instant now) throws IOException {
        JsonNode root = readSuccessfulJson(response);
        JsonNode data = root.path("data");
        String accessKey = data.path("accessKey").asText("");
        long expiresIn = data.path("expiresIn").asLong(-1L);
        if (root.path("code").asInt(-1) != 200
                || accessKey.length() < 32
                || accessKey.length() > 256
                || accessKey.chars().anyMatch(character ->
                        character <= 0x20 || character > 0x7E)
                || expiresIn < 60L
                || expiresIn > 86_400L) {
            throw new UpstreamFailureException();
        }
        return new AccessKeyState(accessKey, now.plusSeconds(expiresIn));
    }

    private AccountClosureAlertDeliveryVerification parseVerification(
            ClientHttpResponse response,
            String providerReference) throws IOException {
        int httpStatus = response.getStatusCode().value();
        if (httpStatus == 401 || httpStatus == 403) {
            throw new PushPlusAccessKeyRejectedException();
        }
        JsonNode root = readSuccessfulJson(response);
        int status = root.path("data").path("status").asInt(-1);
        if (root.path("code").asInt(-1) != 200 || status < 0 || status > 3) {
            throw new UpstreamFailureException();
        }
        if (status == 2) {
            byte[] proof = ("pushplus-app-delivered:" + providerReference).getBytes(UTF_8);
            return new AccountClosureAlertDeliveryVerification(
                    AccountClosureAlertVerificationStatus.DELIVERED, proof);
        }
        if (status == 3) {
            return new AccountClosureAlertDeliveryVerification(
                    AccountClosureAlertVerificationStatus.FAILED, null);
        }
        return new AccountClosureAlertDeliveryVerification(
                AccountClosureAlertVerificationStatus.PENDING, null);
    }

    private JsonNode readSuccessfulJson(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new UpstreamFailureException();
        }
        try (InputStream responseBody = response.getBody()) {
            byte[] boundedBody = responseBody.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            if (boundedBody.length == 0 || boundedBody.length > MAXIMUM_RESPONSE_BYTES) {
                throw new UpstreamFailureException();
            }
            return objectMapper.readTree(boundedBody);
        }
    }

    private String title(java.util.UUID deliveryId) {
        return "AI好友系统告警（" + deliveryId + "）";
    }

    private String content(AccountClosureAlertDelivery delivery) {
        StringBuilder builder = new StringBuilder(256)
                .append("事件编号：").append(delivery.deliveryId()).append('\n')
                .append("责任范围：").append(audienceText(delivery.audience())).append('\n')
                .append("告警类型：").append(typeText(delivery.type())).append('\n')
                .append("发生时间：").append(delivery.occurredAt()).append('\n');
        if (delivery.acknowledgementDueAt() != null) {
            builder.append("接手截止：")
                    .append(delivery.acknowledgementDueAt()).append('\n');
        }
        if (operationsTotpProperties.enabled()
                && delivery.audience() == AccountClosureAlertAudience.ON_CALL
                && delivery.type() == AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED) {
            builder.append("确认接手：")
                    .append(operationsTotpProperties.acknowledgementUri(delivery.deliveryId()))
                    .append('\n');
        }
        return builder.append("请登录服务器核查匿名注销作业状态。此通知不含用户信息。")
                .toString();
    }

    private String audienceText(AccountClosureAlertAudience audience) {
        return switch (audience) {
            case ON_CALL -> "值班处置";
            case PRIVACY_OFFICER -> "隐私处置";
        };
    }

    private String typeText(AccountClosureAlertType type) {
        return switch (type) {
            case ACCOUNT_CLOSURE_DELAY_WARNING -> "注销处理超过二十四小时";
            case ACCOUNT_CLOSURE_P0_OPENED -> "注销处理超过四十八小时";
            case ACCOUNT_CLOSURE_P0_ESCALATED -> "高优先级告警未按时接手";
            case ACCOUNT_CLOSURE_DEADLINE_BREACHED -> "注销处理超过七十二小时";
        };
    }

    private record PushPlusSendRequest(
            String token,
            String title,
            String content,
            String template,
            String channel,
            long timestamp) {
    }

    private record PushPlusAccessKeyRequest(String token, String secretKey) {
    }

    private record PushPlusMessageListRequest(int current, int pageSize) {
    }

    private record AccessKeyState(String value, Instant expiresAt) {
    }
}

/**
 * PushPlus AccessKey 被明确拒绝的内部刷新信号。
 */
class PushPlusAccessKeyRejectedException extends RuntimeException {
}