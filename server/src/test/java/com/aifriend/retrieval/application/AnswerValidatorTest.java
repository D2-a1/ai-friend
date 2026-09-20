package com.aifriend.retrieval.application;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.domain.*;

class AnswerValidatorTest {
    private final AnswerValidator validator = new AnswerValidator();

    @Test void resolvesOnlyCurrentEvidenceInFirstCitationOrder() {
        var evidence = evidence();
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWER,
                List.of(sentence("步骤一", "e2", "e1"), sentence("步骤二", "e1")));
        var bound = validator.validate(draft, evidence);
        assertThat(bound.citations()).containsExactly(evidence.evidence().get(1), evidence.evidence().get(0));
        assertThat(bound.draft()).isSameAs(draft);
        assertThatThrownBy(() -> bound.citations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(bound.toString()).doesNotContain("步骤");
    }

    @Test void oneInventedReferenceRejectsEntireDraftInsteadOfKeepingOtherSentences() {
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWER,
                List.of(sentence("合法标签", "e1"), sentence("不存在来源", "e3")));
        assertThatThrownBy(() -> validator.validate(draft, evidence()))
                .isInstanceOf(KnowledgeGatewayException.class).hasMessage("PROTOCOL");
    }

    @Test void noEvidenceResponseHasNoFabricatedCitation() {
        var bound = validator.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.NO_EVIDENCE, List.of()), evidence());
        assertThat(bound.citations()).isEmpty();
        assertThatThrownBy(() -> validator.validate(new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWER,
                List.of(sentence("猜测", "e1"))), new RetrievalResult(evidence().indexVersion(), RetrievalResult.Mode.NONE, List.of())))
                .hasMessage("PROTOCOL");
    }

    @Test void citationValidityDoesNotClaimSemanticSupport() {
        // Deliberately contradictory content: this layer proves citation identity only.
        // T07 answer service/profile evaluation must not treat this as semantic correctness.
        var draft = new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWER, List.of(sentence("可以自动拨号", "e1")));
        var bound = validator.validate(draft, evidence());
        assertThat(bound.citations().get(0).chunk().text()).isEqualTo("不能自动拨号");
        assertThat(bound.draft().sentences().get(0).text()).isEqualTo("可以自动拨号");
    }

    @Test void immutableDraftRejectsInvalidTagsAndBounds() {
        var ids = new ArrayList<>(List.of("e1"));
        var sentence = new KnowledgeAnswerDraft.Sentence("答复", ids);
        ids.add("e2");
        assertThat(sentence.evidenceIds()).containsExactly("e1");
        assertThatThrownBy(() -> sentence("答复", "e0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sentence("答复", "e1", "e1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sentence("字".repeat(121), "e1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeAnswerDraft(KnowledgeAnswerDraft.Status.ANSWER, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static KnowledgeAnswerDraft.Sentence sentence(String text, String... ids) {
        return new KnowledgeAnswerDraft.Sentence(text, List.of(ids));
    }

    private static RetrievalResult evidence() {
        var version = new IndexVersion(new UUID(0, 1), 1, Optional.empty(), "token-v1", "chunk-v1");
        return new RetrievalResult(version, RetrievalResult.Mode.KEYWORD_ONLY, List.of(
                new RetrievalEvidence(new KnowledgeChunk(new UUID(0, 2), new UUID(0, 3), 1, 0,
                        "指南", "不能自动拨号", 0, 6, "chunk-v1"), 1),
                new RetrievalEvidence(new KnowledgeChunk(new UUID(0, 4), new UUID(0, 5), 1, 0,
                        "指南", "在首页打开守护", 0, 7, "chunk-v1"), 0.5)));
    }
}
