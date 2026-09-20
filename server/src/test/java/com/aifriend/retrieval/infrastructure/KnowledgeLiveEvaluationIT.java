package com.aifriend.retrieval.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

/** 必须显式选择且成组配置独立外发批准；本轮只编译，不运行此IT。 */
class KnowledgeLiveEvaluationIT {
    @Test void explicitlyAuthorizedPublicSyntheticModelReplay() throws Exception {
        var config = KnowledgeLiveEvaluationConfig.load(System::getenv);
        // 验证冻结输入后才构造真实连接执行器；不读取任何业务数据库或生产配置。
        KnowledgeLiveEvaluationRunner.rows("cases-v1.jsonl", KnowledgeLiveEvaluationRunner.CASES_SHA);
        KnowledgeLiveEvaluationRunner.rows("corpus-v1.jsonl", KnowledgeLiveEvaluationRunner.CORPUS_SHA);
        var mapper = new ObjectMapper();
        var folder = Path.of("target", "knowledge-live-evaluation");
        if (Files.isSymbolicLink(Path.of("target")) || Files.isSymbolicLink(folder)) { throw new IllegalStateException("UNSAFE_EVALUATION_REPORT_DIRECTORY"); }
        Files.createDirectories(folder);
        try (var chatTransport = new KnowledgeEvaluationTransport(new PinnedModelHttpTransport(config.chat()), config.maxChatRequests(), true);
             var embeddingTransport = new KnowledgeEvaluationTransport(new PinnedModelHttpTransport(config.embedding()), config.maxEmbeddingRequests(), false)) {
            var chat = new ChatCompletionsKnowledgeAnswerAdapter(config.chat(), chatTransport, mapper,
                    CircuitBreaker.ofDefaults("knowledge-eval-chat"), Bulkhead.ofDefaults("knowledge-eval-chat"));
            var embedding = new EmbeddingsHttpAdapter(config.embedding(), embeddingTransport, mapper,
                    CircuitBreaker.ofDefaults("knowledge-eval-embedding"), Bulkhead.ofDefaults("knowledge-eval-embedding"));
            var report = new LinkedHashMap<String, Object>();
            boolean completed = false;
            try { report.putAll(new KnowledgeLiveEvaluationRunner().run(config, embedding, chat)); completed = true; }
            catch (Exception failure) { report.put("schema", "knowledge-live-evaluation-v1"); report.put("runId", config.runId()); report.put("runStatus", "FAILED_NO_QUALITY_CLAIM"); }
            report.put("chatTransport", chatTransport.metrics()); report.put("embeddingTransport", embeddingTransport.metrics());
            report.put("qualityDecision", "PENDING_MANUAL_REVIEW_NOT_AUTOMATIC_PASS");
            if (Files.isSymbolicLink(Path.of("target")) || Files.isSymbolicLink(folder)) { throw new IllegalStateException("UNSAFE_EVALUATION_REPORT_DIRECTORY"); }
            var path = folder.resolve(config.runId() + "-" + UUID.randomUUID() + ".json");
            Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
            System.out.println("KNOWLEDGE_LIVE_REVIEW_REPORT=" + path);
            if (!completed) { throw new IllegalStateException("LIVE_EVALUATION_INCOMPLETE_SEE_REDACTED_REPORT"); }
        }
    }
}
