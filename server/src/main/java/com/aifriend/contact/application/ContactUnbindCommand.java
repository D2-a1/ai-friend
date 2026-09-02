package com.aifriend.contact.application;

/**
 * 已完成 API 结构校验的解除联系人绑定命令。
 *
 * @param confirmed 用户是否已完成二次确认
 * @param expectedContactVersion 客户端当前展示的联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record ContactUnbindCommand(
        boolean confirmed,
        long expectedContactVersion) {

    /**
     * 生成不含敏感内容的稳定请求指纹输入。
     *
     * @return 用于幂等冲突检查的指纹输入
     */
    public String fingerprintInput() {
        return Boolean.toString(confirmed) + ':' + expectedContactVersion;
    }
}
