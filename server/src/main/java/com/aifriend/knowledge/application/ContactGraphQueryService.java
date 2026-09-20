package com.aifriend.knowledge.application;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphNode;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.knowledge.domain.GraphSnapshot;

/**
 * 本地有限关系查询：权威源、必要时重建、两跳遍历、只读候选和最终复验。
 *
 * <p>不理解或执行任意关系表达式；称呼文字匹配仅用于查询，不代替个人声学身份或联系确认。
 * 无外部模型、任务或微信端口。无长期缓存；所有返回（包括空结果）均重新核对来源。
 * @author Codex
 * @since 1.0.0
 */
public final class ContactGraphQueryService implements GraphQueryPort {
    private final ContactGraphSourcePort source;
    private final KnowledgeGraphPort projections;
    private final KnowledgeAccessPolicy access;

    /**
     * 创建本地查询服务。
     * @param source 当前owner权威来源
     * @param projections 按owner隔离的完整投影存储
     * @param access 独立图谱用途同意
     */
    public ContactGraphQueryService(ContactGraphSourcePort source, KnowledgeGraphPort projections,
            KnowledgeAccessPolicy access) {
        this.source = Objects.requireNonNull(source);
        this.projections = Objects.requireNonNull(projections);
        this.access = Objects.requireNonNull(access);
    }

    /** {@inheritDoc} */
    @Override public Result query(Query query) {
        Objects.requireNonNull(query);
        checkCancellation();
        UUID owner = query.ownerUserId();
        access.requireGraphConsent(owner);
        var facts = source.snapshot(owner);
        requireOwner(facts, owner);
        var existing = projections.findByOwner(owner);
        existing.ifPresent(value -> requireOwner(value.snapshot(), owner));
        KnowledgeGraphPort.Projection projection;
        if (existing.isEmpty() || !existing.get().snapshot().sourceDigest().equals(facts.sourceDigest())) {
            checkCancellation();
            // 一次有限重建；CAS冲突/未知提交结果不在本次请求盲重试。
            projection = projections.project(facts, existing.map(KnowledgeGraphPort.Projection::version).orElse(0L));
        } else {
            projection = existing.get();
        }
        requireSameFacts(facts, projection.snapshot());
        var contacts = contactsByTraversal(projection.snapshot());
        List<UUID> ids;
        if (query.type() == GraphQueryType.LIST_ALIASES) {
            UUID requested = query.contactId().orElseThrow();
            ids = contacts.containsKey(requested) ? List.of(requested) : List.of();
        } else {
            ids = contacts.keySet().stream().sorted().toList();
        }
        if (query.type() != GraphQueryType.FIND_CONTACT_BY_ALIAS && ids.size() > 20) {
            throw new GraphSourceException(GraphSourceException.Kind.GRAPH_LIMIT);
        }
        var candidates = new ArrayList<ContactDisplay>();
        GraphSnapshot lastFreshFacts = null;
        // FIND须扫描完整图，不能只找前20个联系人后将后续同名称呼漏掉。
        for (int start = 0; start < ids.size(); start += 20) {
            checkCancellation();
            List<UUID> batchIds = ids.subList(start, Math.min(start + 20, ids.size()));
            if (batchIds.stream().anyMatch(id -> contacts.get(id).aliases > 5)) {
                throw new GraphSourceException(GraphSourceException.Kind.GRAPH_LIMIT);
            }
            List<ContactDisplay> batch;
            if (source instanceof ContactGraphEvidencePort detailed) {
                var evidence = detailed.displayEvidence(owner, facts.sourceDigest(), batchIds);
                requireSameFacts(facts, evidence.before());
                requireSameFacts(facts, evidence.after());
                batch = evidence.displays();
                lastFreshFacts = evidence.after();
            } else {
                batch = source.displayCurrent(owner, facts.sourceDigest(), batchIds);
            }
            requireCompleteBatch(batch, batchIds, contacts);
            for (var display : batch) {
                if (query.type() != GraphQueryType.FIND_CONTACT_BY_ALIAS || display.aliases().stream()
                        .anyMatch(alias -> normalize(alias).equals(normalize(query.alias().orElseThrow())))) {
                    candidates.add(display);
                    if (candidates.size() > 20) { throw new GraphSourceException(GraphSourceException.Kind.GRAPH_LIMIT); }
                }
            }
        }
        checkCancellation();
        // 最后一批解密后的新事务已覆盖此前全部批次；无批次或基础端口仍须另读新快照。
        var current = lastFreshFacts != null ? lastFreshFacts : source.snapshot(owner);
        requireSameFacts(facts, current);
        access.requireGraphConsent(owner);
        checkCancellation();
        return new Result(facts.sourceDigest(), candidates,
                query.type() == GraphQueryType.FIND_CONTACT_BY_ALIAS && candidates.size() > 1);
    }

    private static Map<UUID, ContactFact> contactsByTraversal(GraphSnapshot graph) {
        var nodes = graph.nodes().stream().collect(Collectors.toMap(GraphNode::id, node -> node));
        var outgoing = graph.edges().stream().collect(Collectors.groupingBy(edge -> edge.fromId()));
        var root = graph.nodes().stream().filter(node -> node.type() == GraphNode.Type.USER).findFirst().orElseThrow();
        var pending = new ArrayDeque<Step>();
        pending.add(new Step(root.id(), 0));
        var seen = new HashSet<UUID>();
        var contacts = new HashMap<UUID, ContactFact>();
        while (!pending.isEmpty()) {
            var step = pending.removeFirst();
            if (step.depth > 2 || !seen.add(step.node) || seen.size() > 200) { invalidProjection(); }
            var node = nodes.get(step.node);
            if (node == null) { invalidProjection(); }
            if (node.type() == GraphNode.Type.CONTACT) {
                contacts.put(node.sourceId(), new ContactFact(node.sourceVersion(),
                        outgoing.getOrDefault(node.id(), List.of()).size()));
            }
            for (var edge : outgoing.getOrDefault(step.node, List.of())) {
                pending.addLast(new Step(edge.toId(), step.depth + 1));
            }
        }
        if (seen.size() != nodes.size()) { invalidProjection(); }
        return Map.copyOf(contacts);
    }

    private static void requireCompleteBatch(List<ContactDisplay> batch, List<UUID> ids, Map<UUID, ContactFact> contacts) {
        if (batch == null || batch.size() != ids.size()) { invalidSource(); }
        var seen = new HashSet<UUID>();
        for (var display : batch) {
            if (display == null || !ids.contains(display.contactId()) || !seen.add(display.contactId())
                    || contacts.get(display.contactId()).version != display.contactVersion()
                    || contacts.get(display.contactId()).aliases != display.aliases().size()) { invalidSource(); }
        }
    }

    // generation是投影发布代次；实际业务引用、版本、关系必须完整一致，不能只信来源摘要标签。
    private static void requireSameFacts(GraphSnapshot expected, GraphSnapshot actual) {
        requireOwner(actual, expected.ownerUserId());
        if (!expected.sourceDigest().equals(actual.sourceDigest())) {
            throw new GraphSourceException(GraphSourceException.Kind.SOURCE_CHANGED);
        }
        var expectedNodes = expected.nodes().stream().map(node -> new NodeFact(node.id(), node.type(),
                node.sourceId(), node.sourceVersion())).collect(Collectors.toSet());
        var actualNodes = actual.nodes().stream().map(node -> new NodeFact(node.id(), node.type(),
                node.sourceId(), node.sourceVersion())).collect(Collectors.toSet());
        var expectedEdges = expected.edges().stream().map(edge -> new EdgeFact(edge.id(), edge.fromId(), edge.toId(),
                edge.type())).collect(Collectors.toSet());
        var actualEdges = actual.edges().stream().map(edge -> new EdgeFact(edge.id(), edge.fromId(), edge.toId(),
                edge.type())).collect(Collectors.toSet());
        if (!expectedNodes.equals(actualNodes) || !expectedEdges.equals(actualEdges)) { invalidSource(); }
    }

    private static void requireOwner(GraphSnapshot graph, UUID owner) {
        if (graph == null || !owner.equals(graph.ownerUserId())) { invalidSource(); }
    }

    private static String normalize(String alias) {
        return Normalizer.normalize(alias, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) { throw new CancellationException("GRAPH_QUERY_CANCELLED"); }
    }

    private static void invalidSource() { throw new GraphSourceException(GraphSourceException.Kind.SOURCE_INVALID); }
    private static void invalidProjection() { throw new GraphProjectionException(GraphProjectionException.Kind.INVALID); }

    private record Step(UUID node, int depth) { }
    private record ContactFact(long version, int aliases) { }
    private record NodeFact(UUID node, GraphNode.Type type, UUID source, long version) { }
    private record EdgeFact(UUID edge, UUID from, UUID to, com.aifriend.knowledge.domain.GraphEdge.Type type) { }
}
