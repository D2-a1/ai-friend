package com.aifriend.assistant.api;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.aifriend.assistant.infrastructure.KnowledgeAdminSecurityConfiguration;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.application.KnowledgeImportRegistrationPort.*;
import com.aifriend.retrieval.domain.*;
import com.aifriend.shared.api.ApiResponse;
import com.aifriend.shared.error.*;

/**
 * 公开文本管理HTTP入口；普通用户没有管理权限，不接受URL抓取、路径或模型profile覆盖。
 * @author Codex
 * @since 1.0.0
 */
@RestController @RequestMapping("/admin/knowledge")
public final class KnowledgeAdminController {
    private static final int MAX_BODY_BYTES=2*1024*1024;
    private static final Set<String> FIELDS=Set.of("sourceKey","title","text","locale","minimumAppVersionCode","maximumAppVersionCode","idempotencyKey");
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(262144).maxNumberLength(20).build()).build()).build();
    private final ObjectProvider<KnowledgeImportRegistrationPort> registration;
    private final ObjectProvider<BuildSpecification> specification;
    private final KnowledgeDocumentManagementPort documents;
    private final ObjectProvider<KnowledgeCleanupStatusPort> cleanupStatus;
    private final boolean enabled,importEnabled,maintenanceEnabled;
    /**
     * 创建独立管理入口，导入、删除与只读状态各自遵守开关边界。
     * @param registration 条件导入登记，不执行构建
     * @param specification 服务器固定构建profile
     * @param documents 关闭开关仍存在的失效端口
     * @param enabled 知识开关
     * @param importEnabled 导入开关
     * @param cleanupStatus 关闭开关仍允许查询的只读积压端口
     * @param maintenanceEnabled 独立维护开关，不代表工作线程健康
     */
    public KnowledgeAdminController(ObjectProvider<KnowledgeImportRegistrationPort> registration,ObjectProvider<BuildSpecification> specification,
            KnowledgeDocumentManagementPort documents,@Value("${ai-friend.knowledge.enabled:false}") boolean enabled,
            @Value("${ai-friend.knowledge.import.enabled:false}") boolean importEnabled,
            ObjectProvider<KnowledgeCleanupStatusPort> cleanupStatus,
            @Value("${ai-friend.knowledge.maintenance.enabled:false}") boolean maintenanceEnabled) {
        this.registration=Objects.requireNonNull(registration);this.specification=Objects.requireNonNull(specification);
        this.documents=Objects.requireNonNull(documents);this.enabled=enabled;this.importEnabled=importEnabled;
        this.cleanupStatus=Objects.requireNonNull(cleanupStatus);
        this.maintenanceEnabled=maintenanceEnabled;
    }

    /**
     * 只读查询，不受功能开关阻挡，不触发清理/重试/告警确认。
     * @param authentication 当前账号/设备复验后的独立管理主体
     * @return 匿名观察结果；没有积压不等于永久清除证明
     */
    @GetMapping("/cleanup-status")
    public ResponseEntity<ApiResponse<CleanupResponse>> cleanup(Authentication authentication) {
        authorize(authentication);
        var port = cleanupStatus.getIfAvailable();
        if (port == null) { throw new IllegalStateException("KNOWLEDGE_CLEANUP_STATUS_UNAVAILABLE"); }
        var snapshot = port.read();
        var response = new CleanupResponse(snapshot.state(), snapshot.observedAt(), snapshot.firstFailureAt(),
                snapshot.nextAttemptAt(), snapshot.failures(), snapshot.leaseState(), snapshot.inactiveVersions(),
                snapshot.inactiveGenerations(), snapshot.unreferencedChunks(), snapshot.countsTruncated(),
                snapshot.pendingFirstAlerts(), snapshot.pendingOverdueAlerts(), snapshot.overdue(), maintenanceEnabled || (enabled && importEnabled));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(ApiResponse.success(response));
    }

    /**
     * 管理端有限只读DTO，故意不复用含令牌的RC内部状态。
     * @param state 当前积压观察，不是永久删除回执
     * @param observedAt 数据库UTC观察时间
     * @param firstFailureAt 当前失败起点，没有失败为null
     * @param nextAttemptAt 账本最早可尝试时间，不保证调度已经运行
     * @param failures 当前失败次数
     * @param leaseState 租约生命周期，无令牌
     * @param inactiveVersions 尚未清除的非活动版本
     * @param inactiveGenerations 非活动世代
     * @param unreferencedChunks 无清单引用的片段
     * @param countsTruncated 计数达到截断上限，只能解释为下界
     * @param pendingFirstAlerts 首次失败告警未送达计数
     * @param pendingOverdueAlerts 超时告警未送达计数
     * @param overdue 失败周期是否达到15分钟
     * @param localScanEnabled 本实例独立维护或知识与导入双开关开启，不代表线程/集群健康
     */
    public record CleanupResponse(KnowledgeCleanupStatusPort.BacklogState state, java.time.Instant observedAt,
            java.time.Instant firstFailureAt, java.time.Instant nextAttemptAt, int failures,
            KnowledgeCleanupStatusPort.LeaseState leaseState, int inactiveVersions, int inactiveGenerations,
            int unreferencedChunks, boolean countsTruncated, long pendingFirstAlerts, long pendingOverdueAlerts,
            boolean overdue, boolean localScanEnabled) { }
    /**
     * 持久登记公开文本导入任务，不在HTTP请求内执行构建。
     * @param authentication 已通过账号/设备过滤的独立管理主体
     * @param body 有界UTF-8 JSON，不落盘
     * @return 202仅代表持久受理，重复同键仍返回同job
     */
    @PostMapping(value="/imports",consumes="application/json")
    public ResponseEntity<ApiResponse<JobResponse>> submit(Authentication authentication,InputStream body) {
        authorize(authentication); gate();
        var input=parse(body);
        var request=new KnowledgeImportRequest(text(input,"sourceKey"),text(input,"title"),text(input,"text"),text(input,"locale"),
                integer(input,"minimumAppVersionCode"),integer(input,"maximumAppVersionCode"),text(input,"idempotencyKey"));
        var receipt=registration.getObject().register(request,specification.getObject());
        return ResponseEntity.accepted().body(ApiResponse.success(response(receipt)));
    }
    /**
     * 读取已登记任务与当前文档修订号。
     * @param authentication 独立管理主体
     * @param id 导入job标识
     * @return 任务历史状态及当前文档修订号，不回显原文/幂等键/租约
     */
    @GetMapping("/imports/{id}")
    public ApiResponse<JobResponse> get(Authentication authentication,@PathVariable UUID id) {
        authorize(authentication); gate();
        var receipt=registration.getObject().find(id).orElseThrow(()->new KnowledgeImportException(KnowledgeImportException.Kind.NOT_FOUND));
        return ApiResponse.success(response(receipt));
    }
    /**
     * 按修订号逻辑失效文档，不声称已完成物理回收。
     * @param authentication 独立管理主体
     * @param id 文档标识
     * @param expectedVersion 从JobResponse.documentRevision读取的根记录修订号
     * @return 204仅逻辑失效，不代表物理擦除
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(Authentication authentication,@PathVariable UUID id,@RequestParam long expectedVersion) {
        authorize(authentication);
        if(expectedVersion<1) throw invalid();
        documents.invalidate(id,expectedVersion); return ResponseEntity.noContent().build();
    }
    private JobResponse response(Receipt receipt) {
        var current=documents.find(receipt.documentId()).orElseThrow(()->new KnowledgeImportException(KnowledgeImportException.Kind.NOT_FOUND));
        if(!current.id().equals(receipt.documentId())) throw new IllegalStateException("KNOWLEDGE_DOCUMENT_METADATA_MISMATCH");
        if(current.deleted()) throw new KnowledgeImportException(KnowledgeImportException.Kind.SOURCE_DELETED);
        return new JobResponse(receipt.id(),receipt.state(),receipt.attempts(),receipt.failure().name(),receipt.documentId(),
                receipt.documentVersion(),current.revision());
    }
    private void gate() { if(!enabled || !importEnabled) throw new IllegalStateException("KNOWLEDGE_IMPORT_DISABLED"); }
    private static void authorize(Authentication authentication) {
        if(authentication==null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt))
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        if(authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals(KnowledgeAdminSecurityConfiguration.AUTHORITY)))
            throw new AccessDeniedException("KNOWLEDGE_MANAGEMENT_REQUIRED");
    }
    private static JsonNode parse(InputStream body) {
        byte[] bytes=null;
        try {
            bytes=body.readNBytes(MAX_BODY_BYTES+1); if(bytes.length>MAX_BODY_BYTES) throw new InputLimitException();
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(var parser=JSON.createParser(value)) {
                JsonNode result=JSON.readTree(parser);
                if(result==null || !result.isObject() || parser.nextToken()!=null) throw invalid();
                result.fieldNames().forEachRemaining(name->{if(!FIELDS.contains(name)) throw invalid();});
                return result;
            }
        } catch(com.fasterxml.jackson.core.exc.StreamConstraintsException limit) { throw new InputLimitException(); }
        catch(java.io.IOException malformed) { throw invalid(); }
        finally { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    }
    private static String text(JsonNode input,String name) {
        var value=input.get(name); if(value==null || !value.isTextual()) throw invalid();
        String result=value.textValue();
        if(name.equals("text") && result.getBytes(StandardCharsets.UTF_8).length>262144) throw new InputLimitException();
        return result;
    }
    private static int integer(JsonNode input,String name) {
        var value=input.get(name); if(value==null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid(); return value.intValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_KNOWLEDGE_IMPORT_REQUEST"); }
    /** 不携带请求内容的超限信号。 */
    public static final class InputLimitException extends RuntimeException {
        /** 创建固定错误。 */ public InputLimitException() { super("INPUT_LIMIT"); }
    }
    /**
     * 不包含正文、幂等键或租约的导入任务响应。
     * @param id job标识
     * @param status job历史状态，READY不承诺仍为活动文档版本
     * @param attempts 已认领次数
     * @param errorCode 固定job失败枚举
     * @param documentId 文档标识
     * @param documentVersion 该job目标内容版本
     * @param documentRevision 当前根记录修订号，供条件删除使用
     */
    public record JobResponse(UUID id,KnowledgeImportJob.State status,int attempts,String errorCode,UUID documentId,
            long documentVersion,long documentRevision) { }
}
