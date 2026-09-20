package com.aifriend.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.knowledge.application.GraphQueryPort;
import com.aifriend.knowledge.domain.ContactDisplay;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.retrieval.domain.KnowledgeChunk;
import com.aifriend.retrieval.domain.RetrievalEvidence;

class AssistantBoundaryContractTest {
    private static final UUID ID = new UUID(0, 1);

    @Test void graphQueryOnlyAcceptsItsOwnFiniteParameters() {
        assertThat(new GraphQueryPort.Query(ID, GraphQueryType.LIST_CONTACTS, Optional.empty(), Optional.empty()))
                .isNotNull();
        assertThatThrownBy(() -> new GraphQueryPort.Query(ID, GraphQueryType.LIST_CONTACTS, Optional.of(ID), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphQueryPort.Query(ID, GraphQueryType.LIST_ALIASES, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphQueryPort.Query(ID, GraphQueryType.FIND_CONTACT_BY_ALIAS, Optional.empty(), Optional.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void candidatesAreImmutableBoundedAndRedacted() {
        var aliases = new ArrayList<>(List.of("虚构称呼"));
        var display = new ContactDisplay(ID, 1, aliases);
        aliases.clear();
        assertThat(display.aliases()).containsExactly("虚构称呼");
        assertThat(display.toString()).doesNotContain("虚构称呼", ID.toString());
        assertThatThrownBy(() -> new ContactDisplay(ID, 1, java.util.Collections.nCopies(6, "称呼")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ambiguousResultNeedsMultipleUniqueCandidates() {
        var display = new ContactDisplay(ID, 1, List.of("称呼"));
        assertThatThrownBy(() -> new GraphQueryPort.Result("a".repeat(64), List.of(display), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphQueryPort.Result("a".repeat(64), List.of(display, display), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void extractiveCannotPretendToBeGeneratedAnswer() {
        var evidence = evidence();
        assertThatThrownBy(() -> new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                AssistantAnswer.Status.ANSWERED, AssistantAnswer.Mode.EXTRACTIVE, AssistantReason.NONE,
                "原文", List.of(evidence), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                AssistantAnswer.Status.EVIDENCE_ONLY, AssistantAnswer.Mode.EXTRACTIVE, AssistantReason.NONE,
                "原文", List.of(evidence), List.of()).status()).isEqualTo(AssistantAnswer.Status.EVIDENCE_ONLY);
    }

    @Test void privateGraphCannotEnterGeneratedOrPublicKnowledgeResult() {
        assertThatThrownBy(() -> new AssistantAnswer(AssistantAnswer.Purpose.CONTACT_GRAPH,
                AssistantAnswer.Status.ANSWERED, AssistantAnswer.Mode.GENERATED, AssistantReason.NONE,
                "私有", List.of(evidence()), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                AssistantAnswer.Status.NEEDS_CLARIFICATION, AssistantAnswer.Mode.NONE, AssistantReason.AMBIGUOUS_CONTACT,
                "私有", List.of(), List.of(new ContactDisplay(ID, 1, List.of("称呼")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unavailableDoesNotCarryOldEvidence() {
        assertThatThrownBy(() -> new AssistantAnswer(AssistantAnswer.Purpose.PUBLIC_KNOWLEDGE,
                AssistantAnswer.Status.UNAVAILABLE, AssistantAnswer.Mode.NONE, AssistantReason.ACCESS_REVOKED,
                "不可用", List.of(evidence()), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void finiteReasonsMatchGeneratedContractExactly() throws Exception {
        String generated = Files.readString(Path.of("target/generated-sources/openapi/src/main/java/"
                + "com/aifriend/contract/model/AssistantReasonCode.java"));
        var values = java.util.Arrays.stream(AssistantReason.values()).map(Enum::name).toList();
        for (String value : values) {
            assertThat(generated).contains(value + "(\"" + value + "\")");
        }
        var matcher = java.util.regex.Pattern.compile("(?m)^  ([A-Z_]+)\\(\"").matcher(generated);
        var names = new ArrayList<String>();
        while (matcher.find()) { names.add(matcher.group(1)); }
        assertThat(names).containsExactlyElementsOf(values);
    }

    private RetrievalEvidence evidence() {
        return new RetrievalEvidence(new KnowledgeChunk(ID, ID, 1, 0, "", "原文", 0, 2, "v1"), 1);
    }
}
