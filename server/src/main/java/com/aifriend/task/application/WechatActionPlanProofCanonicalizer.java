package com.aifriend.task.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Java 与 Android 共用语义的定位证明规范字节编码器。
 *
 * <p>所有字符串使用 UTF-8 和四字节大端长度前缀，可空字段使用 -1；时间使用 UTC epoch
 * 毫秒，避免 JSON 日期格式和分隔符歧义。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class WechatActionPlanProofCanonicalizer {

    /** 规范字节格式魔数。 */
    public static final String FORMAT_MAGIC = "AI_FRIEND_WECHAT_LOCATOR_PROOF_V1";

    private WechatActionPlanProofCanonicalizer() {
    }

    /**
     * 编码当前计划与一次性定位证明的全部安全关键字段。
     *
     * @param claims 动作计划声明
     * @param proofVersion 证明版本
     * @param keyId 签名密钥编号
     * @param saltHex 128 位随机盐小写十六进制
     * @param locatorDigestHex 盐化定位 SHA-256 小写十六进制
     * @return 确定性长度前缀规范字节
     * @throws IllegalStateException 内存字节流发生不可恢复编码错误时抛出
     */
    public static byte[] canonicalBytes(
            WechatActionPlanProofClaims claims,
            String proofVersion,
            String keyId,
            String saltHex,
            String locatorDigestHex) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, FORMAT_MAGIC);
            writeString(output, proofVersion);
            writeString(output, keyId);
            writeString(output, claims.planId());
            writeString(output, claims.action());
            writeString(output, claims.contactId());
            writeNullableString(output, claims.audioObjectId());
            writeString(output, claims.summaryHash());
            writeString(output, claims.minimumRuleVersion());
            output.writeLong(claims.contactVersion());
            writeString(output, claims.wechatVersion());
            writeString(output, claims.locatorVersion());
            writeString(output, saltHex);
            writeString(output, locatorDigestHex);
            output.writeLong(claims.issuedAt().toEpochMilli());
            output.writeLong(claims.expiresAt().toEpochMilli());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("定位证明规范字节编码失败", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void writeNullableString(DataOutputStream output, String value)
            throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        writeString(output, value);
    }
}
