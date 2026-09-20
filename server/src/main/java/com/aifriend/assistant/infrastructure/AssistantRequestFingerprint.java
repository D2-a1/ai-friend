package com.aifriend.assistant.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantQuestion;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 请求HMAC指纹：长度前缀编码、创建/问题分域、账号/会话隔离。
 * 不保存低熵问题的裸SHA摘要；结果不是权限，仍须账号授权和来源复验。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantRequestFingerprint {
    private final SensitiveDataProtector protector;
    /**
     * 创建分域请求指纹器。
     * @param protector 既有带秘密的HMAC保护器，构造不访问数据库
     */
    public AssistantRequestFingerprint(SensitiveDataProtector protector) { this.protector = Objects.requireNonNull(protector); }
    /**
     * 创建会话指纹。同owner同key不同用途使用相同查找键、不同请求摘要，必须冲突。
     * @param owner 认证owner
     * @param key 客户端原始创建键
     * @param purpose 所请求用途
     * @return 查找键和请求摘要
     */
    public Fingerprint creation(UUID owner, String key, Purpose purpose) {
        Objects.requireNonNull(owner); Objects.requireNonNull(purpose); AssistantQuestion.requireKey(key);
        return new Fingerprint(hash("assistant-create-key-v1", owner.toString(), key),
                hash("assistant-create-body-v1", owner.toString(), key, purpose.name()));
    }
    /**
     * 冻结完整问题，重复请求不能悄悄更改版本、语言、应用版本、正文或图谱参数。
     * @param owner 认证owner
     * @param session 已认证归属的session
     * @param question 完整原始请求
     * @return 查找键和请求摘要
     */
    public Fingerprint question(UUID owner, UUID session, AssistantQuestion question) {
        Objects.requireNonNull(owner); Objects.requireNonNull(session); Objects.requireNonNull(question);
        String type; String contact = ""; String content;
        if (question.payload() instanceof AssistantQuestion.PublicText text) { type = "PUBLIC_TEXT"; content = text.text(); }
        else {
            var graph = (AssistantQuestion.PrivateGraph) question.payload();
            type = graph.type().name(); contact = graph.contactId() == null ? "" : graph.contactId().toString();
            content = graph.aliasText() == null ? "" : graph.aliasText();
        }
        return new Fingerprint(questionKey(owner,session,question.requestKey()),
                hash("assistant-question-body-v1", owner.toString(), session.toString(), question.requestKey(),
                        Long.toString(question.expectedVersion()), question.locale(), Integer.toString(question.appVersionCode()),
                        question.payload().purpose().name(), type, contact, content));
    }
    /**
     * 状态查询只计算查找键，不伪造或修改请求正文指纹。
     * @param owner 认证owner
     * @param session 已认证会话
     * @param key 原始幂等键
     * @return owner/session范围HMAC
     */
    public String questionKey(UUID owner,UUID session,String key) {
        Objects.requireNonNull(owner); Objects.requireNonNull(session); AssistantQuestion.requireKey(key);
        return hash("assistant-question-key-v1",owner.toString(),session.toString(),key);
    }
    private String hash(String domain, String... fields) {
        // wipe buffer的内部存储及中间UTF-8；String仍不能保证擦除，禁止任何日志输出。
        var buffer = new WipingBuffer(); byte[] canonical = null; byte[] digest = null;
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(fields.length);
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                try { out.writeInt(bytes.length); out.write(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
            }
            out.flush(); canonical = buffer.toByteArray();
            digest = protector.subjectHmac(domain + ":" + Base64.getEncoder().encodeToString(canonical));
            if (digest == null || digest.length != 32) { throw new IllegalStateException("INVALID_REQUEST_HMAC"); }
            return HexFormat.of().formatHex(digest);
        } catch (IOException failure) { throw new IllegalStateException("REQUEST_FINGERPRINT_FAILED"); }
        finally {
            buffer.wipe(); if (canonical != null) { Arrays.fill(canonical, (byte) 0); }
            if (digest != null) { Arrays.fill(digest, (byte) 0); }
        }
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        // 输入已受500码点/512字节key等限制，预分配避免扩容遗留含敏感内容的旧数组。
        WipingBuffer() { super(4096); }
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
    /**
     * 二进制32字节HMAC的规范十六进制；不写默认日志。
     * @param keyHash owner/session命名空间下查找键
     * @param requestDigest 请求内容指纹
     */
    public record Fingerprint(String keyHash, String requestDigest) {
        /** 拒绝损坏/不规范摘要。 */
        public Fingerprint {
            if (keyHash == null || requestDigest == null || !keyHash.matches("[a-f0-9]{64}")
                    || !requestDigest.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("INVALID_REQUEST_FINGERPRINT"); }
        }
        @Override public String toString() { return "AssistantRequestFingerprint[redacted]"; }
    }
}
