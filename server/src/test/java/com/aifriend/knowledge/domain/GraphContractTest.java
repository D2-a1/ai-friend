package com.aifriend.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class GraphContractTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID GENERATION = new UUID(0, 2);
    private static final String DIGEST = "a".repeat(64);

    @Test void emptyContactGraphStillHasOneOwnerRoot() {
        assertThat(snapshot(List.of(root()), List.of()).nodes()).hasSize(1);
        assertThatThrownBy(() -> snapshot(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void mixedOwnerAndGenerationAreRejectedEvenForSameNodeId() {
        var foreign = new GraphNode(new UUID(0, 9), GENERATION, id(3), GraphNode.Type.CONTACT, id(3), 1);
        var stale = new GraphNode(OWNER, id(9), id(3), GraphNode.Type.CONTACT, id(3), 1);
        for (var bad : List.of(foreign, stale)) {
            assertThatThrownBy(() -> snapshot(List.of(root(), bad), List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void requiresExistingEndpointsAndValidFiniteRelation() {
        assertThatThrownBy(() -> snapshot(List.of(root()),
                List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(root(), contact(3)),
                List.of(edge(10, 1, 3, GraphEdge.Type.HAS_ALIAS)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(root(), contact(3)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void noSelfLoopsCyclesOrDuplicateParent() {
        assertThatThrownBy(() -> edge(10, 1, 1, GraphEdge.Type.HAS_CONTACT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(root(), contact(3)),
                List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT), edge(11, 3, 1, GraphEdge.Type.HAS_ALIAS))))
                .isInstanceOf(IllegalArgumentException.class);
        var alias = new GraphNode(OWNER, GENERATION, id(5), GraphNode.Type.ALIAS, id(5), 1);
        assertThatThrownBy(() -> snapshot(List.of(root(), contact(3), contact(4), alias),
                List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT), edge(11, 1, 4, GraphEdge.Type.HAS_CONTACT),
                        edge(12, 3, 5, GraphEdge.Type.HAS_ALIAS), edge(13, 4, 5, GraphEdge.Type.HAS_ALIAS))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void exactNodeLimitSucceedsButOverflowIsNotTruncated() {
        var nodes = new ArrayList<GraphNode>();
        var edges = new ArrayList<GraphEdge>();
        nodes.add(root());
        for (int i = 3; i < 202; i++) {
            nodes.add(contact(i));
            edges.add(edge(i + 1000, 1, i, GraphEdge.Type.HAS_CONTACT));
        }
        assertThat(snapshot(nodes, edges).nodes()).hasSize(200);
        nodes.add(contact(202));
        edges.add(edge(1202, 1, 202, GraphEdge.Type.HAS_CONTACT));
        assertThatThrownBy(() -> snapshot(nodes, edges)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(List.of(root()),
                java.util.Collections.nCopies(501, edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void snapshotDefensivelyCopiesCollectionsAndDoesNotLogReferences() {
        var nodes = new ArrayList<>(List.of(root(), contact(3)));
        var edges = new ArrayList<>(List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT)));
        var graph = snapshot(nodes, edges);
        nodes.clear();
        edges.clear();
        assertThat(graph.nodes()).hasSize(2);
        assertThatThrownBy(() -> graph.edges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(graph.toString()).doesNotContain(OWNER.toString(), DIGEST);
        assertThat(graph.nodes().get(0).toString()).doesNotContain(OWNER.toString());
    }

    @Test void projectionCannotContainArbitraryTextProperties() {
        for (Class<?> type : List.of(GraphNode.class, GraphEdge.class)) {
            assertThat(java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType))
                    .noneMatch(field -> field == String.class || java.util.Map.class.isAssignableFrom(field));
        }
    }

    @Test void invalidRootAndDuplicateSourceAreRejected() {
        assertThatThrownBy(() -> new GraphNode(OWNER, GENERATION, id(7), GraphNode.Type.USER, id(8), 0))
                .isInstanceOf(IllegalArgumentException.class);
        var duplicate = new GraphNode(OWNER, GENERATION, id(4), GraphNode.Type.CONTACT, id(3), 1);
        assertThatThrownBy(() -> snapshot(List.of(root(), contact(3), duplicate),
                List.of(edge(10, 1, 3, GraphEdge.Type.HAS_CONTACT), edge(11, 1, 4, GraphEdge.Type.HAS_CONTACT))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GraphSnapshot snapshot(List<GraphNode> nodes, List<GraphEdge> edges) {
        return new GraphSnapshot(OWNER, GENERATION, DIGEST, nodes, edges);
    }
    private GraphNode root() { return new GraphNode(OWNER, GENERATION, OWNER, GraphNode.Type.USER, OWNER, 0); }
    private GraphNode contact(int n) { return new GraphNode(OWNER, GENERATION, id(n), GraphNode.Type.CONTACT, id(n), 1); }
    private GraphEdge edge(int n, int from, int to, GraphEdge.Type type) {
        return new GraphEdge(OWNER, GENERATION, id(n), id(from), id(to), type);
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
