package com.aifriend.retrieval.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.application.KnowledgeGatewayException.Kind;

/**
 * 复用现有Apache4生产依赖，但使用独立实例；DNS校验结果直接用于socket连接。
 * 禁重定向/自动重试/压缩，正常TLS主机验证不被替换。总期限覆盖慢速响应体。
 * @author codex
 * @since 1.0.0
 */
public final class PinnedModelHttpTransport implements KnowledgeModelTransport {
    private final URI endpoint;
    private final String apiKey;
    private final CloseableHttpClient client;
    private final ThreadPoolExecutor executor;
    private final int connectMillis;
    private final int readMillis;
    private final int maximumResponseBytes;

    /**
     * 创建生产传输，仅接受已启用并完成校验的配置。
     * @param properties 独立外置配置
     */
    public PinnedModelHttpTransport(KnowledgeEmbeddingProperties properties) {
        this(requireEnabled(properties).endpoint(), properties.apiKey(),
                HttpClients.custom().setDnsResolver(new SafeModelDnsResolver(properties.allowedHosts()))
                        .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                        .disableAuthCaching().disableContentCompression().setMaxConnTotal(4).setMaxConnPerRoute(4).build(),
                properties.connectTimeout(), properties.readTimeout());
    }

    /**
     * 知识问答专用实例，在读取响应时直接限制64KiB，不先分配向量响应上限。
     * @param properties 经校验且已开启的独立生成配置
     */
    public PinnedModelHttpTransport(KnowledgeChatProperties properties) {
        this(requireEnabled(properties).endpoint(), properties.apiKey(),
                HttpClients.custom().setDnsResolver(new SafeModelDnsResolver(properties.allowedHosts()))
                        .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                        .disableAuthCaching().disableContentCompression().setMaxConnTotal(4).setMaxConnPerRoute(4).build(),
                properties.connectTimeout(), properties.readTimeout(), 65536);
    }

    // 仅包内测试可注入本地HTTP桩；生产Bean始终走上面构造器。
    PinnedModelHttpTransport(URI endpoint, String apiKey, CloseableHttpClient client,
            Duration connectTimeout, Duration readTimeout) {
        this(endpoint, apiKey, client, connectTimeout, readTimeout, 8 * 1024 * 1024);
    }

    PinnedModelHttpTransport(URI endpoint, String apiKey, CloseableHttpClient client,
            Duration connectTimeout, Duration readTimeout, int maximumResponseBytes) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.client = client;
        this.connectMillis = Math.toIntExact(connectTimeout.toMillis());
        this.readMillis = Math.toIntExact(readTimeout.toMillis());
        if (maximumResponseBytes < 1 || maximumResponseBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("INVALID_MODEL_RESPONSE_LIMIT");
        }
        this.maximumResponseBytes = maximumResponseBytes;
        this.executor = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "knowledge-http");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static KnowledgeEmbeddingProperties requireEnabled(KnowledgeEmbeddingProperties properties) {
        if (properties == null || !properties.enabled()) { throw new KnowledgeGatewayException(Kind.CONFIGURATION); }
        return properties;
    }

    private static KnowledgeChatProperties requireEnabled(KnowledgeChatProperties properties) {
        if (properties == null || !properties.enabled()) { throw new KnowledgeGatewayException(Kind.CONFIGURATION); }
        return properties;
    }

    /** {@inheritDoc} */
    @Override public byte[] post(byte[] payload, Duration budget) {
        if (payload == null || payload.length == 0 || payload.length > 1024 * 1024
                || budget == null || budget.compareTo(Duration.ofMillis(1)) < 0
                || budget.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new KnowledgeGatewayException(Kind.CONFIGURATION);
        }
        long started = System.nanoTime();
        long nanos = budget.toNanos();
        int milliseconds = Math.max(1, Math.toIntExact(budget.toMillis()));
        HttpPost request = new HttpPost(endpoint);
        request.setHeader("Authorization", "Bearer " + apiKey);
        request.setHeader("Accept", "application/json");
        request.setHeader("Accept-Encoding", "identity");
        request.setEntity(new ByteArrayEntity(payload.clone(), ContentType.APPLICATION_JSON));
        request.setConfig(RequestConfig.custom().setConnectTimeout(Math.min(connectMillis, milliseconds))
                .setConnectionRequestTimeout(Math.min(connectMillis, milliseconds))
                .setSocketTimeout(Math.min(readMillis, milliseconds))
                .setRedirectsEnabled(false).build());
        java.util.concurrent.Future<byte[]> future;
        try {
            future = executor.submit(() -> {
                try (var response = client.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (status >= 300 && status < 400) { throw new KnowledgeGatewayException(Kind.ENDPOINT_REJECTED); }
                    if (status == 429 || status >= 500) { throw new KnowledgeGatewayException(Kind.TEMPORARY); }
                    if (status != 200) { throw new KnowledgeGatewayException(Kind.CONFIGURATION); }
                    var entity = response.getEntity();
                    var type = entity == null ? null : entity.getContentType();
                    var encoding = entity == null ? null : entity.getContentEncoding();
                    if (entity == null || type == null
                            || !"application/json".equalsIgnoreCase(type.getValue().split(";", 2)[0].strip())
                            || (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue()))
                            || entity.getContentLength() > maximumResponseBytes) {
                        throw new KnowledgeGatewayException(Kind.PROTOCOL);
                    }
                    try (var stream = entity.getContent()) {
                        byte[] body = stream.readNBytes(maximumResponseBytes + 1);
                        if (body.length == 0 || body.length > maximumResponseBytes) {
                            throw new KnowledgeGatewayException(Kind.PROTOCOL);
                        }
                        return body;
                    }
                } finally {
                    request.abort(); // 超限/异常体不被连接池自动排空，避免拖过总期限。
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            request.abort();
            throw new KnowledgeGatewayException(Kind.BUSY);
        }
        try {
            long remaining = nanos - (System.nanoTime() - started);
            if (remaining <= 0) { throw new TimeoutException(); }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KnowledgeGatewayException(Kind.TEMPORARY);
        } catch (TimeoutException exception) {
            throw new KnowledgeGatewayException(Kind.TEMPORARY);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof KnowledgeGatewayException known) { throw known; }
            if (cause instanceof java.net.UnknownHostException) { throw new KnowledgeGatewayException(Kind.ENDPOINT_REJECTED); }
            throw new KnowledgeGatewayException(Kind.TEMPORARY);
        } finally {
            request.abort();
            future.cancel(true);
        }
    }

    /** 仅关闭本功能创建的线程和连接。 */
    @Override public void close() {
        executor.shutdownNow();
        try { client.close(); } catch (java.io.IOException ignored) { /* 已禁止新请求，不重试收费调用。 */ }
    }
}
