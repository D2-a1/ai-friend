package com.aifriend.assistant.api;

import java.time.ZoneOffset;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.api.AssistantApiModels.*;
import com.aifriend.assistant.domain.AssistantAnswer.Status;
import com.aifriend.assistant.domain.AssistantAnswer.Mode;
import com.aifriend.knowledge.domain.GraphQueryType;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.error.*;
import com.aifriend.shared.security.CurrentUser;

/**
 * 独立只读问答HTTP入口。owner仅来自已验证JWT，拒绝未知/重复字段及混合用途。
 * 返回独立契约DTO，不返回加密证明、租约、内部上下文或联系执行资格。
 * @author Codex
 * @since 1.0.0
 */
@RestController
@RequestMapping("/assistant/sessions")
public final class AssistantSessionController {
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(4096).maxNumberLength(20).build()).build()).build();
    private final ObjectProvider<AssistantSessionRepository> sessions;
    private final ObjectProvider<AssistantSessionService> service;
    private final ObjectProvider<AssistantResultReader> reader;
    private final AssistantSessionLifecyclePort lifecycle;
    private final boolean enabled,graphEnabled;
    /**
     * 注入按开关提供的问答组件和始终可用的清理端口。
     * @param sessions 条件会话仓储
     * @param service 条件问答服务
     * @param reader 条件安全读取器
     * @param lifecycle 关闭时也可清理
     * @param enabled 知识总开关
     * @param graphEnabled 私人查询开关
     */
    public AssistantSessionController(ObjectProvider<AssistantSessionRepository> sessions,ObjectProvider<AssistantSessionService> service,
            ObjectProvider<AssistantResultReader> reader,AssistantSessionLifecyclePort lifecycle,
            @Value("${ai-friend.knowledge.enabled:false}") boolean enabled,@Value("${ai-friend.knowledge.graph-enabled:false}") boolean graphEnabled) {
        this.sessions=sessions;this.service=service;this.reader=reader;this.lifecycle=lifecycle;this.enabled=enabled;this.graphEnabled=graphEnabled;
    }
    /**
     * 在认证所有者范围内按用途幂等创建会话。
     * @param jwt 认证主体
     * @param body 严格请求
     * @return 新建或同键会话
     */
    @PostMapping(consumes="application/json")
    public ApiResponse<Session> create(@AuthenticationPrincipal Jwt jwt,java.io.InputStream body) {
        UUID owner=owner(jwt); JsonNode input=parse(body,Set.of("purpose","clientRequestId"));
        Purpose purpose=Purpose.valueOf(text(input,"purpose")); gate(purpose);
        var session=sessions.getObject().create(owner,purpose,AssistantQuestion.requireKey(text(input,"clientRequestId")));
        return ApiResponse.success(session(session.id(),session.purpose(),session.state().name(),session.version(),session.expiresAt()));
    }
    /**
     * 验证问题用途与会话一致后提交有界问答。
     * @param jwt 认证主体
     * @param id 会话
     * @param body 严格问题
     * @return 当前受检结果
     */
    @PostMapping(value="/{id}/questions",consumes="application/json")
    public ApiResponse<AssistantQuestionResult> ask(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,java.io.InputStream body) {
        UUID owner=owner(jwt); JsonNode input=parse(body,Set.of("expectedVersion","requestKey","locale","appVersionCode","text","graphQuery"));
        boolean publicText=input.has("text"),privateGraph=input.has("graphQuery");
        if(publicText==privateGraph) throw invalid();
        AssistantQuestion.Payload payload;
        if(publicText) payload=new AssistantQuestion.PublicText(text(input,"text"));
        else {
            JsonNode graph=object(input.get("graphQuery"),Set.of("queryType","contactId","aliasText"));
            payload=new AssistantQuestion.PrivateGraph(GraphQueryType.valueOf(text(graph,"queryType")),
                    graph.has("contactId")?UUID.fromString(text(graph,"contactId")):null,graph.has("aliasText")?text(graph,"aliasText"):null);
        }
        var question=new AssistantQuestion(number(input,"expectedVersion"),text(input,"requestKey"),text(input,"locale"),
                Math.toIntExact(number(input,"appVersionCode")),payload);
        gate(payload.purpose());
        var current=sessions.getObject().find(owner,id).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        if(current.purpose()!=payload.purpose()) throw invalid();
        return ApiResponse.success(result(question.requestKey(),service.getObject().ask(owner,id,question)));
    }
    /**
     * 重新校验来源和授权后读取已有请求，不重新调用模型。
     * @param jwt 认证主体
     * @param id 会话
     * @param key 原始请求键
     * @return 不重新生成的当前结果
     */
    @GetMapping("/{id}/requests/{key}")
    public ApiResponse<AssistantQuestionResult> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable String key) {
        UUID owner=owner(jwt); gate(Purpose.PUBLIC_KNOWLEDGE); AssistantQuestion.requireKey(key);
        var current=sessions.getObject().find(owner,id).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND)); gate(current.purpose());
        var value=reader.getObject().read(owner,id,key).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
        return ApiResponse.success(result(key,value));
    }
    /**
     * 按期望版本关闭会话并清理其敏感结果。
     * @param jwt 认证主体
     * @param id 会话
     * @param expectedVersion 期望版本
     * @return 清理后的元数据，功能关闭仍可调用
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Session> close(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @RequestParam long expectedVersion) {
        UUID owner=owner(jwt); if(expectedVersion<0) throw invalid();
        var closed=lifecycle.close(owner,id,expectedVersion);
        return ApiResponse.success(session(closed.sessionId(),closed.purpose(),closed.state().name(),closed.version(),closed.expiresAt()));
    }
    private void gate(Purpose purpose) { if(!enabled || purpose==Purpose.CONTACT_GRAPH && !graphEnabled) throw new AssistantSessionException(AssistantReason.CONFIG_INVALID); }
    private static UUID owner(Jwt jwt) { if(jwt==null) throw new BusinessException(ErrorCode.AUTH_REQUIRED);return CurrentUser.from(jwt).id(); }
    private static Session session(UUID id,Purpose purpose,String state,long version,java.time.Instant expiry) {
        return new Session(id,purpose,AssistantSession.State.valueOf(state),version,expiry.atOffset(ZoneOffset.UTC));
    }
    private static AssistantQuestionResult result(String key,AssistantResultReader.Result value) {
        var answer=value.answer().orElse(null);
        Status status=answer!=null?answer.status():
                value.state()==AssistantTurnRequest.State.PROCESSING?Status.PROCESSING:Status.UNAVAILABLE;
        AssistantReason reason=answer!=null?answer.reason():switch(value.state()) {
            case PROCESSING -> AssistantReason.NONE;
            case INVALIDATED -> AssistantReason.ACCESS_REVOKED;
            case CANCELLED -> AssistantReason.SESSION_CLOSED;
            default -> AssistantReason.RESULT_STALE;
        };
        List<KnowledgeCitation> citations=new ArrayList<>();
        if(answer!=null) for(var evidence:answer.citations()) {
            var chunk=evidence.chunk();
            citations.add(new KnowledgeCitation("e"+(citations.size()+1),chunk.documentId(),chunk.documentVersion(),chunk.id(),
                    chunk.heading().isBlank()?"未命名片段":chunk.heading(),chunk.text(),(long)chunk.sourceStart(),(long)chunk.sourceEnd()));
        }
        var candidates=answer==null?List.<KnowledgeGraphCandidate>of():answer.candidates().stream()
                .map(c->new KnowledgeGraphCandidate(c.contactId(),c.contactVersion(),c.aliases())).toList();
        return new AssistantQuestionResult(key,status,answer==null?Mode.NONE:answer.mode(),reason,
                answer==null?(status==Status.UNAVAILABLE?"本次查询没有完成，请重新开始。不会自动重试。":""):answer.text(),
                citations,candidates,value.sessionVersion(),value.retrievalMode());
    }
    private static JsonNode parse(java.io.InputStream body,Set<String> fields) {
        byte[] bytes=null;
        try {
            bytes=body.readNBytes(16385);
            if(bytes.length>16384) throw new AssistantSessionException(AssistantReason.INPUT_LIMIT);
            String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try(var parser=JSON.createParser(decoded)) {
                JsonNode result=JSON.readTree(parser); if(parser.nextToken()!=null) throw invalid(); return object(result,fields);
            }
        } catch(java.io.IOException failure) { throw invalid(); }
        finally { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    }
    private static JsonNode object(JsonNode node,Set<String> fields) {
        if(node==null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(name->{if(!fields.contains(name)) throw invalid();}); return node;
    }
    private static String text(JsonNode node,String name) { var value=node.get(name);if(value==null || !value.isTextual()) throw invalid();return value.textValue(); }
    private static long number(JsonNode node,String name) { var value=node.get(name);if(value==null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();return value.longValue(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_ASSISTANT_REQUEST"); }
}
