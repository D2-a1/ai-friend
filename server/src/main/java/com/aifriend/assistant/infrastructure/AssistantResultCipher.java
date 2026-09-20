package com.aifriend.assistant.infrastructure;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 回答和来源证明的独立严格AES-GCM信封，不能和上下文密文互换。
 * 只负责绑定/结构验证，外部同意、profile和实时来源复验不能由解密成功代替。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantResultCipher implements com.aifriend.assistant.application.AssistantResultProtectionPort {
    private static final int MAX_PLAIN=AssistantTurnRequest.MAX_RESULT_BYTES-28;
    private final SensitiveDataProtector protector;
    private final JsonMapper mapper;
    /** {@inheritDoc} */
    @Override public byte[] encrypt(AssistantSession session,AssistantTurnRequest request,AssistantStoredResult result) {
        return encrypt(binding(session,request),result);
    }
    /** {@inheritDoc} */
    @Override public AssistantStoredResult decrypt(AssistantSession session,AssistantTurnRequest request,byte[] encrypted) {
        return decrypt(binding(session,request),encrypted);
    }
    /**
     * 创建绑定问答结果及证明的加密器。
     * @param protector 既有AES-GCM保护器；构造不访问数据库或密钥文件
     */
    public AssistantResultCipher(SensitiveDataProtector protector) {
        this.protector=Objects.requireNonNull(protector);
        var factory=JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(12000).maxNumberLength(40).build()).build();
        mapper=JsonMapper.builder(factory).addModule(new Jdk8Module())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        // proof中的无索引/无profile明确允许null；必需字段由record构造器检查。
    }
    /**
     * 保存经过应用层当前来源/授权检查的答案。
     * @param binding 来自已锁定会话/持久请求
     * @param result 业务答案和实际来源证明
     * @return 随机IV绑定密文
     */
    public byte[] encrypt(Binding binding,AssistantStoredResult result) {
        Objects.requireNonNull(binding); Objects.requireNonNull(result);
        if(binding.purpose()!=result.answer().purpose()) throw new IllegalArgumentException("RESULT_BINDING_MISMATCH");
        byte[] plain=null;
        try {
            plain=mapper.writeValueAsBytes(new Envelope("assistant-result-v1",binding,result));
            if(plain.length>MAX_PLAIN) throw new IllegalArgumentException("RESULT_LIMIT");
            byte[] encrypted=protector.encryptBytes(plain);
            if(encrypted==null || encrypted.length<=28 || encrypted.length>AssistantTurnRequest.MAX_RESULT_BYTES) {
                if(encrypted!=null) Arrays.fill(encrypted,(byte)0); throw new IllegalStateException("INVALID_RESULT_CIPHER");
            }
            return encrypted;
        } catch(IOException | RuntimeException failure) { throw failure(AssistantReason.STORAGE_UNAVAILABLE); }
        finally { if(plain!=null) Arrays.fill(plain,(byte)0); }
    }
    /**
     * 严格解密绑定证明，后续必须经过实时来源及同意复验后才展示或放入上下文。
     * @param binding 当前会话和持久原请求推导的预期绑定
     * @param encrypted 借用密文，不修改调用方数组
     * @return 不具备当前授权的待复验结果
     */
    public AssistantStoredResult decrypt(Binding binding,byte[] encrypted) {
        Objects.requireNonNull(binding);
        if(encrypted==null || encrypted.length<=28 || encrypted.length>AssistantTurnRequest.MAX_RESULT_BYTES) throw failure(AssistantReason.DECRYPTION_FAILED);
        byte[] copy=encrypted.clone(), plain=null;
        try {
            plain=protector.decryptBytes(copy);
            if(plain==null || plain.length==0 || plain.length>MAX_PLAIN) throw new IllegalArgumentException("RESULT_LIMIT");
            var envelope=mapper.readValue(plain,Envelope.class);
            if(envelope==null || !"assistant-result-v1".equals(envelope.schema()) || !binding.equals(envelope.binding())
                    || envelope.result()==null || envelope.result().answer().purpose()!=binding.purpose()) {
                throw new IllegalArgumentException("RESULT_BINDING_MISMATCH");
            }
            return envelope.result();
        } catch(IOException | RuntimeException failure) { throw failure(AssistantReason.DECRYPTION_FAILED); }
        finally { Arrays.fill(copy,(byte)0); if(plain!=null) Arrays.fill(plain,(byte)0); }
    }
    /**
     * 用会话与原请求推导绑定，版本采用该请求完成版本而非后续会话版本。
     * @param session 当前认证会话
     * @param request 已持久原请求
     * @return 创建结果或读取结果使用的固定绑定
     */
    public static Binding binding(AssistantSession session,AssistantTurnRequest request) {
        Objects.requireNonNull(session); Objects.requireNonNull(request);
        if(!session.id().equals(request.sessionId()) || !session.owner().equals(request.owner())
                || session.purpose()!=request.purpose() || session.version()<request.admittedVersion()) {
            throw new IllegalArgumentException("RESULT_BINDING_MISMATCH");
        }
        return new Binding(session.owner(),session.id(),request.id(),request.leaseToken(),session.purpose(),session.policyVersion(),
                request.admittedVersion()+1,request.requestDigest(),request.expiresAt().toEpochMilli());
    }
    /**
     * 防止账号/请求/用途/版本、执行栅栏及保留期替换。
     * @param owner 认证owner
     * @param session 会话
     * @param request 原逻辑请求
     * @param token 原执行栅栏
     * @param purpose 独立用途
     * @param policy 政策版本
     * @param resultVersion 原请求完成版本
     * @param requestDigest 原完整请求HMAC
     * @param expiresAtMillis 原结果保留截止
     */
    public record Binding(UUID owner,UUID session,UUID request,UUID token,Purpose purpose,String policy,long resultVersion,
            String requestDigest,long expiresAtMillis) {
        /** 检查完整绑定，不接受缺身份或无界期限。 */
        public Binding {
            Objects.requireNonNull(owner); Objects.requireNonNull(session); Objects.requireNonNull(request);
            Objects.requireNonNull(token); Objects.requireNonNull(purpose);
            if(policy==null || !policy.matches("[A-Za-z0-9._-]{1,64}") || resultVersion<2 || expiresAtMillis<=0
                    || requestDigest==null || !requestDigest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("INVALID_RESULT_BINDING");
        }
        @Override public String toString() { return "AssistantResultBinding[redacted]"; }
    }
    private record Envelope(String schema,Binding binding,AssistantStoredResult result) { }
    private static AssistantSessionException failure(AssistantReason reason) { return new AssistantSessionException(reason); }
}
