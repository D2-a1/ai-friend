package com.aifriend.retrieval.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aifriend.retrieval.application.KnowledgeRepositoryPort;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.KnowledgeDocument;
import com.aifriend.retrieval.domain.RetrievalEvidence;
import com.aifriend.retrieval.domain.RetrievalQuery;

/**
 * 小规模不可变BM25索引；N/df/avgdl只对匹配语言及App版本的片段计算。
 * @author codex
 * @since 1.0.0
 */
public final class Bm25KeywordIndex {
    private final KnowledgeTokenizer tokenizer;
    private final List<Entry> entries;
    private final double k1;
    private final double b;

    /**
     * 构建词法快照，分词版本不一致拒绝；不依赖Embedding可用性。
     * @param snapshot 完整权威快照
     * @param tokenizer 共同分词口径
     * @param k1 BM25饱和参数，初始1.2
     * @param b 文长归一参数，初始0.75
     * @param maxEstimatedBytes 本次构建可用保守预算，最多128MiB，非实测堆峰值
     */
    public Bm25KeywordIndex(KnowledgeRepositoryPort.Snapshot snapshot, KnowledgeTokenizer tokenizer,
            double k1, double b, long maxEstimatedBytes) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        if (!snapshot.version().tokenizerVersion().equals(KnowledgeTokenizer.VERSION)
                || !Double.isFinite(k1) || k1 <= 0 || k1 > 3
                || !Double.isFinite(b) || b < 0 || b > 1
                || maxEstimatedBytes < 1 || maxEstimatedBytes > 128L * 1024 * 1024) {
            throw new IllegalArgumentException("INVALID_KEYWORD_CONFIGURATION");
        }
        this.k1 = k1;
        this.b = b;
        var documents = new HashMap<java.util.UUID, KnowledgeDocument>();
        long estimate = 0;
        for (var document : snapshot.documents()) {
            documents.put(document.id(), document);
            estimate += 256L + document.text().length() * 4L;
        }
        if (estimate > maxEstimatedBytes) {
            throw new IllegalArgumentException("RESOURCE_LIMIT");
        }
        var built = new ArrayList<Entry>();
        for (var chunk : snapshot.chunks()) {
            List<String> tokens = tokenizer.tokenize(chunk.text());
            var frequencies = new HashMap<String, Integer>();
            tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
            // 索引只保留每个不同词的键和tf；重复token列表仅在当前片段构建时存活。
            // 分别约束保留内存和当前构建峰值，不能将全部历史临时token累加成驻留对象。
            long addition = 256L + chunk.text().length() * 4L;
            for (var term : frequencies.keySet()) { addition += 192L + term.length() * 4L; }
            long temporary = 256L + chunk.text().length() * 8L;
            for (var token : tokens) { temporary += 96L + token.length() * 4L; }
            temporary += frequencies.size() * 96L; // mutable map与不可变副本短暂共存。
            if (estimate + addition + temporary > maxEstimatedBytes) {
                throw new IllegalArgumentException("RESOURCE_LIMIT");
            }
            estimate += addition;
            built.add(new Entry(chunk, documents.get(chunk.documentId()), Map.copyOf(frequencies), tokens.size()));
        }
        this.entries = List.copyOf(built);
    }

    /**
     * 对去重查询词求BM25和，仅输出有词级锚点的正分；相同分按UUID字符串稳定排序。
     * 多字汉语查询至少命中相邻双字或完整ASCII词，不能只因“的”等单字召回。
     * @param query 已校验公开查询
     * @param topK 单路召回数量，1至20
     * @return 不可变候选列表
     */
    public List<RetrievalEvidence> search(RetrievalQuery query, int topK) {
        Objects.requireNonNull(query, "query");
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("INPUT_LIMIT");
        }
        // TreeSet固定求和顺序，避免HashSet遍历导致浮点并列不稳定。
        var terms = new java.util.TreeSet<>(tokenizer.tokenize(query.text()));
        // 分数只表示排序，不证明问题有依据。保留单字查询能力，但多字查询不能靠单字重合。
        var anchors = terms.stream().filter(term -> term.codePointCount(0, term.length()) > 1
                || term.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN))
                .collect(java.util.stream.Collectors.toSet());
        if (anchors.isEmpty() && terms.size() == 1) { anchors = java.util.Set.copyOf(terms); }
        var eligible = entries.stream()
                .filter(entry -> entry.document().appliesTo(query.locale(), query.appVersionCode())).toList();
        if (terms.isEmpty() || eligible.isEmpty()) {
            return List.of();
        }
        double average = eligible.stream().mapToInt(Entry::length).average().orElse(0);
        if (average == 0) {
            return List.of();
        }
        var documentFrequencies = new HashMap<String, Integer>();
        for (var entry : eligible) {
            for (var term : terms) {
                if (entry.frequencies().containsKey(term)) { documentFrequencies.merge(term, 1, Integer::sum); }
            }
        }
        var scores = new ArrayList<RetrievalEvidence>();
        int total = eligible.size();
        for (var entry : eligible) {
            if (anchors.stream().noneMatch(entry.frequencies()::containsKey)) { continue; }
            double score = 0;
            for (var term : terms) {
                int tf = entry.frequencies().getOrDefault(term, 0);
                if (tf == 0) { continue; }
                int df = documentFrequencies.get(term);
                double idf = Math.log1p((total - df + 0.5) / (df + 0.5));
                score += idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * entry.length() / average));
            }
            if (score > 0) { scores.add(new RetrievalEvidence(entry.chunk(), score)); }
        }
        return scores.stream().sorted(Comparator.comparingDouble(RetrievalEvidence::score).reversed()
                .thenComparing(item -> item.chunk().id().toString())).limit(topK).toList();
    }

    private record Entry(KnowledgeChunk chunk, KnowledgeDocument document, Map<String, Integer> frequencies, int length) { }
}
