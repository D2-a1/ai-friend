package com.aifriend.retrieval.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 独立评测配置；必须先批准公开合成资料外发，不回退到生产/旧任务环境变量。 */
record KnowledgeLiveEvaluationConfig(String runId, String profileLabel, KnowledgeChatProperties chat,
        KnowledgeEmbeddingProperties embedding, int maxChatRequests, int maxEmbeddingRequests) {
    static final String PREFIX = "AI_FRIEND_KNOWLEDGE_EVAL_";

    static KnowledgeLiveEvaluationConfig load(Function<String, String> environment) {
        if (!"true".equals(environment.apply(PREFIX + "ENABLED"))
                || !"true".equals(environment.apply(PREFIX + "PUBLIC_DATA_APPROVED"))) {
            throw new IllegalStateException("LIVE_EVALUATION_NOT_AUTHORIZED");
        }
        try {
            String run = required(environment, "RUN_ID");
            String label = required(environment, "PROFILE_LABEL");
            if (!run.matches("[a-z0-9][a-z0-9._-]{0,63}") || !Set.of("A", "B").contains(label)) {
                throw new IllegalArgumentException();
            }
            String temperature = required(environment, "CHAT_TEMPERATURE");
            var chat = new KnowledgeChatProperties(true, URI.create(required(environment, "CHAT_ENDPOINT")),
                    hosts(environment, "CHAT_ALLOWED_HOSTS"), required(environment, "CHAT_MODEL"), required(environment, "CHAT_API_KEY"),
                    required(environment, "CHAT_PROFILE_ID"), "evaluation-pending-review",
                    KnowledgeChatProperties.TokenLimitField.valueOf(required(environment, "CHAT_TOKEN_LIMIT_FIELD")),
                    integer(environment, "CHAT_MAX_OUTPUT_TOKENS"), KnowledgeChatProperties.ThinkingMode.valueOf(required(environment, "CHAT_THINKING_MODE")),
                    temperature.equals("OMIT") ? null : Double.valueOf(temperature),
                    millis(environment, "CHAT_CONNECT_TIMEOUT_MS"), millis(environment, "CHAT_READ_TIMEOUT_MS"));
            var embedding = new KnowledgeEmbeddingProperties(true, URI.create(required(environment, "EMBEDDING_ENDPOINT")),
                    hosts(environment, "EMBEDDING_ALLOWED_HOSTS"), required(environment, "EMBEDDING_MODEL"), required(environment, "EMBEDDING_API_KEY"),
                    integer(environment, "EMBEDDING_DIMENSION"), required(environment, "EMBEDDING_PROFILE_ID"),
                    integer(environment, "EMBEDDING_BATCH_SIZE"), millis(environment, "EMBEDDING_CONNECT_TIMEOUT_MS"), millis(environment, "EMBEDDING_READ_TIMEOUT_MS"));
            int chatLimit = integer(environment, "MAX_CHAT_REQUESTS");
            int embeddingLimit = integer(environment, "MAX_EMBEDDING_REQUESTS");
            if (chatLimit < 1 || chatLimit > 56 || embeddingLimit < 1 || embeddingLimit > 128) { throw new IllegalArgumentException(); }
            return new KnowledgeLiveEvaluationConfig(run, label, chat, embedding, chatLimit, embeddingLimit);
        } catch (RuntimeException invalid) {
            // URI/enum/number异常常携带原始输入，禁止透传原因或配置值。
            throw new IllegalArgumentException("INVALID_LIVE_EVALUATION_CONFIGURATION");
        }
    }
    private static String required(Function<String, String> environment, String name) {
        String value = environment.apply(PREFIX + name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(); }
        return value;
    }
    private static int integer(Function<String, String> environment, String name) { return Integer.parseInt(required(environment, name)); }
    private static Duration millis(Function<String, String> environment, String name) { return Duration.ofMillis(integer(environment, name)); }
    private static Set<String> hosts(Function<String, String> environment, String name) {
        return Arrays.stream(required(environment, name).split(",", -1)).map(String::trim).collect(Collectors.toSet());
    }
    @Override public String toString() { return "KnowledgeLiveEvaluationConfig[values=redacted]"; }
}
