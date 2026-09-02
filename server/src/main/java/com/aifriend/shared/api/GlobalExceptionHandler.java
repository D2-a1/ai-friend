package com.aifriend.shared.api;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.UpstreamFailureException;

/**
 * API 统一异常处理骨架。
 *
 * @author Codex
 * @since 1.0.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 安全记录未预期异常类型的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 创建统一异常处理器。
     */
    public GlobalExceptionHandler() {
    }

    /**
     * 处理请求参数错误。
     *
     * @param exception 参数异常
     * @return 统一错误响应
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ApiError> handleValidationException(Exception exception) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不正确");
    }

    /**
     * 处理稳定业务异常。
     *
     * @param exception 业务异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException exception) {
        return build(exception.errorCode().httpStatus(), exception.errorCode().name(), exception.getMessage());
    }

    /**
     * 处理微信身份等上游不可用异常。
     *
     * @param exception 上游不可用异常
     * @return 502 且使用契约已有 INTERNAL_ERROR 代码
     */
    @ExceptionHandler(UpstreamFailureException.class)
    public ResponseEntity<ApiError> handleUpstreamFailureException(UpstreamFailureException exception) {
        return build(HttpStatus.BAD_GATEWAY, "INTERNAL_ERROR", exception.getMessage());
    }

    /**
     * 处理已认证主体的拒绝访问异常。
     *
     * @param exception 拒绝访问异常
     * @return 统一错误响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDeniedException(AccessDeniedException exception) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号无权执行此操作");
    }

    /**
     * 处理未预期异常，不向客户端暴露内部信息。
     *
     * @param exception 未预期异常
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        LOGGER.error("未预期异常 traceId={} exceptionType={}", TraceContext.currentTraceId(),
                exception.getClass().getSimpleName());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂不可用，请稍后再试");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        ApiError error = new ApiError(code, message, null, TraceContext.currentTraceId());
        return ResponseEntity.status(status).body(error);
    }
}
