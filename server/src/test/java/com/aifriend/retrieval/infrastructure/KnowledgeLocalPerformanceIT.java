package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.application.KnowledgeAnswerService;
import com.aifriend.assistant.domain.AssistantAnswer;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 显式离线性能专项：默认Surefire命名不运行IT；没有网络、实库、用户资料或生成模型。 */
class KnowledgeLocalPerformanceIT {
    private static final int SAMPLES = 100;
    private static final long MEMORY_BUDGET = 128L * 1024 * 1024;
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("synthetic-performance", 1024);
    private static final UUID OWNER = new UUID(0, 1);
    private enum Subject { RETRIEVAL, EXTRACTIVE_TEXT, GRAPH_QUERY }

    @Test void explicitLocalMatrixRecordsEveryRequestAndSeparateGroups() throws Exception {
        var fixture = new Fixture();
        var groups = new ArrayList<Group>();
        for (var subject : Subject.values()) {
            for (int concurrency : new int[] {1, 4}) {
                for (boolean warm : new boolean[] {false, true}) {
                    groups.add(measure(fixture, subject, concurrency, warm));
                }
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "knowledge-local-performance-v1");
        report.put("scope", "LOCAL_COMPONENT_SIMULATION_NOT_CLOUD_ACCEPTANCE");
        report.put("temperatureDefinition", "COLD_INSTANCE includes new service assembly per request; WARMED_SERVICE reuses one service after 20 warmups; neither means cold JVM or cold database");
        report.put("loadDefinition", "Closed-loop 1 or 4 workers; elapsed includes service creation for cold requests but not client queue; all failures enter percentiles");
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("chunksPerSnapshot", fixture.active.chunks().size());
        report.put("retainedSnapshots", 2);
        report.put("retainedVectorPayloadBytes", (long) 2 * 2000 * PROFILE.dimension() * Float.BYTES);
        report.put("graphContacts", 20);
        report.put("realDbConnections", 0);
        report.put("realExternalCalls", 0);
        report.put("groups", groups);
        var folder = Path.of("target", "knowledge-performance");
        if (Files.isSymbolicLink(Path.of("target")) || Files.isSymbolicLink(folder)) {
            throw new IllegalStateException("UNSAFE_REPORT_DIRECTORY");
        }
        Files.createDirectories(folder);
        var path = folder.resolve("local-" + UUID.randomUUID() + ".json");
        Files.write(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        System.out.println("LOCAL_PERFORMANCE_REPORT=" + path);
        assertThat(groups).hasSize(12);
        assertThat(groups).allSatisfy(group -> {
            assertThat(group.stats().samples()).isEqualTo(SAMPLES);
            assertThat(group.stats().failures()).as(group.subject() + "/" + group.temperature() + "/" + group.concurrency()).isZero();
            assertThat(group.stats().overDeadline()).isZero();
            assertThat(group.stats().p95Ms()).as(group.subject() + "/" + group.temperature() + "/" + group.concurrency())
                    .isLessThanOrEqualTo(group.targetP95Ms());
        });
        // 强引用保留双世代，不能让JIT将未用备用快照提前回收后声称双快照测量。
        java.lang.ref.Reference.reachabilityFence(fixture.standby);
        java.lang.ref.Reference.reachabilityFence(fixture.standbyVectors);
    }

    private Group measure(Fixture fixture, Subject subject, int concurrency, boolean warm) throws Exception {
        IntConsumer shared = fixture.operation(subject);
        if (warm) { for (int i = 0; i < 20; i++) { shared.accept(i); } }
        ManagementFactory.getMemoryPoolMXBeans().forEach(pool -> pool.resetPeakUsage());
        var nanos = new long[SAMPLES]; Arrays.fill(nanos, -1);
        var success = new boolean[SAMPLES];
        var failures = new ConcurrentHashMap<String, AtomicInteger>();
        var next = new AtomicInteger();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(concurrency);
        var tasks = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                tasks.add(executor.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                    for (int i = next.getAndIncrement(); i < SAMPLES; i = next.getAndIncrement()) {
                        long before = System.nanoTime();
                        try {
                            (warm ? shared : fixture.operation(subject)).accept(i);
                            success[i] = true;
                        } catch (RuntimeException | AssertionError failure) {
                            failures.computeIfAbsent(failure.getClass().getSimpleName(), unused -> new AtomicInteger()).incrementAndGet();
                        } finally { nanos[i] = System.nanoTime() - before; }
                    }
                }));
            }
            start.countDown();
            long groupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            for (var task : tasks) { task.get(Math.max(1, groupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        } finally {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) { throw new IllegalStateException("PERFORMANCE_WORKER_NOT_STOPPED"); }
        }
        long peakHeap = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        long committedVirtual = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getCommittedVirtualMemorySize() : -1;
        var stats = KnowledgePerformanceMeasurements.summarize(nanos, success, TimeUnit.SECONDS.toNanos(8));
        var errors = new TreeMap<String, Integer>(); failures.forEach((type, count) -> errors.put(type, count.get()));
        var group = new Group(subject.name(), warm ? "WARMED_SERVICE" : "COLD_INSTANCE", concurrency,
                warm ? 20 : 0, stats, subject == Subject.EXTRACTIVE_TEXT ? 8000 : 300, peakHeap,
                committedVirtual, errors);
        System.out.printf(Locale.ROOT, "LOCAL_PERF %s %s c=%d n=%d failed=%d p95Ms=%.3f heapPoolPeakSumBytes=%d%n",
                group.subject(), group.temperature(), concurrency, SAMPLES, stats.failures(), stats.p95Ms(), peakHeap);
        return group;
    }

    record Group(String subject, String temperature, int concurrency, int unmeasuredWarmups,
            KnowledgePerformanceMeasurements.Stats stats, double targetP95Ms, long heapPoolPeakSumBytes,
            long processCommittedVirtualBytesNotRss, Map<String, Integer> failureTypes) { }

    private static final class Fixture {
        final KnowledgeRepositoryPort.Snapshot active = corpus(1);
        final KnowledgeRepositoryPort.Snapshot standby = corpus(2);
        final Map<UUID, float[]> activeVectors = vectors(active);
        final Map<UUID, float[]> standbyVectors = vectors(standby);
        final KnowledgeRepositoryPort repository = new KnowledgeRepositoryPort() {
            @Override public Optional<Snapshot> readActive() { return Optional.of(active); }
            @Override public boolean isCurrent(IndexVersion version, List<KnowledgeChunk> chunks) {
                return active.version().equals(version) && active.chunks().containsAll(chunks);
            }
        };
        final KnowledgeVectorRepositoryPort vectorStore = version -> {
            if (!active.version().equals(version)) { throw new IllegalStateException("STALE_PERFORMANCE_INDEX"); }
            var copy = new HashMap<UUID, float[]>();
            activeVectors.forEach((id, vector) -> copy.put(id, vector.clone()));
            return Map.copyOf(copy);
        };
        final KnowledgeAccessPolicy access = new KnowledgeAccessPolicy(new ConsentGrantQueryPort() {
            @Override public boolean isGranted(UUID owner, ConsentType type) { return OWNER.equals(owner); }
            @Override public boolean isGrantedForPolicy(UUID owner, ConsentType type, String policy) {
                return OWNER.equals(owner) && type == ConsentType.CONTACT_GRAPH && policy.equals("contact-graph-v1");
            }
        });
        final AtomicInteger operations = new AtomicInteger();

        IntConsumer operation(Subject subject) {
            var search = new LocalKnowledgeSearchAdapter(vectorStore, 1.2, .75, MEMORY_BUDGET);
            if (subject == Subject.RETRIEVAL) {
                EmbeddingPort model = (profile, texts, remaining) -> {
                    if (!PROFILE.equals(profile)) { throw new IllegalArgumentException("WRONG_SIMULATED_PROFILE"); }
                    var vector = new float[PROFILE.dimension()]; vector[Integer.parseInt(texts.get(0).substring(5))] = 1;
                    return new EmbeddingBatch(PROFILE, new float[][] {vector});
                };
                var service = new HybridRetrievalService(repository, search, new RrfFusion(60), Optional.of(model), Duration.ofSeconds(2));
                return i -> {
                    var answer = service.retrieve(query(i, true));
                    require(answer.mode() == RetrievalResult.Mode.HYBRID && !answer.evidence().isEmpty()
                            && answer.evidence().get(0).chunk().documentId().equals(id(i + 100)), "BAD_RETRIEVAL");
                };
            }
            if (subject == Subject.EXTRACTIVE_TEXT) {
                var service = new KnowledgeAnswerService(repository, search, new RrfFusion(60), Optional.empty(), Optional.empty(),
                        reservation -> KnowledgeQuotaPort.Decision.GRANTED, access,
                        new KnowledgeAnswerService.Settings(AssistantAnswer.Mode.EXTRACTIVE, Duration.ofSeconds(4)), Clock.systemUTC());
                return i -> {
                    var answer = service.answer(new KnowledgeAnswerService.Request(OWNER, id(10_000 + operations.incrementAndGet()),
                            query(i, false), Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(8), Optional.empty()));
                    require(answer.status() == AssistantAnswer.Status.EVIDENCE_ONLY && !answer.citations().isEmpty()
                            && answer.citations().get(0).chunk().documentId().equals(id(i + 100)), "BAD_EXTRACT");
                };
            }
            return graphOperation();
        }

        private IntConsumer graphOperation() {
            var generation = id(800);
            var nodes = new ArrayList<GraphNode>(); var edges = new ArrayList<GraphEdge>();
            nodes.add(new GraphNode(OWNER, generation, id(3), GraphNode.Type.USER, OWNER, 1));
            for (int i = 0; i < 20; i++) {
                nodes.add(new GraphNode(OWNER, generation, id(100+i), GraphNode.Type.CONTACT, id(100+i), 1));
                nodes.add(new GraphNode(OWNER, generation, id(300+i), GraphNode.Type.ALIAS, id(300+i), 1));
                edges.add(new GraphEdge(OWNER, generation, id(500+i), id(3), id(100+i), GraphEdge.Type.HAS_CONTACT));
                edges.add(new GraphEdge(OWNER, generation, id(700+i), id(100+i), id(300+i), GraphEdge.Type.HAS_ALIAS));
            }
            var graph = new GraphSnapshot(OWNER, generation, "a".repeat(64), nodes, edges);
            ContactGraphSourcePort source = new ContactGraphSourcePort() {
                @Override public GraphSnapshot snapshot(UUID owner) { require(OWNER.equals(owner), "FOREIGN_OWNER"); return graph; }
                @Override public List<ContactDisplay> displayCurrent(UUID owner, String digest, List<UUID> ids) {
                    require(OWNER.equals(owner) && graph.sourceDigest().equals(digest), "STALE_GRAPH");
                    return ids.stream().map(id -> new ContactDisplay(id, 1, List.of("合成称呼" + id.getLeastSignificantBits()))).toList();
                }
            };
            KnowledgeGraphPort stored = new KnowledgeGraphPort() {
                @Override public Optional<Projection> findByOwner(UUID owner) { require(OWNER.equals(owner), "FOREIGN_OWNER"); return Optional.of(new Projection(graph, 1)); }
                @Override public Projection project(GraphSnapshot value, long version) { throw new AssertionError("PURE_GRAPH_QUERY_MUST_NOT_WRITE"); }
                @Override public boolean purge(UUID owner, long version) { throw new AssertionError("NO_DELETE_IN_PERFORMANCE_RUN"); }
            };
            var service = new ContactGraphQueryService(source, stored, access);
            return i -> require(service.query(new GraphQueryPort.Query(OWNER, GraphQueryType.LIST_CONTACTS,
                    Optional.empty(), Optional.empty())).candidates().size() == 20, "BAD_GRAPH_RESULT");
        }
    }

    private static KnowledgeRepositoryPort.Snapshot corpus(int version) {
        var chunker = new KnowledgeChunker(400, 0, 400);
        var documents = new ArrayList<KnowledgeDocument>(); var chunks = new ArrayList<KnowledgeChunk>();
        for (int i = 0; i < 100; i++) {
            var seed = "topic" + i + " 公开合成资料操作指南 ";
            var text = seed.repeat(8000 / seed.length() + 1).substring(0, 8000);
            var document = new KnowledgeDocument(id(i + 100), "perf-" + i, version, "合成指南", "zh-CN", 1, 10, text);
            documents.add(document); chunks.addAll(chunker.chunk(document));
        }
        require(chunks.size() == 2000, "WRONG_PERFORMANCE_CORPUS_SIZE");
        return new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(900 + version), version, Optional.of(PROFILE),
                KnowledgeTokenizer.VERSION, chunker.version()), documents, chunks);
    }
    private static Map<UUID, float[]> vectors(KnowledgeRepositoryPort.Snapshot snapshot) {
        var result = new HashMap<UUID, float[]>();
        snapshot.chunks().forEach(chunk -> { var vector = new float[PROFILE.dimension()];
            vector[(int) chunk.documentId().getLeastSignificantBits() - 100] = 1; result.put(chunk.id(), vector); });
        return Map.copyOf(result);
    }
    private static RetrievalQuery query(int i, boolean external) {
        return new RetrievalQuery("topic" + i, "zh-CN", 1, 4,
                external ? RetrievalQuery.ExternalProcessing.ALLOWED : RetrievalQuery.ExternalProcessing.LOCAL_ONLY);
    }
    private static UUID id(long i) { return new UUID(0, i); }
    private static void require(boolean valid, String reason) { if (!valid) { throw new IllegalStateException(reason); } }
}
