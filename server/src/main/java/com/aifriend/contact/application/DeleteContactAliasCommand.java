package com.aifriend.contact.application;

/**
 * 已通过 API 结构校验的称呼删除命令。
 *
 * @param confirmed 用户已完成删除二次确认
 * @param expectedContactVersion 客户端展示的联系人对外版本
 * @author Codex
 * @since 1.0.0
 */
public record DeleteContactAliasCommand(
        boolean confirmed,
        long expectedContactVersion) {

    /**
     * 生成用于幂等冲突检查的稳定指纹输入。
     *
     * @return 用于幂等冲突检查的稳定指纹输入
     */
    public String fingerprintInput() {
        return confirmed + ":" + expectedContactVersion;
    }
}
