package com.aifriend.shared.error;

/**
 * 携带稳定错误码的业务异常。
 *
 * @author Codex
 * @since 1.0.0
 */
public class BusinessException extends RuntimeException {

    /** 可稳定映射为 HTTP 响应的业务错误码。 */
    private final ErrorCode errorCode;

    /**
     * 使用错误码默认提示创建异常。
     *
     * @param errorCode 稳定错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    /**
     * 使用受控提示创建异常。
     *
     * @param errorCode 稳定错误码
     * @param message 用户可理解且不含敏感信息的提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 获取稳定错误码。
     *
     * @return 错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
