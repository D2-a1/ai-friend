package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

/** 独立串行评测的硬请求限额及供应商usage观测；未知用量不是0，不参与业务授权。 */
final class KnowledgeEvaluationTransport implements KnowledgeModelTransport {
    private final KnowledgeModelTransport delegate;
    private final int maximum;
    private int attempts, responsesWithUsage;
    private long promptTokens, completionTokens;
    private final boolean chat;
    KnowledgeEvaluationTransport(KnowledgeModelTransport delegate, int maximum, boolean chat) {
        if (maximum < 1 || maximum > 128) { throw new IllegalArgumentException("INVALID_EVALUATION_LIMIT"); }
        this.delegate = java.util.Objects.requireNonNull(delegate); this.maximum = maximum; this.chat = chat;
    }
    @Override public synchronized byte[] post(byte[] payload, Duration budget) {
        if (attempts >= maximum) { throw new KnowledgeGatewayException(KnowledgeGatewayException.Kind.BUSY); }
        attempts++; // 失败、401和超时同样消耗一次，不盲目退款。
        byte[] response = delegate.post(payload, budget);
        try {
            var usage = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(response).path("usage");
            var input = usage.path("prompt_tokens"); var output = usage.path("completion_tokens");
            if (input.isIntegralNumber() && input.canConvertToLong() && input.longValue() >= 0 && input.longValue() <= 1_000_000_000
                    && (!chat || (output.isIntegralNumber() && output.canConvertToLong() && output.longValue() >= 0 && output.longValue() <= 1_000_000_000))) {
                responsesWithUsage++; promptTokens += input.longValue(); completionTokens += chat ? output.longValue() : 0;
            }
        } catch (Exception ignored) { /* 协议由生产适配器判定；缺失usage只记未知，不输出原始响应。 */ }
        return response;
    }
    synchronized Map<String, Object> metrics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("requests", attempts); result.put("maximumRequests", maximum);
        result.put("responsesWithUsage", responsesWithUsage); result.put("requestsWithUnknownUsage", attempts - responsesWithUsage);
        result.put("reportedPromptTokens", promptTokens); result.put("reportedCompletionTokens", completionTokens);
        result.put("billedCost", null); result.put("costStatus", "REQUIRES_PROVIDER_BILLING_AND_CURRENT_PRICE_RECONCILIATION");
        return result;
    }
    @Override public void close() { delegate.close(); }
}
