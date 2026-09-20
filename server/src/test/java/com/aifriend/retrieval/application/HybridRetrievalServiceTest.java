package com.aifriend.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.retrieval.domain.*;
import com.aifriend.retrieval.infrastructure.KnowledgeTokenizer;
import com.aifriend.retrieval.infrastructure.LocalKnowledgeSearchAdapter;
import com.aifriend.retrieval.infrastructure.RrfFusion;

class HybridRetrievalServiceTest {
    private static final EmbeddingProfile PROFILE = new EmbeddingProfile("test-space", 2);
    private final KnowledgeRepositoryPort repository = mock(KnowledgeRepositoryPort.class);
    private final KnowledgeVectorRepositoryPort vectors = mock(KnowledgeVectorRepositoryPort.class);
    private final EmbeddingPort embedding = mock(EmbeddingPort.class);
    private final AtomicBoolean current = new AtomicBoolean(true);
    private KnowledgeRepositoryPort.Snapshot snapshot;
    private HybridRetrievalService service;

    @BeforeEach void setup() {
        var doc = new KnowledgeDocument(id(1), "guide", 1, "指南", "zh-CN", 1, 2, "开启守护");
        var chunk = new KnowledgeChunk(id(101), doc.id(), 1, 0, "", doc.text(), 0, 4, "v1");
        snapshot = new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id(50), 1, Optional.of(PROFILE),
                KnowledgeTokenizer.VERSION, "v1"), List.of(doc), List.of(chunk));
        when(repository.readActive()).thenReturn(Optional.of(snapshot));
        when(repository.isCurrent(any(), anyList())).thenAnswer(invocation -> current.get());
        when(vectors.load(snapshot.version())).thenReturn(Map.of(id(101), new float[] {1, 0}));
        when(embedding.embed(eq(PROFILE), anyList(), any())).thenReturn(new EmbeddingBatch(PROFILE, new float[][] {{1, 0}}));
        service = new HybridRetrievalService(repository,
                new LocalKnowledgeSearchAdapter(vectors, 1.2, .75, 128L * 1024 * 1024),
                new RrfFusion(60), Optional.of(embedding), Duration.ofSeconds(2));
    }

    @Test void defaultLocalOnlyCannotCallEmbeddingEvenWhenConfigured() {
        var result = service.retrieve(new RetrievalQuery("守护", "zh-CN", 1, 4));
        assertThat(result.mode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        assertThat(result.evidence()).hasSize(1);
        verifyNoInteractions(embedding, vectors);
        verify(repository).isCurrent(eq(snapshot.version()), anyList());
    }

    @Test void approvedProcessingCombinesBothLanesAndRevalidatesSources() {
        var result = service.retrieve(external("守护", "zh-CN", 1));
        assertThat(result.mode()).isEqualTo(RetrievalResult.Mode.HYBRID);
        assertThat(result.evidence()).hasSize(1);
        verify(embedding, times(1)).embed(eq(PROFILE), eq(List.of("守护")), any());
        verify(repository).isCurrent(eq(snapshot.version()), anyList());
    }

    @Test void modelFailureDegradesOnceToIndependentlyValidKeywordEvidence() {
        when(embedding.embed(eq(PROFILE), anyList(), any()))
                .thenThrow(new KnowledgeGatewayException(KnowledgeGatewayException.Kind.TEMPORARY));
        var result = service.retrieve(external("守护", "zh-CN", 1));
        assertThat(result.mode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        assertThat(result.evidence()).hasSize(1);
        verify(embedding, times(1)).embed(any(), anyList(), any());
        verifyNoInteractions(vectors);
    }

    @Test void noApplicableVersionOrLanguageAndSymbolsDoNotCauseExternalCalls() {
        assertThat(service.retrieve(external("守护", "en", 1)).evidence()).isEmpty();
        assertThat(service.retrieve(external("守护", "zh-CN", 3)).evidence()).isEmpty();
        assertThat(service.retrieve(external("?!", "zh-CN", 1)).evidence()).isEmpty();
        verifyNoInteractions(embedding, vectors);
    }

    @Test void corruptedStoredVectorsAreNotHiddenByKeywordFallback() {
        when(vectors.load(any())).thenReturn(Map.of(id(101), new float[] {Float.NaN, 0}));
        assertThatThrownBy(() -> service.retrieve(external("守护", "zh-CN", 1)))
                .isInstanceOf(KnowledgeRetrievalException.class).hasMessage("INDEX_INVALID");
    }

    @Test void sourceDeletedWhileEmbeddingRunsCannotReturnOldEvidence() {
        when(embedding.embed(eq(PROFILE), anyList(), any())).thenAnswer(invocation -> {
            current.set(false);
            return new EmbeddingBatch(PROFILE, new float[][] {{1, 0}});
        });
        assertThatThrownBy(() -> service.retrieve(external("守护", "zh-CN", 1)))
                .isInstanceOf(KnowledgeRetrievalException.class).hasMessage("SOURCE_CHANGED");
    }

    @Test void noActiveIndexIsNotFabricatedAsAnEmptySuccessfulSearch() {
        when(repository.readActive()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retrieve(external("守护", "zh-CN", 1))).hasMessage("NO_INDEX");
        verifyNoInteractions(embedding, vectors);
    }

    @Test void profileMismatchInQueryResponseDoesNotEnterVectorIndex() {
        when(embedding.embed(eq(PROFILE), anyList(), any()))
                .thenReturn(new EmbeddingBatch(new EmbeddingProfile("wrong-space", 2), new float[][] {{1, 0}}));
        assertThat(service.retrieve(external("守护", "zh-CN", 1)).mode()).isEqualTo(RetrievalResult.Mode.KEYWORD_ONLY);
        verifyNoInteractions(vectors);
    }

    @Test void finalDatabaseFailureCannotBeTreatedAsValidCachedEvidence() {
        when(repository.isCurrent(any(), anyList())).thenThrow(new IllegalStateException("simulated storage failure"));
        assertThatThrownBy(() -> service.retrieve(new RetrievalQuery("守护", "zh-CN", 1, 4)))
                .isInstanceOf(IllegalStateException.class);
    }

    private RetrievalQuery external(String text, String locale, int version) {
        return new RetrievalQuery(text, locale, version, 4, RetrievalQuery.ExternalProcessing.ALLOWED);
    }
    private static UUID id(int n) { return new UUID(0, n); }
}
