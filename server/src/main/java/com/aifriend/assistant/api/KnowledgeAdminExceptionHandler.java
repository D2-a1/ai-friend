package com.aifriend.assistant.api;

import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import com.aifriend.retrieval.application.KnowledgeImportException;
import com.aifriend.shared.api.*;

/**
 * 仅公开知识管理接口的固定错误映射，不回显SQL、输入或模型细节。
 * @author Codex
 * @since 1.0.0
 */
@Order(-10) @RestControllerAdvice(assignableTypes=KnowledgeAdminController.class)
public final class KnowledgeAdminExceptionHandler {
    /** 创建处理器。 */ public KnowledgeAdminExceptionHandler() { }
    /**
     * 将确定性导入失败映射到有限HTTP错误。
     * @param failure 确定业务失败
     * @return 有限状态和固定原因
     */
    @ExceptionHandler(KnowledgeImportException.class)
    public ResponseEntity<ApiError> business(KnowledgeImportException failure) {
        int status=switch(failure.kind()) {case NOT_FOUND->404;case RESOURCE_LIMIT->429;default->409;};
        String code=switch(status) {case 404->"NOT_FOUND";case 429->"RATE_LIMITED";default->"SESSION_CONFLICT";};
        return error(status,code,failure.kind().name());
    }
    /**
     * 将非法参数映射为不回显输入的校验错误。
     * @param invalid 参数错误
     * @return 不回显正文
     */
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalid(Exception invalid) { return error(422,"VALIDATION_FAILED","INVALID_REQUEST"); }
    /**
     * 拒绝超出管理请求大小上限的输入。
     * @param limit 超限错误
     * @return 413
     */
    @ExceptionHandler(KnowledgeAdminController.InputLimitException.class)
    public ResponseEntity<ApiError> limit(KnowledgeAdminController.InputLimitException limit) { return error(413,"VALIDATION_FAILED","INPUT_LIMIT"); }
    /**
     * 对结果未知或配置不可用统一失败关闭。
     * @param unavailable 存储/事务/关闭开关，结果不确定不能自动重试
     * @return 503，不伪造成功或确定回滚
     */
    @ExceptionHandler({DataAccessException.class,TransactionException.class,IllegalStateException.class})
    public ResponseEntity<ApiError> unavailable(Exception unavailable) { return error(503,"INTERNAL_ERROR","STORAGE_OR_CONFIGURATION_UNAVAILABLE"); }
    private static ResponseEntity<ApiError> error(int status,String code,String reason) {
        return ResponseEntity.status(status).body(new ApiError(code,"知识管理操作未完成，请核对状态。",Map.of("reasonCode",reason),TraceContext.currentTraceId()));
    }
}
