package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.application.KnowledgeAnswerService;
import com.aifriend.assistant.domain.AssistantAnswer;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 冻结独立问题实际经过生产BM25/RRF/摘录服务；存储/额度模拟，绝不调用真实模型。 */
class KnowledgeFrozenLocalReplayTest {
    private static final Instant NOW = Instant.parse("2026-09-11T11:00:00Z");

    @ParameterizedTest(name="{index}: frozen {0}") @MethodSource("questions")
    void frozenQuestionMustReturnSupportedExtractOrAbstain(Sample sample) throws Exception {
        var snapshot = snapshot();
        var repository = mock(KnowledgeRepositoryPort.class);
        when(repository.readActive()).thenReturn(Optional.of(snapshot));
        when(repository.isCurrent(any(), anyList())).thenReturn(true);
        var vectors = mock(KnowledgeVectorRepositoryPort.class);
        var consents = mock(ConsentGrantQueryPort.class);
        var quota = mock(KnowledgeQuotaPort.class);
        when(quota.reserve(any())).thenReturn(KnowledgeQuotaPort.Decision.GRANTED);
        var service = new KnowledgeAnswerService(repository,
                new LocalKnowledgeSearchAdapter(vectors,1.2,.75,128L*1024*1024),new RrfFusion(60),
                Optional.empty(),Optional.empty(),quota,new KnowledgeAccessPolicy(consents),
                new KnowledgeAnswerService.Settings(AssistantAnswer.Mode.EXTRACTIVE,Duration.ofSeconds(4)),
                Clock.fixed(NOW,ZoneOffset.UTC));
        var answer = service.answer(new KnowledgeAnswerService.Request(id("owner"),id(sample.id()),
                new RetrievalQuery(sample.input(),"zh-CN",1,4),NOW.plusSeconds(8),Optional.empty()));
        verifyNoInteractions(vectors,consents);
        assertThat(answer.candidates()).isEmpty();
        if (sample.category().equals("ANSWERABLE")) {
            assertThat(answer.status()).as(sample.id()).isEqualTo(AssistantAnswer.Status.EVIDENCE_ONLY);
            assertThat(answer.mode()).isEqualTo(AssistantAnswer.Mode.EXTRACTIVE);
            assertThat(answer.citations()).as(sample.id()).anyMatch(e -> e.chunk().documentId().equals(id(sample.source()))
                    && e.chunk().text().contains(sample.fact()));
            assertThat(answer.text()).contains(sample.fact());
            assertThat(answer.citations()).allMatch(e -> snapshot.chunks().contains(e.chunk()));
        } else {
            assertThat(answer.status()).as(sample.id()).isIn(AssistantAnswer.Status.NO_EVIDENCE,
                    AssistantAnswer.Status.NEEDS_CLARIFICATION);
            assertThat(answer.citations()).isEmpty();
        }
    }

    @Test void frozenAnswerableBm25RecallAtFourMustMeetNinetyPercent() throws Exception {
        var snapshot = snapshot();
        var index = new Bm25KeywordIndex(snapshot,new KnowledgeTokenizer(),1.2,.75,128L*1024*1024);
        var misses = new ArrayList<String>();
        var answerable = questions().filter(s -> s.category().equals("ANSWERABLE")).toList();
        for (var sample : answerable) {
            var hits = index.search(new RetrievalQuery(sample.input(),"zh-CN",1,4),4);
            if(hits.stream().noneMatch(e -> e.chunk().documentId().equals(id(sample.source())))) misses.add(sample.id());
        }
        assertThat(answerable).hasSize(20);
        assertThat(misses).as("BM25-only Recall@4; missing frozen IDs, not generated-answer quality").hasSizeLessThanOrEqualTo(2);
    }

    static Stream<Sample> questions() throws Exception {
        return rows("cases-v1.jsonl").stream().filter(r -> List.of("ANSWERABLE","NO_EVIDENCE").contains(r.path("category").asText()))
                .map(r -> new Sample(r.path("id").asText(),r.path("category").asText(),r.path("input").asText(),
                        r.path("evidenceSource").asText(),r.path("requiredFact").asText()));
    }

    private static KnowledgeRepositoryPort.Snapshot snapshot() throws Exception {
        var chunker = new KnowledgeChunker(400,60,600);
        var documents = rows("corpus-v1.jsonl").stream().map(r -> new KnowledgeDocument(id(r.get("id").asText()),
                r.get("id").asText(),1,r.get("title").asText(),r.get("locale").asText(),
                r.get("minAppVersion").asInt(),r.get("maxAppVersion").asInt(),r.get("text").asText())).toList();
        var chunks = documents.stream().flatMap(d -> chunker.chunk(d).stream()).toList();
        return new KnowledgeRepositoryPort.Snapshot(new IndexVersion(id("generation"),1,Optional.empty(),
                KnowledgeTokenizer.VERSION,chunker.version()),documents,chunks);
    }

    private static List<JsonNode> rows(String name) throws Exception {
        try(var input = KnowledgeFrozenLocalReplayTest.class.getResourceAsStream("/knowledge-acceptance/"+name)) {
            if(input==null) throw new IllegalStateException("Missing frozen resource");
            var result = new ArrayList<JsonNode>();
            var mapper = new ObjectMapper();
            for(var line : new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList()) result.add(mapper.readTree(line));
            return List.copyOf(result);
        }
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    record Sample(String id,String category,String input,String source,String fact) {
        @Override public String toString() { return id; }
    }
}
