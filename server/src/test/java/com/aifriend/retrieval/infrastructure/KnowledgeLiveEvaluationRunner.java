package com.aifriend.retrieval.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.application.KnowledgeAnswerService;
import com.aifriend.assistant.domain.AssistantAnswer;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 仅固定公开合成语料的真实管线评测；模型端口可注入测试替身，不引入业务数据库/联系执行。 */
final class KnowledgeLiveEvaluationRunner {
    static final String CASES_SHA = "e2a775e5b7d47dbb84b1c9a03248c710ff9df7a949528404b09f998d2f8cab6b";
    static final String CORPUS_SHA = "0d890c3fda1545c7da5276e7af0fc37dd975c75237f028d9c575e9177e726784";
    private static final UUID OWNER = id("synthetic-evaluation-owner");

    Map<String, Object> run(KnowledgeLiveEvaluationConfig config, EmbeddingPort embedding, KnowledgeAnswerGenerationPort generation) throws Exception {
        var cases = rows("cases-v1.jsonl", CASES_SHA).stream()
                .filter(row -> Set.of("ANSWERABLE", "NO_EVIDENCE").contains(row.path("category").asText())).toList();
        var corpus = rows("corpus-v1.jsonl", CORPUS_SHA);
        if (cases.size() != 28 || corpus.size() != 10 || corpus.stream().anyMatch(row -> !row.path("synthetic").asBoolean())) {
            throw new IllegalStateException("UNEXPECTED_PUBLIC_EVALUATION_DATA");
        }
        var chunker = new KnowledgeChunker(400, 60, 600);
        var documents = corpus.stream().map(row -> new KnowledgeDocument(id(row.get("id").asText()), row.get("id").asText(),
                1, row.get("title").asText(), row.get("locale").asText(), row.get("minAppVersion").asInt(),
                row.get("maxAppVersion").asInt(), row.get("text").asText())).toList();
        var chunks = documents.stream().flatMap(document -> chunker.chunk(document).stream()).toList();
        var profile = config.embedding().profile();
        var snapshot = new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id("synthetic-live-generation"), 1,
                Optional.of(profile), KnowledgeTokenizer.VERSION, chunker.version()), documents, chunks);
        var values = new HashMap<UUID, float[]>();
        for (int from = 0; from < chunks.size(); from += config.embedding().batchSize()) {
            var batch = chunks.subList(from, Math.min(chunks.size(), from + config.embedding().batchSize()));
            var result = embedding.embed(profile, batch.stream().map(KnowledgeChunk::text).toList(), Duration.ofSeconds(8));
            if (!result.profile().equals(profile) || result.size() != batch.size()) { throw new IllegalStateException("INVALID_EVALUATION_EMBEDDING_BATCH"); }
            for (int i = 0; i < batch.size(); i++) { values.put(batch.get(i).id(), result.vector(i)); }
        }
        KnowledgeRepositoryPort repository = new KnowledgeRepositoryPort() {
            @Override public Optional<Snapshot> readActive() { return Optional.of(snapshot); }
            @Override public boolean isCurrent(IndexVersion version, List<KnowledgeChunk> checked) {
                return snapshot.version().equals(version) && snapshot.chunks().containsAll(checked);
            }
        };
        KnowledgeVectorRepositoryPort vectors = version -> {
            if (!snapshot.version().equals(version)) { throw new IllegalStateException("STALE_EVALUATION_INDEX"); }
            var copy = new HashMap<UUID, float[]>(); values.forEach((id, value) -> copy.put(id, value.clone())); return Map.copyOf(copy);
        };
        var search = new LocalKnowledgeSearchAdapter(vectors, 1.2, .75, 128L * 1024 * 1024);
        var fusion = new RrfFusion(60);
        var validDrafts = new AtomicInteger(); var protocolErrors = new AtomicInteger(); var generationAttempts = new AtomicInteger();
        KnowledgeAnswerGenerationPort measured = new KnowledgeAnswerGenerationPort() {
            @Override public String profileId() { return generation.profileId(); }
            @Override public KnowledgeAnswerDraft generate(String question, RetrievalResult evidence, boolean repair, Duration budget) {
                generationAttempts.incrementAndGet();
                try { var draft = generation.generate(question, evidence, repair, budget); validDrafts.incrementAndGet(); return draft; }
                catch (KnowledgeGatewayException failure) { if (failure.kind() == KnowledgeGatewayException.Kind.PROTOCOL) { protocolErrors.incrementAndGet(); } throw failure; }
            }
        };
        var consents = new ConsentGrantQueryPort() {
            @Override public boolean isGranted(UUID owner, ConsentType type) { return OWNER.equals(owner) && type == ConsentType.KNOWLEDGE_MODEL; }
            @Override public boolean isGrantedForPolicy(UUID owner, ConsentType type, String policy) {
                return isGranted(owner, type) && policy.equals("knowledge-model-v1");
            }
        };
        var reservations = new HashSet<KnowledgeQuotaPort.Reservation>();
        var service = new KnowledgeAnswerService(repository, search, fusion, Optional.of(embedding), Optional.of(measured),
                reservation -> reservations.add(reservation) ? KnowledgeQuotaPort.Decision.GRANTED : KnowledgeQuotaPort.Decision.DUPLICATE,
                new KnowledgeAccessPolicy(consents), new KnowledgeAnswerService.Settings(AssistantAnswer.Mode.GENERATED, Duration.ofSeconds(4)), Clock.systemUTC());
        var samples = new ArrayList<Map<String, Object>>();
        int keywordHits = 0, vectorHits = 0, rrfHits = 0, diagnosticFailures = 0;
        var latency = new long[28]; var successful = new boolean[28];
        for (int index = 0; index < cases.size(); index++) {
            var sample = cases.get(index); String question = sample.get("input").asText();
            var query = new RetrievalQuery(question, "zh-CN", 1, 4, RetrievalQuery.ExternalProcessing.ALLOWED);
            if (sample.get("category").asText().equals("ANSWERABLE")) {
                var expected = id(sample.get("evidenceSource").asText());
                var keyword = search.keyword(snapshot, query, 20).stream().map(RetrievalEvidence::chunk).toList();
                if (hasSource(keyword.stream().limit(4).toList(), expected)) { keywordHits++; }
                List<KnowledgeChunk> vector = List.of();
                try {
                    var embedded = embedding.embed(profile, List.of(question), Duration.ofSeconds(2));
                    if (!embedded.profile().equals(profile) || embedded.size() != 1) { throw new IllegalArgumentException(); }
                    vector = search.vector(snapshot, query, profile, embedded.vector(0), 20).stream()
                            .filter(hit -> hit.similarity() > 0).map(KnowledgeSearchPort.VectorHit::chunk).toList();
                    if (hasSource(vector.stream().limit(4).toList(), expected)) { vectorHits++; }
                } catch (RuntimeException unavailable) { diagnosticFailures++; }
                if (fusion.fuse(keyword, vector, 4).stream().anyMatch(hit -> hit.chunk().documentId().equals(expected))) { rrfHits++; }
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", sample.get("id").asText()); row.put("category", sample.get("category").asText());
            row.put("publicSyntheticQuestion", question); row.put("humanEvidenceSupport", "PENDING_MANUAL_REVIEW");
            long started = System.nanoTime();
            try {
                var result = service.answerWithEvidence(new KnowledgeAnswerService.Request(OWNER, id("run-" + config.runId() + "-" + index), query,
                        Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(8), Optional.of(config.chat().profileId())));
                var answer = result.answer();
                successful[index] = answer.status() != AssistantAnswer.Status.UNAVAILABLE;
                row.put("status", answer.status().name()); row.put("mode", answer.mode().name()); row.put("reason", answer.reason().name());
                row.put("retrievalMode", result.retrievalMode().map(Enum::name).orElse("NONE"));
                row.put("untrustedGeneratedOrExtractedText", answer.text());
                row.put("citations", answer.citations().stream().map(hit -> Map.of("publicSourceId",
                        documents.stream().filter(doc -> doc.id().equals(hit.chunk().documentId())).findFirst().orElseThrow().sourceKey(),
                        "evidenceText", hit.chunk().text())).toList());
                row.put("citationsExist", answer.citations().stream().allMatch(hit -> chunks.contains(hit.chunk())));
                row.put("hasCitations", !answer.citations().isEmpty());
                row.put("requiredFactVerbatimPresentNotCorrectness", sample.has("requiredFact") && answer.text().contains(sample.get("requiredFact").asText()));
                row.put("requiredFactForHumanReview", sample.path("requiredFact").asText());
                if (!answer.candidates().isEmpty()) { throw new IllegalStateException("PRIVATE_EVALUATION_RESULT_FORBIDDEN"); }
            } catch (RuntimeException failure) {
                successful[index] = false; row.put("status", "UNAVAILABLE"); row.put("mode", "NONE");
                row.put("failureCode", failure instanceof KnowledgeGatewayException gateway ? gateway.kind().name() : "CONTROLLED_EVALUATION_FAILURE");
            } finally { latency[index] = System.nanoTime() - started; }
            samples.add(row);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "knowledge-live-evaluation-v1"); report.put("runId", config.runId()); report.put("profileLabel", config.profileLabel());
        report.put("scope", "PUBLIC_SYNTHETIC_REGRESSION_NOT_RELEASE_APPROVAL");
        report.put("qualityDecision", "PENDING_MANUAL_REVIEW_NOT_AUTOMATIC_PASS");
        report.put("casesSha256", CASES_SHA); report.put("corpusSha256", CORPUS_SHA);
        report.put("embeddingConfigurationFingerprint", fingerprint(config.embedding().endpoint().toASCIIString(), config.embedding().model(),
                profile.id(), String.valueOf(profile.dimension())));
        report.put("chatConfigurationFingerprint", fingerprint(config.chat().endpoint().toASCIIString(), config.chat().model(), config.chat().profileId(),
                config.chat().tokenLimitField().name(), String.valueOf(config.chat().maxOutputTokens()), config.chat().thinkingMode().name(),
                String.valueOf(config.chat().temperature()), config.chat().connectTimeout().toString(), config.chat().readTimeout().toString()));
        report.put("recallAt4", Map.of("denominator", 20, "bm25Hits", keywordHits, "vectorHits", vectorHits,
                "rrfHits", rrfHits, "diagnosticFailuresCountAsMisses", diagnosticFailures));
        report.put("generationProtocol", Map.of("attempts", generationAttempts.get(), "validDrafts", validDrafts.get(), "protocolErrors", protocolErrors.get()));
        report.put("serviceLatencyExcludingIndexBuildAndRecallDiagnostics", KnowledgePerformanceMeasurements.summarize(latency, successful, Duration.ofSeconds(8).toNanos()));
        report.put("separateDiagnosticEmbeddingQueries", 20); report.put("realDatabaseUsed", false);
        report.put("samples", samples);
        return report;
    }
    static List<JsonNode> rows(String file, String expectedSha) throws Exception {
        try (var input = KnowledgeLiveEvaluationRunner.class.getResourceAsStream("/knowledge-acceptance/" + file)) {
            if (input == null) { throw new IllegalStateException("MISSING_FROZEN_EVALUATION_DATA"); }
            byte[] bytes = input.readNBytes(131073);
            if (bytes.length > 131072 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expectedSha)) {
                throw new IllegalStateException("FROZEN_EVALUATION_DATA_CHANGED");
            }
            var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var result = new ArrayList<JsonNode>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) { result.add(mapper.readTree(line)); }
            return List.copyOf(result);
        }
    }
    private static boolean hasSource(List<KnowledgeChunk> chunks, UUID source) { return chunks.stream().anyMatch(chunk -> chunk.documentId().equals(source)); }
    static String fingerprint(String... values) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(values)));
    }
    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
}
