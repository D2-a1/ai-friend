package com.aifriend.shared.error;

/**
 * 外部依赖不可用异常。
 *
 * @author Codex
 * @since 1.0.0
 */
public class UpstreamFailureException extends RuntimeException {

    /**
     * 创建不暴露供应商细节的上游异常。
     */
    public UpstreamFailureException() {
        super("微信身份服务暂不可用，请稍后再试");
    }

    /**
     * 使用不暴露供应商或内部配置的受控提示创建上游异常。
     *
     * @param safeMessage 可安全返回客户端的提示
     */
    public UpstreamFailureException(String safeMessage) {
        super(safeMessage);
    }
}
