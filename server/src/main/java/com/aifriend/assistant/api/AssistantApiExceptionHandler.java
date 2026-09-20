package com.aifriend.assistant.api;

import java.util.Map;
import java.util.concurrent.CancellationException;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.aifriend.assistant.domain.*;
import com.aifriend.shared.api.*;

/**
 * 仅助手入口使用的脱敏错误映射，不修改旧联系任务的异常语义。
 * @author Codex
 * @since 1.0.0
 */
@Order(-10)
@RestControllerAdvice(assignableTypes=AssistantSessionController.class)
public final class AssistantApiExceptionHandler {
    /** 创建处理器。 */
    public AssistantApiExceptionHandler() { }
    /**
     * 将有限业务失败原因映射为脱敏HTTP错误。
     * @param failure 固定原因
     * @return 契约兼容通用code及有限reasonCode
     */
    @ExceptionHandler(AssistantSessionException.class)
    public ResponseEntity<ApiError> assistant(AssistantSessionException failure) {
        AssistantReason reason=failure.reason(); int status=switch(reason) {
            case ACCESS_REVOKED,AUTH_CHANGED -> 403;
            case VERSION_MISMATCH,IDEMPOTENCY_CONFLICT,STALE_REQUEST,RESULT_STALE,SESSION_CLOSED,SESSION_EXPIRED,EVIDENCE_INVALIDATED,PROFILE_CHANGED -> 409;
            case RESOURCE_LIMIT,BUDGET_EXHAUSTED -> 429;
            case INPUT_LIMIT -> 413;
            case INVALID_REQUEST,INVALID_TEXT,INVALID_GRAPH_QUERY,EMPTY_INPUT,AMBIGUOUS_QUERY,MISSING_CONTEXT -> 422;
            default -> 503;
        };
        String code=switch(status) {case 403->"FORBIDDEN";case 409->"SESSION_CONFLICT";case 429->"RATE_LIMITED";case 413,422->"VALIDATION_FAILED";default->"INTERNAL_ERROR";};
        return ResponseEntity.status(status).body(new ApiError(code,"本次问答未完成，请根据提示重试。",Map.of("reasonCode",reason.name()),TraceContext.currentTraceId()));
    }
    /**
     * 拒绝非法参数或不可解析正文，不向响应复制异常内容。
     * @param invalid 不记录原正文或异常消息
     * @return 参数错误
     */
    @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class,MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalid(Exception invalid) {
        return ResponseEntity.unprocessableEntity().body(new ApiError("VALIDATION_FAILED","问答参数不正确。",
                Map.of("reasonCode","INVALID_REQUEST"),TraceContext.currentTraceId()));
    }
    /**
     * 将取消信号映射为不可用，避免声称已生成成功结果。
     * @param cancelled 取消信号
     * @return 不声称任务成功
     */
    @ExceptionHandler(CancellationException.class)
    public ResponseEntity<ApiError> cancelled(CancellationException cancelled) {
        return ResponseEntity.status(503).body(new ApiError("INTERNAL_ERROR","本次问答已中止。",Map.of("reasonCode","RESULT_STALE"),TraceContext.currentTraceId()));
    }
}
