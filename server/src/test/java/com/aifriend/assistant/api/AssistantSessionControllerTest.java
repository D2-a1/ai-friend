package com.aifriend.assistant.api;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.retrieval.domain.*;
import com.aifriend.shared.api.GlobalExceptionHandler;
import com.aifriend.shared.security.PublicIdCodec;

/** HTTP序列化和路由真实运行；认证上下文/业务依赖模拟，不调用数据库或模型。 */
class AssistantSessionControllerTest {
    private final UUID owner=UUID.randomUUID(),sid=UUID.randomUUID(),rid=UUID.randomUUID();
    private final String key="request-key-000001";
    private final AssistantSessionRepository sessions=mock(AssistantSessionRepository.class);
    private final AssistantSessionService service=mock(AssistantSessionService.class);
    private final AssistantResultReader reader=mock(AssistantResultReader.class);
    private final AssistantSessionLifecyclePort lifecycle=mock(AssistantSessionLifecyclePort.class);
    private final Instant now=Instant.parse("2026-09-10T10:00:00Z");
    private AssistantSession session;
    private MockMvc mvc;
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider=mock(ObjectProvider.class); when(provider.getObject()).thenReturn(value); return provider;
    }
    private MockMvc web(boolean enabled,boolean graph) {
        return MockMvcBuilders.standaloneSetup(new AssistantSessionController(provider(sessions),provider(service),provider(reader),lifecycle,enabled,graph))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new AssistantApiExceptionHandler(),new GlobalExceptionHandler()).build();
    }
    @BeforeEach void setup() {
        session=AssistantSession.create(sid,owner,Purpose.PUBLIC_KNOWLEDGE,"policy-v1",now);
        when(sessions.find(owner,sid)).thenReturn(Optional.of(session));
        when(sessions.create(owner,Purpose.PUBLIC_KNOWLEDGE,key)).thenReturn(session);
        Jwt jwt=Jwt.withTokenValue("synthetic").header("alg","none").subject(PublicIdCodec.userId(owner)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt)); mvc=web(true,true);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private String createBody() { return "{\"purpose\":\"PUBLIC_KNOWLEDGE\",\"clientRequestId\":\""+key+"\"}"; }
    private String question(String content) { return "{\"expectedVersion\":0,\"requestKey\":\""+key+"\",\"locale\":\"zh-CN\",\"appVersionCode\":1,"+content+"}"; }
    private AssistantResultReader.Result processing() { return new AssistantResultReader.Result(1,rid,AssistantTurnRequest.State.PROCESSING,null,Optional.empty(),RetrievalResult.Mode.NONE); }
    @Test void createUsesJwtOwnerAndOnlyReturnsMetadata() throws Exception {
        mvc.perform(post("/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(sid.toString()))
                .andExpect(jsonPath("$.data.purpose").value("PUBLIC_KNOWLEDGE"))
                .andExpect(jsonPath("$.data.expiresAt").exists()).andExpect(jsonPath("$.data.owner").doesNotExist())
                .andExpect(jsonPath("$.data.conversation").doesNotExist());
        verify(sessions).create(owner,Purpose.PUBLIC_KNOWLEDGE,key); verifyNoInteractions(service,reader,lifecycle);
    }
    @Test void askAcceptsFiveHundredUnicodeCodePointsAndReturnsProcessing() throws Exception {
        when(service.ask(eq(owner),eq(sid),any())).thenReturn(processing());
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"text\":\""+"😀".repeat(500)+"\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.answerMode").value("NONE")).andExpect(jsonPath("$.data.requestKey").value(key));
        verify(service).ask(eq(owner),eq(sid),argThat(q->((AssistantQuestion.PublicText)q.payload()).text().codePointCount(0,1000)==500));
    }
    @Test void expiredResultHasAnExplicitFailureExplanationWithoutEvidence() throws Exception {
        var expired=new AssistantResultReader.Result(1,rid,AssistantTurnRequest.State.EXPIRED,null,
                Optional.empty(),RetrievalResult.Mode.NONE);
        when(service.ask(eq(owner),eq(sid),any())).thenReturn(expired);
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"text\":\"公开说明\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.reasonCode").value("RESULT_STALE"))
                .andExpect(jsonPath("$.data.text").value("本次查询没有完成，请重新开始。不会自动重试。"))
                .andExpect(jsonPath("$.data.citations").isEmpty())
                .andExpect(jsonPath("$.data.candidates").isEmpty());
        verify(service,times(1)).ask(eq(owner),eq(sid),any());
    }

    @Test void oversizedUnicodeQuestionIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"text\":\""+"😀".repeat(501)+"\""))).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(service,reader);
    }
    @Test void bodyIsBoundedBeforeJsonParsing() throws Exception {
        mvc.perform(post("/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content(" ".repeat(16385)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.data.reasonCode").value("INPUT_LIMIT"));
        verifyNoInteractions(sessions,service,reader);
    }
    @Test void malformedUtf8IsRejectedWithoutReplacement() throws Exception {
        mvc.perform(post("/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content(new byte[]{(byte)0xc3,0x28}))
                .andExpect(status().isUnprocessableEntity()); verifyNoInteractions(sessions,service,reader);
    }
    @ParameterizedTest @ValueSource(strings={
            "\"text\":null", "\"text\":123", "\"text\":\"x\",\"owner\":\"other\"",
            "\"text\":\"x\",\"text\":\"y\"", "\"text\":\"x\",\"graphQuery\":{\"queryType\":\"LIST_CONTACTS\"}",
            "\"graphQuery\":{\"queryType\":\"RUN_CYPHER\"}",
            "\"graphQuery\":{\"queryType\":\"LIST_CONTACTS\",\"aliasText\":\"x\"}",
            "\"graphQuery\":{\"queryType\":\"LIST_ALIASES\"}"})
    void invalidPayloadDoesNotExecute(String content) throws Exception {
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON).content(question(content)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.reasonCode").value("INVALID_REQUEST"));
        verifyNoInteractions(service,reader);
    }
    @ParameterizedTest @ValueSource(strings={"\"1\"","1.5","2147483648","9223372036854775808"})
    void numbersCannotBeCoerced(String number) throws Exception {
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"text\":\"x\"").replace("\"appVersionCode\":1","\"appVersionCode\":"+number)))
                .andExpect(status().isUnprocessableEntity()); verifyNoInteractions(service);
    }
    @Test void graphCannotCrossPublicSessionPurpose() throws Exception {
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"graphQuery\":{\"queryType\":\"LIST_CONTACTS\"}")))
                .andExpect(status().isUnprocessableEntity()); verifyNoInteractions(service);
    }
    @Test void privateGraphDispatchUsesBoundedPayload() throws Exception {
        when(sessions.find(owner,sid)).thenReturn(Optional.of(AssistantSession.create(sid,owner,Purpose.CONTACT_GRAPH,"policy-v1",now)));
        when(service.ask(eq(owner),eq(sid),any())).thenReturn(processing());
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON)
                .content(question("\"graphQuery\":{\"queryType\":\"LIST_CONTACTS\"}"))).andExpect(status().isOk());
        verify(service).ask(eq(owner),eq(sid),argThat(q->q.payload().purpose()==Purpose.CONTACT_GRAPH));
    }
    @Test void getPreservesEvidenceAndActualHybridModeWithoutRegeneration() throws Exception {
        var chunk=new KnowledgeChunk(UUID.randomUUID(),UUID.randomUUID(),1,0,"","说明",0,2,"v1");
        var answer=new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.EVIDENCE_ONLY,Mode.EXTRACTIVE,AssistantReason.NONE,
                "说明",List.of(new RetrievalEvidence(chunk,1)),List.of());
        when(reader.read(owner,sid,key)).thenReturn(Optional.of(new AssistantResultReader.Result(2,rid,AssistantTurnRequest.State.COMPLETED,2L,
                Optional.of(answer),RetrievalResult.Mode.HYBRID)));
        mvc.perform(get("/assistant/sessions/{id}/requests/{key}",sid,key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrievalMode").value("HYBRID"))
                .andExpect(jsonPath("$.data.citations[0].evidenceId").value("e1"))
                .andExpect(jsonPath("$.data.citations[0].title").value("未命名片段"))
                .andExpect(jsonPath("$.data.citations[0].sourceEnd").value(2))
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist()); verifyNoInteractions(service);
    }
    @Test void featureOffBlocksCreationButAllowsCloseWithActualMetadata() throws Exception {
        mvc=web(false,false);
        when(lifecycle.close(owner,sid,0)).thenReturn(new AssistantSessionLifecyclePort.Closed(sid,1,AssistantSession.State.CLOSED,Purpose.PUBLIC_KNOWLEDGE,now.plusSeconds(300)));
        mvc.perform(post("/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data.reasonCode").value("CONFIG_INVALID"));
        mvc.perform(delete("/assistant/sessions/{id}",sid).param("expectedVersion","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CLOSED"))
                .andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.purpose").value("PUBLIC_KNOWLEDGE"));
        verify(lifecycle).close(owner,sid,0); verifyNoInteractions(service,reader);
    }
    @ParameterizedTest @ValueSource(strings={"ACCESS_REVOKED","VERSION_MISMATCH","RESOURCE_LIMIT","STORAGE_UNAVAILABLE"})
    void finiteBusinessReasonsKeepGenericContractCode(String reason) throws Exception {
        when(service.ask(eq(owner),eq(sid),any())).thenThrow(new AssistantSessionException(AssistantReason.valueOf(reason)));
        int expected=switch(reason) {case "ACCESS_REVOKED"->403;case "VERSION_MISMATCH"->409;case "RESOURCE_LIMIT"->429;default->503;};
        mvc.perform(post("/assistant/sessions/{id}/questions",sid).contentType(MediaType.APPLICATION_JSON).content(question("\"text\":\"x\"")))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.data.reasonCode").value(reason));
    }
    @Test void foreignSessionDoesNotReadAnswer() throws Exception {
        when(sessions.find(owner,sid)).thenReturn(Optional.empty());
        mvc.perform(get("/assistant/sessions/{id}/requests/{key}",sid,key)).andExpect(status().isNotFound()); verifyNoInteractions(reader,service);
    }
    @Test void unauthenticatedCannotCreateOrClose() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/assistant/sessions").contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/assistant/sessions/{id}",sid).param("expectedVersion","0")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions,service,reader,lifecycle);
    }
}
