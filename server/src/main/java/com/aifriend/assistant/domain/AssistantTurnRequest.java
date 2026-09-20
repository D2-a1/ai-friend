package com.aifriend.assistant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;

/**
 * 持久请求的生命周期值对象；只存密文，不自动重领/重置计费尝试或八秒截止。
 * 与session的变更必须由后续仓储同事务提交，单独构造本对象不授予执行权。
 * @param id 逻辑请求ID，计费也复用本ID
 * @param owner 认证owner
 * @param sessionId 会话ID
 * @param purpose 固定用途
 * @param keyHash 幂等键HMAC
 * @param requestDigest 完整请求HMAC
 * @param admittedVersion 受理后会话版本
 * @param leaseToken 首次受理的执行栅栏
 * @param createdAt DB受理时间
 * @param deadline 首次受理冻结的总截止
 * @param expiresAt 会话创建时确定的十五分钟总截止，不因请求重试或新问题延长；读取另验当前会话空闲截止
 * @param state 请求状态
 * @param resultVersion 完成后版本，仅COMPLETED有值
 * @param encryptedResult 包含答案及来源证明的绑定密文，仅COMPLETED有值
 * @author Codex
 * @since 1.0.0
 */
public record AssistantTurnRequest(UUID id, UUID owner, UUID sessionId, Purpose purpose, String keyHash, String requestDigest,
        long admittedVersion, UUID leaseToken, Instant createdAt, Instant deadline, Instant expiresAt,
        State state, Long resultVersion, byte[] encryptedResult) {
    /** 仅COMPLETED可能读取密文，其余终态永不重领。 */
    public enum State {
        /** 首次受理后的唯一在途执行。 */
        PROCESSING,
        /** 结果已提交，读取仍须重新校验来源和授权。 */
        COMPLETED,
        /** 执行期限已到。 */
        EXPIRED,
        /** 会话取消导致终止。 */
        CANCELLED,
        /** 授权或来源变化使请求失效。 */
        INVALIDATED
    }
    /** 回答/候选/证明JSON编码硬上限，尚未接持久结果编解码器。 */
    public static final int MAX_RESULT_BYTES = 196636;
    /** 恢复损坏行时同样验证结构与期限。 */
    public AssistantTurnRequest {
        Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(sessionId);
        Objects.requireNonNull(purpose); Objects.requireNonNull(leaseToken); Objects.requireNonNull(state);
        time(createdAt); time(deadline); time(expiresAt);
        if (keyHash == null || !keyHash.matches("[a-f0-9]{64}") || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}")
                || admittedVersion < 1 || admittedVersion == Long.MAX_VALUE || !deadline.isAfter(createdAt)
                || deadline.isAfter(createdAt.plusSeconds(8)) || expiresAt.isBefore(deadline)
                || expiresAt.isAfter(createdAt.plusSeconds(900))) { throw new IllegalArgumentException("INVALID_TURN_REQUEST"); }
        if (state == State.COMPLETED) {
            if (resultVersion == null || resultVersion != admittedVersion + 1 || encryptedResult == null
                    || encryptedResult.length <= 28 || encryptedResult.length > MAX_RESULT_BYTES) {
                throw new IllegalArgumentException("INVALID_TURN_RESULT");
            }
            encryptedResult = encryptedResult.clone();
        } else if (resultVersion != null || encryptedResult != null) { throw new IllegalArgumentException("UNEXPECTED_TURN_RESULT"); }
    }
    /**
     * 读取不共享底层数组的结果密文。
     * @return 密文防御性副本，调用方负责清理自己的副本
     */
    @Override public byte[] encryptedResult() { return encryptedResult == null ? null : encryptedResult.clone(); }
    /**
     * 检查同key内容，绝不把不同正文当成已有结果。
     * @param digest 同owner/session/key重新计算的HMAC
     */
    public void requireSameRequest(String digest) {
        if (!requestDigest.equals(digest)) { throw failure(AssistantReason.IDEMPOTENCY_CONFLICT); }
    }
    /**
     * 原执行栅栏在截止前提交；同一提交重试须读持久结果，不再次调用此状态转换。
     * @param token 原始执行栅栏
     * @param version 受理后会话版本
     * @param result 含来源证明的绑定密文，不能是模型原始结果
     * @param now DB当前时间
     * @return 完成快照
     */
    public AssistantTurnRequest complete(UUID token, long version, byte[] result, Instant now) {
        current(now);
        if (state != State.PROCESSING || !leaseToken.equals(token) || admittedVersion != version
                || !now.isBefore(deadline)) { throw failure(AssistantReason.RESULT_STALE); }
        return copy(State.COMPLETED, admittedVersion + 1, result);
    }
    /**
     * 仅处理已到总截止的原请求，不续租或产生新token。
     * @param now DB当前时间
     * @return 过期终态；其他终态保持不变
     */
    public AssistantTurnRequest expire(Instant now) {
        current(now);
        if (state != State.PROCESSING) { return this; }
        if (now.isBefore(deadline)) { throw failure(AssistantReason.STALE_REQUEST); }
        return copy(State.EXPIRED, null, null);
    }
    /**
     * 关闭/撤权/来源失效后移除结果引用；物理清理由后续存储负责。
     * @param revoked true为失效，false为用户关闭
     * @param now DB当前时间
     * @return 不再携带结果的终态快照
     */
    public AssistantTurnRequest invalidate(boolean revoked, Instant now) {
        current(now); return copy(revoked ? State.INVALIDATED : State.CANCELLED, null, null);
    }
    /**
     * 只允许已完成且尚在保留期的结果进入后续解密/来源与同意复验，不代表可以直接返回。
     * @param now DB当前时间
     * @return 密文副本
     */
    public byte[] resultForRevalidation(Instant now) {
        current(now);
        if (state != State.COMPLETED || !now.isBefore(expiresAt)) { throw failure(AssistantReason.RESULT_STALE); }
        return encryptedResult();
    }
    private void current(Instant now) {
        time(now); if (now.isBefore(createdAt)) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
    }
    private AssistantTurnRequest copy(State next, Long version, byte[] result) {
        return new AssistantTurnRequest(id, owner, sessionId, purpose, keyHash, requestDigest, admittedVersion,
                leaseToken, createdAt, deadline, expiresAt, next, version, result);
    }
    private static void time(Instant value) {
        if (value == null || value.getNano() % 1_000_000 != 0) { throw new IllegalArgumentException("INVALID_TURN_TIME"); }
    }
    private static AssistantSessionException failure(AssistantReason reason) { return new AssistantSessionException(reason); }
    @Override public String toString() { return "AssistantTurnRequest[state=" + state + ", redacted]"; }
}
