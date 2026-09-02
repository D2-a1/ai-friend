package com.aifriend.invitation.application;

/**
 * 密码学安全随机令牌生成端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SecretTokenPort {

    /**
     * 生成至少 256 位、无填充的 URL 安全随机令牌。
     *
     * @return 随机令牌明文，只能在当前调用内存中短暂存在
     */
    String issue();
}
