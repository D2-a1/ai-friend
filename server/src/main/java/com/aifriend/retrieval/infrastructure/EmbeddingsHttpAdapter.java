package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.aifriend.retrieval.application.EmbeddingPort;
import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.application.KnowledgeGatewayException.Kind;
import com.aifriend.retrieval.domain.EmbeddingBatch;
import com.aifriend.retrieval.domain.EmbeddingProfile;
import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 通用Embeddings JSON协议，严格校验整个批次，不自动重试或切换profile。
 * @author codex
 * @since 1.0.0
 */
public final class EmbeddingsHttpAdapter implements EmbeddingPort, AutoCloseable {
    private final KnowledgeEmbeddingProperties properties;
    private final KnowledgeModelTransport transport;
    private final ObjectMapper mapper;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    /**
     * 构造独立网关，不复用任务模型的熔断器、凭据或语法。
     * @param properties 已校验外置配置
     * @param transport 固定端点传输
     * @param mapper 基础JSON组件，复制后严格化
     * @param circuitBreaker 本功能专用熔断器
     * @param bulkhead 本功能专用舱壁
     */
    public EmbeddingsHttpAdapter(KnowledgeEmbeddingProperties properties, KnowledgeModelTransport transport,
            ObjectMapper mapper, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(12).maxNumberLength(64).maxStringLength(8192).build());
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
    }

    /** {@inheritDoc} */
    @Override public EmbeddingBatch embed(EmbeddingProfile profile, List<String> texts, Duration remainingBudget) {
        long started = System.nanoTime();
        if (!properties.enabled() || !properties.profile().equals(profile) || texts == null
                || texts.isEmpty() || texts.size() > properties.batchSize()
                || remainingBudget == null || remainingBudget.compareTo(Duration.ofMillis(1)) < 0
                || remainingBudget.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new KnowledgeGatewayException(Kind.CONFIGURATION);
        }
        final byte[] request;
        final int inputCount;
        try {
            List<String> input = List.copyOf(texts);
            inputCount = input.size();
            input.forEach(text -> KnowledgeText.require(text, 600, 2400, false));
            request = mapper.writeValueAsBytes(Map.of("model", properties.model(), "input", input, "encoding_format", "float"));
        } catch (Exception invalidInput) {
            throw new KnowledgeGatewayException(Kind.CONFIGURATION);
        }
        try {
            var batch = Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                long remaining = remainingBudget.toNanos() - (System.nanoTime() - started);
                if (remaining < 1_000_000) { throw new KnowledgeGatewayException(Kind.TEMPORARY); }
                return decode(transport.post(request, Duration.ofNanos(remaining)), profile, inputCount);
            })).get();
            if (System.nanoTime() - started >= remainingBudget.toNanos()) {
                throw new KnowledgeGatewayException(Kind.TEMPORARY);
            }
            return batch;
        } catch (BulkheadFullException | CallNotPermittedException unavailable) {
            throw new KnowledgeGatewayException(Kind.BUSY);
        }
    }

    private EmbeddingBatch decode(byte[] bytes, EmbeddingProfile profile, int expectedCount) {
        if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024) {
            throw new KnowledgeGatewayException(Kind.PROTOCOL);
        }
        try (var parser = mapper.createParser(bytes)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null || root == null || !root.isObject()
                    || !onlyFields(root, Set.of("object", "model", "data", "usage"))
                    || (root.has("object") && !"list".equals(root.get("object").asText()))
                    || (root.has("model") && (!root.get("model").isTextual()
                        || !properties.model().equals(root.get("model").textValue())))) {
                throw new KnowledgeGatewayException(Kind.PROTOCOL);
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != expectedCount) {
                throw new KnowledgeGatewayException(Kind.PROTOCOL);
            }
            var seen = new HashSet<Integer>();
            float[][] vectors = new float[expectedCount][];
            for (var row : data) {
                if (!row.isObject() || !onlyFields(row, Set.of("index", "embedding", "object"))
                        || (row.has("object") && !"embedding".equals(row.get("object").asText()))
                        || !row.has("index") || !row.get("index").isIntegralNumber()
                        || !row.get("index").canConvertToInt()) {
                    throw new KnowledgeGatewayException(Kind.PROTOCOL);
                }
                int index = row.get("index").intValue();
                JsonNode embedding = row.get("embedding");
                if (index < 0 || index >= expectedCount || !seen.add(index)
                        || embedding == null || !embedding.isArray() || embedding.size() != profile.dimension()) {
                    throw new KnowledgeGatewayException(Kind.PROTOCOL);
                }
                float[] vector = new float[profile.dimension()];
                for (int i = 0; i < vector.length; i++) {
                    if (!embedding.get(i).isNumber()) { throw new KnowledgeGatewayException(Kind.PROTOCOL); }
                    vector[i] = embedding.get(i).floatValue();
                }
                vectors[index] = vector;
            }
            return new EmbeddingBatch(profile, vectors);
        } catch (KnowledgeGatewayException expected) {
            throw expected;
        } catch (Exception invalidResponse) {
            throw new KnowledgeGatewayException(Kind.PROTOCOL);
        }
    }

    private boolean onlyFields(JsonNode node, Set<String> allowed) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) { return false; }
        }
        return true;
    }

    /** 关闭独立传输实例。 */
    @Override public void close() { transport.close(); }
}
