package com.aifriend.assistant.infrastructure;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantConversation;
import com.aifriend.assistant.domain.AssistantReason;
import com.aifriend.assistant.domain.AssistantSessionException;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * AES-GCM保护的严格上下文信封，绑定owner/session/purpose/policy/version。
 * 不修改共享JSON配置、不记录明文；仅清零自有字节副本，Java String不能保证擦除。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantContextCipher {
    // 4 * (500 + 360) code points；UTF-8 JSON可把代理对写成12字节转义。
    // 41,280字节正文加固定有界字段，48KiB覆盖合法上界；不是放宽业务字数。
    private static final int MAX_PLAIN_BYTES = 49152;
    private final SensitiveDataProtector protector;
    private final JsonMapper mapper;

    /**
     * 创建独立有界JSON编解码器，不读取真实密钥或连接数据库。
     * @param protector 既有运行时加密器
     */
    public AssistantContextCipher(SensitiveDataProtector protector) {
        this.protector = Objects.requireNonNull(protector);
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                        .maxStringLength(2000).maxNumberLength(20).build()).build();
        mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    }

    /**
     * 加密待持久化上下文，绑定字段来自已锁定会话，不来自用户输入。
     * @param binding 会话绑定
     * @param context 经过领域校验的上下文
     * @return 自有随机IV密文
     */
    public byte[] encrypt(Binding binding, AssistantConversation context) {
        Objects.requireNonNull(binding); Objects.requireNonNull(context);
        if (!matches(binding, context)) { throw new IllegalArgumentException("CONTEXT_BINDING_MISMATCH"); }
        byte[] plain = null;
        try {
            plain = mapper.writeValueAsBytes(new Envelope(1, binding, context));
            if (plain.length > MAX_PLAIN_BYTES) { throw new IllegalArgumentException("CONTEXT_LIMIT"); }
            byte[] cipher = protector.encryptBytes(plain);
            if (cipher == null || cipher.length <= 28 || cipher.length > MAX_PLAIN_BYTES + 28) {
                if (cipher != null) { Arrays.fill(cipher, (byte) 0); }
                throw new IllegalStateException("INVALID_CONTEXT_CIPHER");
            }
            return cipher;
        } catch (IOException | RuntimeException failure) {
            throw new AssistantSessionException(AssistantReason.STORAGE_UNAVAILABLE);
        } finally { if (plain != null) { Arrays.fill(plain, (byte) 0); } }
    }

    /**
     * 解密后检查归属与精确版本；不允许以旧版本密文恢复当前上下文。
     * @param expected 从已认证且已复验的会话读取的绑定
     * @param encrypted 借用的数据库密文，不会修改调用方数组
     * @return 内存上下文；后续仍须复验每条持久结果的来源和授权
     */
    public AssistantConversation decrypt(Binding expected, byte[] encrypted) {
        Objects.requireNonNull(expected);
        if (encrypted == null || encrypted.length <= 28 || encrypted.length > MAX_PLAIN_BYTES + 28) { throw invalid(); }
        byte[] copy = encrypted.clone(); byte[] plain = null;
        try {
            plain = protector.decryptBytes(copy);
            if (plain == null || plain.length == 0 || plain.length > MAX_PLAIN_BYTES) { throw invalid(); }
            Envelope decoded = mapper.readValue(plain, Envelope.class);
            if (decoded == null || decoded.schema() != 1 || !expected.equals(decoded.binding())
                    || !matches(expected, decoded.context())) { throw invalid(); }
            return decoded.context();
        } catch (IOException | RuntimeException failure) { throw invalid(); }
        finally { Arrays.fill(copy, (byte) 0); if (plain != null) { Arrays.fill(plain, (byte) 0); } }
    }

    /**
     * 加密信封内受认证的命名空间。
     * @param owner 认证owner
     * @param session 会话ID
     * @param purpose 独立用途
     * @param policyVersion 当时政策
     * @param version 内容所属会话版本
     */
    public record Binding(UUID owner, UUID session, Purpose purpose, String policyVersion, long version) {
        /** 不允许缺归属、空政策或负版本。 */
        public Binding {
            Objects.requireNonNull(owner); Objects.requireNonNull(session); Objects.requireNonNull(purpose);
            if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,64}") || version < 0) {
                throw new IllegalArgumentException("INVALID_CONTEXT_BINDING");
            }
        }
        @Override public String toString() { return "AssistantContextBinding[redacted]"; }
    }

    private record Envelope(int schema, Binding binding, AssistantConversation context) { }
    private static boolean matches(Binding binding, AssistantConversation context) {
        return context != null && context.purpose() == binding.purpose()
                && context.turns().stream().allMatch(turn -> turn.resultVersion() <= binding.version());
    }
    private static AssistantSessionException invalid() { return new AssistantSessionException(AssistantReason.DECRYPTION_FAILED); }
}
