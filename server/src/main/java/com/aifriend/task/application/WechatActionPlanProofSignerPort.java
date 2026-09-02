package com.aifriend.task.application;

/**
 * 当前动作计划 Ed25519 定位证明签名端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface WechatActionPlanProofSignerPort {

    /**
     * 判断正式签名材料是否完整可用。
     *
     * @return 可签发时返回 true
     */
    boolean available();

    /**
     * 返回当前签名密钥编号。
     *
     * @return 非敏感密钥编号
     */
    String keyId();

    /**
     * 对规范字节生成 Ed25519 签名。
     *
     * @param canonicalBytes 版本化长度前缀规范字节
     * @return 64 字节 Ed25519 签名
     * @throws com.aifriend.shared.error.BusinessException 签名材料缺失或签名失败时抛出
     */
    byte[] sign(byte[] canonicalBytes);
}
