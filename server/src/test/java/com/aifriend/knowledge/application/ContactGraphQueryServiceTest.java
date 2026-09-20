package com.aifriend.knowledge.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.domain.*;
import com.aifriend.shared.error.BusinessException;

/** 真实BFS/来源复验/有限查询，来源和持久端口为模拟，不调用外部模型。 */
class ContactGraphQueryServiceTest {
    private final UUID owner = id(1);
    private final ContactGraphSourcePort source = mock(ContactGraphSourcePort.class);
    private final KnowledgeGraphPort projections = mock(KnowledgeGraphPort.class);
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private final Map<UUID, List<String>> names = new HashMap<>();
    private GraphSnapshot current;
    private KnowledgeGraphPort.Projection stored;
    private boolean granted = true;
    private Runnable afterDisplay = () -> {};
    private ContactGraphQueryService service;

    @BeforeEach void setup() {
        current = graph(owner, 10, 1, 2);
        names.put(id(100), List.of("老二")); names.put(id(101), List.of("老三"));
        when(consents.isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH, "contact-graph-v1"))
                .thenAnswer(call -> granted);
        when(source.snapshot(owner)).thenAnswer(call -> current);
        when(projections.findByOwner(owner)).thenAnswer(call -> Optional.ofNullable(stored));
        when(projections.project(any(), anyLong())).thenAnswer(call -> {
            stored = new KnowledgeGraphPort.Projection(call.getArgument(0), (Long) call.getArgument(1) + 1); return stored;
        });
        when(source.displayCurrent(eq(owner), anyString(), anyList())).thenAnswer(call -> {
            if (!current.sourceDigest().equals(call.getArgument(1))) { throw new GraphSourceException(GraphSourceException.Kind.SOURCE_CHANGED); }
            List<UUID> ids = call.getArgument(2);
            var displays = ids.stream().map(contact -> new ContactDisplay(contact,
                    current.nodes().stream().filter(node -> node.type() == GraphNode.Type.CONTACT && node.sourceId().equals(contact))
                            .findFirst().orElseThrow().sourceVersion(), names.getOrDefault(contact, List.of("称呼")))).toList();
            afterDisplay.run(); return displays;
        });
        service = new ContactGraphQueryService(source, projections, new KnowledgeAccessPolicy(consents));
    }

    @Test void firstReadBuildsProjectionThenListsCurrentContactAliases() {
        var result = service.query(list());
        assertThat(result.candidates()).extracting(ContactDisplay::contactId).containsExactly(id(100), id(101));
        assertThat(result.ambiguous()).isFalse();
        assertThat(result.candidates().get(0).aliases()).containsExactly("老二");
        verify(projections).project(current, 0);
        verify(source, times(2)).snapshot(owner);
    }

    @Test void detailedSourceReusesFinalFreshProofAndRejectsChangedFacts() {
        var detailed=mock(ContactGraphEvidencePort.class);
        when(detailed.snapshot(owner)).thenAnswer(call->current);
        var displays=List.of(new ContactDisplay(id(100),1,List.of("老二")),new ContactDisplay(id(101),1,List.of("老三")));
        when(detailed.displayEvidence(eq(owner),anyString(),anyList()))
                .thenAnswer(call->new ContactGraphEvidencePort.Evidence(current,current,displays));
        var optimized=new ContactGraphQueryService(detailed,projections,new KnowledgeAccessPolicy(consents));
        assertThat(optimized.query(list()).candidates()).isEqualTo(displays);
        verify(detailed,times(1)).snapshot(owner);
        verify(detailed,never()).displayCurrent(any(),anyString(),anyList());
        when(detailed.displayEvidence(eq(owner),anyString(),anyList()))
                .thenReturn(new ContactGraphEvidencePort.Evidence(current,graph(owner,11,2,2),displays));
        failure("SOURCE_CHANGED",()->optimized.query(list()));
    }

    @Test void detailedSourceCannotMixEarlierBatchWithChangedFinalBatchOrRevokedConsent() {
        current=graph(owner,10,1,21);
        var detailed=mock(ContactGraphEvidencePort.class);
        when(detailed.snapshot(owner)).thenReturn(current);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(detailed.displayEvidence(eq(owner),anyString(),anyList())).thenAnswer(call->{
            List<UUID> ids=call.getArgument(2);
            var displays=ids.stream().map(id->new ContactDisplay(id,1,List.of("相同称呼"))).toList();
            return new ContactGraphEvidencePort.Evidence(current,
                    count.incrementAndGet()==2?graph(owner,11,2,21):current,displays);
        });
        var optimized=new ContactGraphQueryService(detailed,projections,new KnowledgeAccessPolicy(consents));
        failure("SOURCE_CHANGED",()->optimized.query(find("不存在")));
        verify(detailed,times(2)).displayEvidence(eq(owner),anyString(),anyList());
        when(detailed.displayEvidence(eq(owner),anyString(),anyList())).thenAnswer(call->{
            granted=false;
            List<UUID> ids=call.getArgument(2);
            return new ContactGraphEvidencePort.Evidence(current,current,
                    ids.stream().map(id->new ContactDisplay(id,1,List.of("相同称呼"))).toList());
        });
        assertThatThrownBy(()->optimized.query(find("不存在"))).isInstanceOf(BusinessException.class);
    }

    @Test void sameFactsWithDifferentProjectionGenerationDoesNotRebuild() {
        stored = new KnowledgeGraphPort.Projection(graph(owner, 11, 1, 2), 3);
        assertThat(service.query(list()).candidates()).hasSize(2);
        verify(projections, never()).project(any(), anyLong());
    }

    @Test void sourceVersionChangeRebuildsOnceAtExpectedControlVersion() {
        stored = new KnowledgeGraphPort.Projection(graph(owner, 11, 1, 2), 3);
        current = graph(owner, 12, 2, 2);
        assertThat(service.query(list()).candidates()).allSatisfy(candidate -> assertThat(candidate.contactVersion()).isEqualTo(2));
        verify(projections).project(current, 3);
    }

    @Test void aliasesQueryOnlyDecryptsRequestedCurrentContact() {
        var result = service.query(new GraphQueryPort.Query(owner, GraphQueryType.LIST_ALIASES,
                Optional.of(id(101)), Optional.empty()));
        assertThat(result.candidates()).singleElement().satisfies(candidate -> assertThat(candidate.aliases()).containsExactly("老三"));
        verify(source).displayCurrent(owner, current.sourceDigest(), List.of(id(101)));
    }

    @Test void unknownContactReturnsNoCandidateAfterFreshValidationNotForeignLookup() {
        assertThat(service.query(new GraphQueryPort.Query(owner, GraphQueryType.LIST_ALIASES,
                Optional.of(id(999)), Optional.empty())).candidates()).isEmpty();
        verify(source, never()).displayCurrent(any(), any(), any());
        verify(source, times(2)).snapshot(owner);
    }

    @Test void normalizedExactAliasFindsCandidatesButDoesNotDoFuzzyOrRelationshipInference() {
        names.put(id(100), List.of("Ｊａｃｋ"));
        assertThat(service.query(find("jack")).candidates()).extracting(ContactDisplay::contactId).containsExactly(id(100));
        assertThat(service.query(find("给Jack打电话")).candidates()).isEmpty();
        assertThat(service.query(find("jack的哥哥")).candidates()).isEmpty();
    }

    @Test void duplicateAliasReturnsAmbiguousCandidatesNeverTopOne() {
        names.put(id(101), List.of("老二"));
        var result = service.query(find("老二"));
        assertThat(result.candidates()).hasSize(2); assertThat(result.ambiguous()).isTrue();
    }

    @Test void aliasLookupScansBeyondFirstTwentyAndDetectsLateAmbiguity() {
        current = graph(owner, 10, 1, 21); names.put(id(120), List.of("老二"));
        var result = service.query(find("老二"));
        assertThat(result.candidates()).extracting(ContactDisplay::contactId).containsExactly(id(100), id(120));
        assertThat(result.ambiguous()).isTrue();
        verify(source, times(2)).displayCurrent(eq(owner), eq(current.sourceDigest()), anyList());
    }

    @Test void tooManyResultsAreRejectedWithoutTruncation() {
        current = graph(owner, 10, 1, 21);
        for (int i = 0; i < 21; i++) { names.put(id(100 + i), List.of("同名")); }
        failure("GRAPH_LIMIT", () -> service.query(list()));
        failure("GRAPH_LIMIT", () -> service.query(find("同名")));
    }

    @Test void finalSourceChangeAfterDisplayDropsOldCandidates() {
        afterDisplay = () -> current = graph(owner, 11, 2, 1);
        failure("SOURCE_CHANGED", () -> service.query(list()));
    }

    @Test void finalConsentRevocationDropsCandidates() {
        afterDisplay = () -> granted = false;
        assertThatThrownBy(() -> service.query(list())).isInstanceOf(BusinessException.class);
    }

    @Test void initialConsentFailureDoesNotReadSourceOrProjection() {
        granted = false;
        assertThatThrownBy(() -> service.query(list())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(source, projections);
    }

    @Test void foreignSourceOrStoredProjectionCannotCrossOwnerBoundary() {
        current = graph(id(2), 10, 1, 2);
        failure("SOURCE_INVALID", () -> service.query(list()));
        current = graph(owner, 10, 1, 2);
        stored = new KnowledgeGraphPort.Projection(graph(id(2), 10, 1, 2), 1);
        failure("SOURCE_INVALID", () -> service.query(list()));
    }

    @Test void matchingDigestDoesNotHideChangedNodes() {
        var forged = graph(owner, 11, 2, 2);
        stored = new KnowledgeGraphPort.Projection(new GraphSnapshot(owner, forged.generation(), current.sourceDigest(),
                forged.nodes(), forged.edges()), 1);
        failure("SOURCE_INVALID", () -> service.query(list()));
        verify(source, never()).displayCurrent(any(), any(), any());
    }

    @Test void incompleteForeignOrStaleDisplayBatchCannotReturnPartialSuccess() {
        for (List<ContactDisplay> batch : List.of(List.of(new ContactDisplay(id(100), 1, List.of("老二"))),
                List.of(new ContactDisplay(id(100), 1, List.of("老二")), new ContactDisplay(id(999), 1, List.of("老三"))),
                List.of(new ContactDisplay(id(100), 2, List.of("老二")), new ContactDisplay(id(101), 1, List.of("老三"))))) {
            doReturn(batch).when(source).displayCurrent(eq(owner), anyString(), anyList());
            failure("SOURCE_INVALID", () -> service.query(list()));
        }
    }

    @Test void projectionConflictIsNotRetriedOrTurnedIntoAnAnswer() {
        doThrow(new GraphProjectionException(GraphProjectionException.Kind.CONFLICT)).when(projections).project(any(), anyLong());
        assertThatThrownBy(() -> service.query(list())).isInstanceOf(GraphProjectionException.class).hasMessage("CONFLICT");
        verify(projections).project(any(), anyLong());
        verify(source, never()).displayCurrent(any(), any(), any());
    }

    @Test void completeContactListWithMissingAliasIsStillRejected() {
        doReturn(List.of(new ContactDisplay(id(100), 1, List.of()),
                new ContactDisplay(id(101), 1, List.of("老三"))))
                .when(source).displayCurrent(eq(owner), anyString(), anyList());
        failure("SOURCE_INVALID", () -> service.query(list()));
    }

    @Test void cancellationDoesNotClearInterruptOrContinueReading() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service.query(list())).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(source, projections, consents);
        } finally { Thread.interrupted(); }
    }

    @Test void emptyGraphStillValidatesCurrentSourceAndConsent() {
        current = graph(owner, 10, 1, 0);
        assertThat(service.query(list()).candidates()).isEmpty();
        verify(source, times(2)).snapshot(owner);
        verify(consents, times(2)).isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH, "contact-graph-v1");
    }

    private GraphQueryPort.Query list() { return new GraphQueryPort.Query(owner, GraphQueryType.LIST_CONTACTS, Optional.empty(), Optional.empty()); }
    private GraphQueryPort.Query find(String alias) { return new GraphQueryPort.Query(owner, GraphQueryType.FIND_CONTACT_BY_ALIAS, Optional.empty(), Optional.of(alias)); }
    private static void failure(String kind, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(GraphSourceException.class).hasMessage(kind);
    }
    private static UUID id(long value) { return new UUID(0, value); }
    private static GraphSnapshot graph(UUID owner, long generation, long version, int contacts) {
        var nodes = new ArrayList<GraphNode>(); var edges = new ArrayList<GraphEdge>(); UUID gen = id(generation);
        nodes.add(new GraphNode(owner, gen, id(3), GraphNode.Type.USER, owner, version));
        for (int i = 0; i < contacts; i++) {
            nodes.add(new GraphNode(owner, gen, id(100 + i), GraphNode.Type.CONTACT, id(100 + i), version));
            nodes.add(new GraphNode(owner, gen, id(300 + i), GraphNode.Type.ALIAS, id(300 + i), version));
            edges.add(new GraphEdge(owner, gen, id(500 + i), id(3), id(100 + i), GraphEdge.Type.HAS_CONTACT));
            edges.add(new GraphEdge(owner, gen, id(700 + i), id(100 + i), id(300 + i), GraphEdge.Type.HAS_ALIAS));
        }
        return new GraphSnapshot(owner, gen, (version == 1 ? "a" : "b").repeat(64), nodes, edges);
    }
}
