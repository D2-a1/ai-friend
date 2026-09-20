package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.aifriend.retrieval.application.KnowledgeGatewayException;
import com.aifriend.retrieval.domain.KnowledgeAnswerDraft;

class KnowledgeAnswerProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeAnswerProtocol protocol = new KnowledgeAnswerProtocol(mapper);
    private static final String VALID = """
            {"status":"ANSWER","sentences":[{"text":"在首页打开小友守护。","evidenceIds":["e1"]}]}
            """;

    @Test void normalAnswerAndExplicitNoEvidenceDecodeWithoutInventingFields() throws Exception {
        var answer = protocol.decode(envelope(VALID), "fixture");
        assertThat(answer.status()).isEqualTo(KnowledgeAnswerDraft.Status.ANSWER);
        assertThat(answer.sentences().get(0).text()).isEqualTo("在首页打开小友守护。");
        assertThat(answer.sentences().get(0).evidenceIds()).containsExactly("e1");
        assertThat(protocol.decode(envelope("{\"status\":\"NO_EVIDENCE\",\"sentences\":[]}"), "fixture").sentences()).isEmpty();
        assertThat(mapper.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)).isFalse();
    }

    @ParameterizedTest @MethodSource("invalidContents")
    void malformedOrExpandedInnerProtocolIsRejectedAsAWhole(String content) throws Exception {
        assertThatThrownBy(() -> protocol.decode(envelope(content), "fixture"))
                .isInstanceOf(KnowledgeGatewayException.class).hasMessage("PROTOCOL").hasNoCause();
    }

    static Stream<String> invalidContents() {
        String sentence = "{\"text\":\"步骤\",\"evidenceIds\":[\"e1\"]}";
        return Stream.of(
                "", "null", "[]", "```json\n" + VALID + "\n```", VALID + "{}",
                "{\"status\":\"ANSWER\",\"status\":\"NO_EVIDENCE\",\"sentences\":[]}",
                "{\"status\":\"ANSWER\",\"sentences\":[],\"url\":\"https://bad.invalid\"}",
                "{\"status\":\"ANSWER\"}", "{\"status\":\"OTHER\",\"sentences\":[]}",
                "{\"status\":\"ANSWER\",\"sentences\":[]}",
                "{\"status\":\"NO_EVIDENCE\",\"sentences\":[" + sentence + "]}",
                "{\"status\":\"ANSWER\",\"sentences\":[" + String.join(",", java.util.Collections.nCopies(4, sentence)) + "]}",
                VALID.replace("\"e1\"", "\"e5\""), VALID.replace("[\"e1\"]", "[]"),
                VALID.replace("[\"e1\"]", "[\"e1\",\"e1\"]"), VALID.replace("[\"e1\"]", "[1]"),
                VALID.replace("\"text\":", "\"text\":\"重复\",\"text\":"),
                VALID.replace("\"evidenceIds\":", "\"action\":\"send\",\"evidenceIds\":"),
                VALID.replace("在首页打开小友守护。", "字".repeat(121)),
                VALID.replace("在首页打开小友守护。", " "),
                "{\"status\":\"ANSWER\",\"sentences\":[",
                "{\"status\":\"ANSWER\",\"sentences\":null}");
    }

    @ParameterizedTest @MethodSource("badEnvelopes")
    void multipleChoicesToolsNonStopAndEnvelopeCorruptionAreRejected(String response) {
        assertThatThrownBy(() -> protocol.decode(response.getBytes(StandardCharsets.UTF_8), "fixture"))
                .isInstanceOf(KnowledgeGatewayException.class).hasMessage("PROTOCOL");
    }

    static Stream<String> badEnvelopes() throws Exception {
        var test = new KnowledgeAnswerProtocolTest();
        String normal = new String(test.envelope(VALID), StandardCharsets.UTF_8);
        return Stream.of(normal.replace("\"fixture\"", "\"other\""),
                normal.replace("\"stop\"", "\"length\""),
                normal.replace("\"index\":0", "\"index\":1"),
                normal.replace("\"index\":0", "\"index\":4294967296"),
                normal.replace("\"role\":\"assistant\"", "\"role\":\"user\""),
                normal.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"tool_calls\":[]"),
                normal.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"function_call\":{}"),
                normal.replace("\"role\":\"assistant\"", "\"role\":\"assistant\",\"refusal\":\"no\""),
                normal.replace("\"model\":\"fixture\"", "\"model\":\"fixture\",\"model\":\"fixture\""),
                normal + "{}", "{\"model\":\"fixture\",\"choices\":[]}",
                "{\"model\":\"fixture\",\"choices\":[{},{}]}",
                "{\"model\":\"fixture\",\"choices\":{}}", "null");
    }

    @Test void byteAndCodePointBoundariesAreEnforced() throws Exception {
        assertThatThrownBy(() -> protocol.decode(new byte[65537], "fixture")).hasMessage("PROTOCOL");
        assertThatThrownBy(() -> protocol.decode(null, "fixture")).hasMessage("PROTOCOL");
        var draft = protocol.decode(envelope(VALID.replace("在首页打开小友守护。", "😀".repeat(120))), "fixture");
        assertThat(draft.sentences().get(0).text().codePointCount(0, draft.sentences().get(0).text().length())).isEqualTo(120);
        assertThatThrownBy(() -> protocol.decode(envelope(VALID.replace("在首页打开小友守护。", "😀".repeat(121))), "fixture"))
                .hasMessage("PROTOCOL");
    }

    @Test void optionalReasoningIsNeverReturnedAsAnswerOrLogged() throws Exception {
        var root = mapper.readTree(envelope(VALID));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("choices").get(0).get("message"))
                .put("reasoning_content", "private ignored reasoning");
        var draft = protocol.decode(mapper.writeValueAsBytes(root), "fixture");
        assertThat(draft.toString()).doesNotContain("private", "首页");
        assertThat(draft.sentences().get(0).toString()).doesNotContain("首页");
    }

    private byte[] envelope(String content) throws Exception {
        return mapper.writeValueAsBytes(Map.of("model", "fixture", "object", "chat.completion",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content)))));
    }
}
