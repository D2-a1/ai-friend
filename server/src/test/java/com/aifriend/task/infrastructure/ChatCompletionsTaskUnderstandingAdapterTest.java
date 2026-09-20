package com.aifriend.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskConversationContext;
import com.aifriend.task.application.TaskConversationPreferences;
import com.aifriend.task.application.TaskDraftRevision;
import com.aifriend.task.application.TaskInterpretationOutcome;
import com.aifriend.task.application.TaskMatchedContactView;
import com.aifriend.task.application.TaskPayload;
import com.aifriend.task.application.TaskProcessingVersionsView;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSemanticModelProperties;
import com.aifriend.task.application.TaskSemanticModelProperties.ThinkingMode;
import com.aifriend.task.application.TaskSemanticModelProperties.TokenLimitField;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.task.application.TaskUnderstandingView;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskRevisionMode;

class ChatCompletionsTaskUnderstandingAdapterTest {

    private static final URI ENDPOINT =
            URI.create("https://chat.vendor-one.net/v1/chat/completions");
    private static final URI ALTERNATE_ENDPOINT =
            URI.create("https://chat.vendor-two.cn/compatible-mode/v1/chat/completions");
    private static final String API_KEY = "test-semantic-model-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private ChatCompletionsTaskUnderstandingAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new ChatCompletionsTaskUnderstandingAdapter(
                properties(), builder.build(), objectMapper,
                CircuitBreaker.ofDefaults("semantic-test"),
                Bulkhead.ofDefaults("semantic-test"));
    }

    @Test
    void configuredRequestDialectMustNotDependOnEndpointHost()
            throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer alternateServer =
                MockRestServiceServer.bindTo(builder).build();
        ChatCompletionsTaskUnderstandingAdapter alternateAdapter =
                new ChatCompletionsTaskUnderstandingAdapter(
                        new TaskSemanticModelProperties(
                                true, ALTERNATE_ENDPOINT,
                                Set.of("chat.vendor-two.cn"),
                                "semantic-model-v1", API_KEY,
                                TokenLimitField.MAX_COMPLETION_TOKENS,
                                256, 0.25D, ThinkingMode.DISABLED,
                                Duration.ofSeconds(2), Duration.ofSeconds(6)),
                        builder.build(), objectMapper,
                        CircuitBreaker.ofDefaults("semantic-alternate-test"),
                        Bulkhead.ofDefaults("semantic-alternate-test"));
        alternateServer.expect(requestTo(ALTERNATE_ENDPOINT))
                .andExpect(content().string(containsString(
                        "\"max_completion_tokens\":256")))
                .andExpect(content().string(containsString(
                        "\"enable_thinking\":false")))
                .andExpect(content().string(containsString(
                        "\"temperature\":0.25")))
                .andExpect(content().string(not(containsString("\"max_tokens\""))))
                .andRespond(withSuccess(
                        completion(decision(
                                "PATCH", "VOICE_CALL", "REMATCH", "KEEP",
                                null, null)),
                        MediaType.APPLICATION_JSON));

        TaskDraftRevision revision = alternateAdapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 360),
                        word("打电话", 380, 760)),
                800,
                TaskRevisionMode.FULL_RETRY);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        alternateServer.verify();
    }

    @Test
    void objectThinkingDialectMustBeSelectedOnlyByExternalConfiguration()
            throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer objectServer =
                MockRestServiceServer.bindTo(builder).build();
        ChatCompletionsTaskUnderstandingAdapter objectAdapter =
                new ChatCompletionsTaskUnderstandingAdapter(
                        new TaskSemanticModelProperties(
                                true, ALTERNATE_ENDPOINT,
                                Set.of("chat.vendor-two.cn"),
                                "semantic-model-v2", API_KEY,
                                TokenLimitField.MAX_TOKENS,
                                180, 0.0D, ThinkingMode.OBJECT_DISABLED,
                                Duration.ofSeconds(2), Duration.ofSeconds(6)),
                        builder.build(), objectMapper,
                        CircuitBreaker.ofDefaults("semantic-object-test"),
                        Bulkhead.ofDefaults("semantic-object-test"));
        objectServer.expect(requestTo(ALTERNATE_ENDPOINT))
                .andExpect(content().string(containsString(
                        "\"thinking\":{\"type\":\"disabled\"}")))
                .andExpect(content().string(not(containsString(
                        "\"enable_thinking\""))))
                .andExpect(content().string(containsString("\"max_tokens\":180")))
                .andRespond(withSuccess(
                        completion(decision(
                                "PATCH", "VOICE_CALL", "REMATCH", "KEEP",
                                null, null)),
                        MediaType.APPLICATION_JSON));

        TaskDraftRevision revision = objectAdapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 360),
                        word("打电话", 380, 760)),
                800,
                TaskRevisionMode.FULL_RETRY);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        objectServer.verify();
    }

    @Test
    void firstRequestMustUseModelAndStillRequireAcousticContactRematch()
            throws Exception {
        expect(decision("PATCH", "VIDEO_CALL", "REMATCH", "KEEP", null, null),
                true);
        TaskSpeechRecognition recognition = recognition(
                word("跟", 0, 100), word("老大", 120, 360),
                word("开", 380, 480), word("视频", 500, 800));

        TaskDraftRevision revision = adapter.revise(
                null, recognition, 900, TaskRevisionMode.FULL_RETRY);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VIDEO_CALL);
        assertThat(revision.replaceContact()).isTrue();
        assertThat(revision.replaceSourceAudio()).isFalse();
        server.verify();
    }

    @Test
    void actionCorrectionMustPreserveVerifiedContactAndChangeOnlyAction()
            throws Exception {
        expect(decision("PATCH", "VIDEO_CALL", "KEEP", "KEEP", null, null),
                true);

        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.VOICE_CALL, null),
                recognition(word("不对", 0, 180), word("是", 200, 280),
                        word("视频通话", 300, 760)),
                800,
                TaskRevisionMode.CORRECTION);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VIDEO_CALL);
        assertThat(revision.replaceContact()).isFalse();
        assertThat(revision.messageText()).isNull();
        server.verify();
    }

    @Test
    void messageReplacementMustComeOnlyFromBoundedRecognizedWordRange()
            throws Exception {
        expect(decision("PATCH", "KEEP", "KEEP", "REPLACE", 3, 5), false);

        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.SEND_MESSAGE, "明天回来"),
                recognition(word("不对", 0, 160), word("内容", 180, 340),
                        word("改成", 360, 540), word("后天", 560, 760),
                        word("回来", 780, 1_020)),
                1_100,
                TaskRevisionMode.CORRECTION);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.messageText()).isEqualTo("后天回来");
        assertThat(revision.replaceSourceAudio()).isTrue();
        assertThat(revision.recognition().effectiveAudioRanges()).singleElement()
                .satisfies(range -> {
                    assertThat(range.startMs()).isEqualTo(560);
                    assertThat(range.endMs()).isEqualTo(1_020);
                });
        server.verify();
    }

    @Test
    void outOfRangeMessageEvidenceMustRequestOneProtocolCorrection()
            throws Exception {
        expect(decision("PATCH", "KEEP", "KEEP", "REPLACE", 3, 99), false);
        expectCorrection(
                decision("PATCH", "KEEP", "KEEP", "REPLACE", 2, 4), false);

        TaskDraftRevision revision = adapter.revise(
                payload(TaskIntent.SEND_MESSAGE, "明天回来"),
                recognition(word("内容", 0, 180), word("改成", 200, 400),
                        word("后天", 420, 620), word("回来", 640, 860)),
                900,
                TaskRevisionMode.CORRECTION);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.messageText()).isEqualTo("后天回来");
        assertThat(revision.replaceSourceAudio()).isTrue();
        server.verify();
    }

    @Test
    void extraExecuteFieldMustBeRejectedAndNeverBecomeConfirmation()
            throws Exception {
        String invalid = "{\"decision\":\"PATCH\",\"action\":\"VOICE_CALL\","
                + "\"contact\":\"KEEP\",\"message\":\"KEEP\","
                + "\"clarification\":\"NONE\","
                + "\"contentStartWord\":null,\"contentEndWord\":null,"
                + "\"execute\":true}";
        expect(invalid, false);
        expectCorrection(invalid, false);

        assertThatThrownBy(() -> adapter.revise(
                        payload(TaskIntent.VOICE_CALL, null),
                        recognition(word("确认", 0, 360)),
                        500,
                        TaskRevisionMode.CORRECTION))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SEMANTIC_MODEL_PROTOCOL_INVALID));
        server.verify();
    }

    @Test
    void providerStyleExecuteResponseMustBeCorrectedWithoutRelaxingProtocol()
            throws Exception {
        String invalid = "{\"decision\":\"execute\","
                + "\"action\":\"make_voice_call\",\"contact\":\"老大\","
                + "\"message\":\"\",\"clarification\":\"\","
                + "\"contentStartWord\":\"\",\"contentEndWord\":\"\"}";
        expect(invalid, false);
        expectCorrection(
                decision("PATCH", "VOICE_CALL", "REMATCH", "KEEP", null, null),
                false);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("联系", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VOICE_CALL);
        assertThat(revision.replaceContact()).isTrue();
        server.verify();
    }

    @Test
    void missingFinishReasonMustFailClosed() throws Exception {
        String modelContent = decision(
                "PATCH", "VOICE_CALL", "REMATCH", "KEEP", null, null);
        String response = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", modelContent)))));
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        expectCorrection(
                decision("PATCH", "VOICE_CALL", "REMATCH", "KEEP", null, null),
                false);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("联系", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY);

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VOICE_CALL);
        assertThat(revision.replaceContact()).isTrue();
        server.verify();
    }

    @Test
    void duplicateDecisionFieldMustFailClosed() throws Exception {
        String invalid = "{\"decision\":\"ASK\",\"decision\":\"PATCH\","
                + "\"action\":\"VOICE_CALL\",\"contact\":\"REMATCH\","
                + "\"message\":\"KEEP\",\"clarification\":\"NONE\","
                + "\"contentStartWord\":null,"
                + "\"contentEndWord\":null}";
        expect(invalid, false);
        expectCorrection(invalid, false);

        assertThatThrownBy(() -> adapter.revise(
                        null,
                        recognition(word("给", 0, 100), word("老大", 120, 350),
                                word("打电话", 370, 720)),
                        800,
                        TaskRevisionMode.FULL_RETRY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SEMANTIC_MODEL_PROTOCOL_INVALID));
        server.verify();
    }

    @Test
    void callTypeClarificationUsesServerSidePreferenceOnlyForDraft() throws Exception {
        String clarification = "{\"decision\":\"ASK\",\"action\":\"KEEP\","
                + "\"contact\":\"KEEP\",\"message\":\"KEEP\","
                + "\"clarification\":\"CALL_TYPE\","
                + "\"contentStartWord\":null,\"contentEndWord\":null}";
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("联系", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY,
                new TaskConversationPreferences("NORMAL", "STANDARD", "VIDEO"));

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VIDEO_CALL);
        assertThat(revision.replaceContact()).isTrue();
        assertThat(revision.replaceSourceAudio()).isFalse();
        server.verify();
    }

    @Test
    void defaultPreferenceKeepsAmbiguousCallInSameSessionRetry() throws Exception {
        String clarification = "{\"decision\":\"ASK\",\"action\":\"KEEP\","
                + "\"contact\":\"KEEP\",\"message\":\"KEEP\","
                + "\"clarification\":\"CALL_TYPE\","
                + "\"contentStartWord\":null,\"contentEndWord\":null}";
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("联系", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome())
                .isEqualTo(TaskInterpretationOutcome.NEEDS_RETRY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.HELP);
        assertThat(revision.replaceContact()).isFalse();
        server.verify();
    }

    @Test
    void explicitPhoneCallOverridesIncorrectCallTypeClarification() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"CALL_TYPE","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("打电话", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VOICE_CALL);
        assertThat(revision.replaceContact()).isTrue();
        server.verify();
    }

    @Test
    void explicitVideoCallOverridesIncorrectCallTypeClarification() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"CALL_TYPE","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老大", 120, 350),
                        word("打视频电话", 370, 720)),
                800,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.VIDEO_CALL);
        assertThat(revision.replaceContact()).isTrue();
        server.verify();
    }

    @Test
    void explicitMessageOverridesIncorrectCallTypeClarification() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"CALL_TYPE","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老二", 120, 350),
                        word("发信息", 370, 650), word("明天见", 680, 980)),
                1_000,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome()).isEqualTo(TaskInterpretationOutcome.READY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.SEND_MESSAGE);
        assertThat(revision.messageText()).isEqualTo("明天见");
        assertThat(revision.replaceContact()).isTrue();
        assertThat(revision.replaceSourceAudio()).isTrue();
        server.verify();
    }

    @Test
    void explicitMessageWithoutContentNeverAsksForCallType() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"CALL_TYPE","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老二", 120, 350),
                        word("发信息", 370, 650)),
                700,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome())
                .isEqualTo(TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT);
        assertThat(revision.intent()).isEqualTo(TaskIntent.SEND_MESSAGE);
        assertThat(revision.messageText()).isNull();
        assertThat(revision.replaceContact()).isTrue();
        assertThat(revision.replaceSourceAudio()).isFalse();
        server.verify();
    }

    @Test
    void explicitMessageOverridesIncorrectOtherClarification() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"OTHER","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("给", 0, 100), word("老二", 120, 350),
                        word("发信息", 370, 650)),
                700,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome())
                .isEqualTo(TaskInterpretationOutcome.NEEDS_CONTENT_REPEAT);
        assertThat(revision.intent()).isEqualTo(TaskIntent.SEND_MESSAGE);
        assertThat(revision.replaceContact()).isTrue();
        server.verify();
    }

    @Test
    void contactAliasContainingVideoMustNotInventVideoCallType() throws Exception {
        String clarification = """
                {"decision":"ASK","action":"KEEP","contact":"KEEP","message":"KEEP",
                "clarification":"CALL_TYPE","contentStartWord":null,"contentEndWord":null}
                """.replaceAll("\\s+", "");
        expect(clarification, true);

        TaskDraftRevision revision = adapter.revise(
                null,
                recognition(word("联系", 0, 180), word("视频哥", 200, 550)),
                700,
                TaskRevisionMode.FULL_RETRY,
                TaskConversationPreferences.safeDefaults());

        assertThat(revision.outcome())
                .isEqualTo(TaskInterpretationOutcome.NEEDS_RETRY);
        assertThat(revision.intent()).isEqualTo(TaskIntent.HELP);
        assertThat(revision.replaceContact()).isFalse();
        server.verify();
    }
    @Test
    void timeoutMustReturnUnavailableWithoutKeywordFallback() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(request -> {
                    throw new SocketTimeoutException("timed out");
                });

        assertThatThrownBy(() -> adapter.revise(
                        null,
                        recognition(word("跟", 0, 100), word("老大", 120, 350),
                                word("开视频", 370, 720)),
                        800,
                        TaskRevisionMode.FULL_RETRY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SEMANTIC_MODEL_UNAVAILABLE));
        server.verify();
    }

    @Test
    void providerFailureMustNotFallBackToKeywordGuessing() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.revise(
                        null,
                        recognition(word("给", 0, 100), word("老大", 120, 350),
                                word("打电话", 370, 720)),
                        800,
                        TaskRevisionMode.FULL_RETRY))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.SEMANTIC_MODEL_UNAVAILABLE));
        server.verify();
    }

    private void expect(String modelContent, boolean verifyNoContactIdentifier)
            throws JsonProcessingException {
        expect(modelContent, verifyNoContactIdentifier, false);
    }

    private void expectCorrection(
            String modelContent,
            boolean verifyNoContactIdentifier) throws JsonProcessingException {
        expect(modelContent, verifyNoContactIdentifier, true);
    }

    private void expect(
            String modelContent,
            boolean verifyNoContactIdentifier,
            boolean correction) throws JsonProcessingException {
        var expectation = server.expect(requestTo(ENDPOINT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().string(containsString("contextual-task-draft-v2")))
                .andExpect(content().string(containsString("response_format")))
                .andExpect(content().string(containsString("\"max_tokens\":180")))
                .andExpect(content().string(not(containsString(
                        "\"max_completion_tokens\""))))
                .andExpect(content().string(not(containsString(
                        "\"enable_thinking\""))))
                .andExpect(content().string(not(containsString("ambiguousCall"))))
                .andExpect(content().string(not(containsString("ASK_EVERY_TIME"))));
        if (correction) {
            expectation.andExpect(content().string(containsString(
                    "上一次输出未通过固定协议校验")));
        } else {
            expectation.andExpect(content().string(not(containsString(
                    "上一次输出未通过固定协议校验"))));
        }
        if (verifyNoContactIdentifier) {
            expectation.andExpect(content().string(not(containsString("ct_demo1234"))));
        }
        expectation.andRespond(withSuccess(
                completion(modelContent), MediaType.APPLICATION_JSON));
    }

    private String completion(String modelContent) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "finish_reason", "stop",
                        "message", Map.of("content", modelContent)))));
    }

    private String decision(
            String decision,
            String action,
            String contact,
            String message,
            Integer start,
            Integer end) throws JsonProcessingException {
        var node = objectMapper.createObjectNode();
        node.put("decision", decision);
        node.put("action", action);
        node.put("contact", contact);
        node.put("message", message);
        node.put("clarification", "NONE");
        if (start == null) node.putNull("contentStartWord");
        else node.put("contentStartWord", start);
        if (end == null) node.putNull("contentEndWord");
        else node.put("contentEndWord", end);
        return objectMapper.writeValueAsString(node);
    }

    private TaskSemanticModelProperties properties() {
        return new TaskSemanticModelProperties(
                true, ENDPOINT, Set.of("chat.vendor-one.net"),
                "semantic-model-v1", API_KEY,
                TokenLimitField.MAX_TOKENS, 180, 0.0D, ThinkingMode.OMIT,
                Duration.ofSeconds(2), Duration.ofSeconds(6));
    }

    private TaskPayload payload(TaskIntent intent, String messageText) {
        TaskMatchedContactView contact = new TaskMatchedContactView(
                "ct_demo1234", "老大", "老大");
        TaskUnderstandingView understanding = new TaskUnderstandingView(
                intent, contact, "给老大打电话", messageText, List.of(),
                List.of(), 0.9D, new TaskProcessingVersionsView(
                        "wugang", "dialect-v1", "asr-v1", "assist-v1",
                        "fusion-v1", "align-v1", "template-v1", "threshold-v1"));
        return new TaskPayload(
                null, understanding, List.of(), "旧摘要", Set.of(),
                null, null, null, null,
                TaskConversationContext.initial("给老大打电话", "旧摘要"));
    }

    private TaskSpeechRecognition recognition(TaskRecognizedWord... words) {
        List<TaskRecognizedWord> wordList = List.of(words);
        StringJoiner transcript = new StringJoiner(" ");
        wordList.forEach(word -> transcript.add(word.text()));
        TaskTranscriptCandidate primary = new TaskTranscriptCandidate(
                transcript.toString(), wordList, 0.9D,
                TaskAsrSource.PRIMARY, "asr-v1");
        return new TaskSpeechRecognition(
                primary.transcript(), List.of(primary), List.of(), 0.9D,
                "asr-v1", "assist-v1", "fusion-v1", "align-v1");
    }

    private TaskRecognizedWord word(String text, int startMs, int endMs) {
        return new TaskRecognizedWord(text, startMs, endMs, 0.9D);
    }
}
