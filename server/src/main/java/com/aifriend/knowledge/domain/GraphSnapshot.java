package com.aifriend.knowledge.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 完整两跳关系快照；越限、混归属或损坏时整批拒绝。
 * @param ownerUserId 当前用户
 * @param generation 投影世代
 * @param sourceDigest 排序后的权威引用/版本摘要，不含称呼摘要
 * @param nodes 完整节点集合
 * @param edges 完整边集合
 * @author codex
 * @since 1.0.0
 */
public record GraphSnapshot(UUID ownerUserId, UUID generation, String sourceDigest,
        List<GraphNode> nodes, List<GraphEdge> edges) {
    /** 校验归属、版本、来源唯一性、端点、类型及连通性。 */
    public GraphSnapshot {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(generation, "generation");
        if (sourceDigest == null || !sourceDigest.matches("[a-f0-9]{64}") || nodes == null
                || edges == null || nodes.isEmpty() || nodes.size() > 200 || edges.size() > 500) {
            throw new IllegalArgumentException("INVALID_GRAPH_SNAPSHOT");
        }
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        var byId = new HashMap<UUID, GraphNode>();
        var sources = new HashSet<String>();
        int roots = 0;
        for (var node : nodes) {
            if (!ownerUserId.equals(node.ownerUserId()) || !generation.equals(node.generation())
                    || byId.put(node.id(), node) != null || !sources.add(node.type() + ":" + node.sourceId())) {
                throw new IllegalArgumentException("MIXED_GRAPH_NODE");
            }
            if (node.type() == GraphNode.Type.USER) {
                roots++;
            }
        }
        if (roots != 1) {
            throw new IllegalArgumentException("INVALID_GRAPH_ROOT");
        }
        var edgeIds = new HashSet<UUID>();
        var destinations = new HashSet<UUID>();
        for (var edge : edges) {
            var from = byId.get(edge.fromId());
            var to = byId.get(edge.toId());
            if (!ownerUserId.equals(edge.ownerUserId()) || !generation.equals(edge.generation())
                    || !edgeIds.add(edge.id()) || !destinations.add(edge.toId()) || from == null || to == null) {
                throw new IllegalArgumentException("INVALID_GRAPH_EDGE");
            }
            boolean permitted = switch (edge.type()) {
                case HAS_CONTACT -> from.type() == GraphNode.Type.USER && to.type() == GraphNode.Type.CONTACT;
                case HAS_ALIAS -> from.type() == GraphNode.Type.CONTACT && to.type() == GraphNode.Type.ALIAS;
            };
            if (!permitted) {
                throw new IllegalArgumentException("INVALID_GRAPH_RELATION");
            }
        }
        for (var node : nodes) {
            if (node.type() != GraphNode.Type.USER && !destinations.contains(node.id())) {
                throw new IllegalArgumentException("DISCONNECTED_GRAPH");
            }
        }
    }

    /** 不默认输出私人引用。 */
    @Override public String toString() { return "GraphSnapshot[nodes=" + nodes.size() + ", edges=" + edges.size() + "]"; }
}
