package com.aifriend.assistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;

/**
 * 独立问答会话不可变状态；仓储仍须以owner+version做原子CAS并复验授权。
 * 不产生联系任务，不代替持久幂等表或返回前证据复验。
 * @param id 会话ID
 * @param owner 认证账号
 * @param purpose 不可跨模式切换的用途
 * @param policyVersion 建立时用途政策
 * @param state 生命周期
 * @param version 单调版本
 * @param createdAt 创建时刻
 * @param lastActivityAt 最近已接受状态变更
 * @param expiresAt 空闲/总时限中更早者
 * @param acceptedQuestions 已受理问题数（包括失败问题）
 * @param pending 唯一在途请求
 * @param conversation 已完成且有可复验结果引用的历史
 * @author Codex
 * @since 1.0.0
 */
public record AssistantSession(UUID id, UUID owner, Purpose purpose, String policyVersion, State state, long version,
        Instant createdAt, Instant lastActivityAt, Instant expiresAt, int acceptedQuestions,
        Optional<Pending> pending, AssistantConversation conversation) {
    /** 与HTTP生命周期枚举一致。 */
    public enum State {
        /** 会话开放，仍须逐次验证授权及期限。 */
        OPEN,
        /** 会话已主动关闭，不能继续受理问题。 */
        CLOSED,
        /** 会话已到期，不能继续受理问题。 */
        EXPIRED
    }

    /** 验证持久化恢复也必须满足的结构不变量。 */
    public AssistantSession {
        Objects.requireNonNull(id); Objects.requireNonNull(owner); Objects.requireNonNull(purpose);
        Objects.requireNonNull(state); Objects.requireNonNull(pending); Objects.requireNonNull(conversation);
        requireTime(createdAt); requireTime(lastActivityAt); requireTime(expiresAt);
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,64}") || version < 0
                || acceptedQuestions < 0 || acceptedQuestions > 4 || conversation.purpose() != purpose
                || conversation.turns().size() > acceptedQuestions || lastActivityAt.isBefore(createdAt)
                || expiresAt.isAfter(createdAt.plusSeconds(900)) || !expiresAt.isAfter(createdAt)
                || (state == State.OPEN && (!expiresAt.equals(expiry(createdAt, lastActivityAt))
                    || !lastActivityAt.isBefore(expiresAt)))
                || (state != State.OPEN && (pending.isPresent() || !conversation.turns().isEmpty()))) {
            throw new IllegalArgumentException("INVALID_ASSISTANT_SESSION");
        }
        if (pending.isPresent()) {
            Pending work = pending.get();
            if (acceptedQuestions < 1 || conversation.turns().size() >= acceptedQuestions
                    || work.startedAt().isBefore(createdAt) || work.startedAt().isAfter(lastActivityAt)
                    || work.deadline().isAfter(expiresAt)
                    || conversation.turns().stream().anyMatch(turn -> turn.requestId().equals(work.requestId()))) {
                throw new IllegalArgumentException("INVALID_PENDING_REQUEST");
            }
        }
        if (conversation.turns().stream().anyMatch(turn -> turn.resultVersion() > version)) {
            throw new IllegalArgumentException("FUTURE_CONTEXT_VERSION");
        }
    }

    /**
     * 建立空会话；去重及当前授权由调用方完成。
     * @param id 服务端ID
     * @param owner 已认证账号
     * @param purpose 用途
     * @param policy 当前政策
     * @param now 可信毫秒级时间
     * @return 初始版本0的会话
     */
    public static AssistantSession create(UUID id, UUID owner, Purpose purpose, String policy, Instant now) {
        requireTime(now);
        return new AssistantSession(id, owner, purpose, policy, State.OPEN, 0, now, now, now.plusSeconds(300),
                0, Optional.empty(), new AssistantConversation(purpose, List.of()));
    }

    /**
     * 受理新问题，不允许并发第二个问题或第五个问题。
     * @param expectedVersion 客户端期望版本
     * @param requestId 服务端持久请求ID
     * @param leaseToken 本次执行的随机栅栏
     * @param now 受理时间（包含后续排队的总期限从这里开始）
     * @return 新版本与冻结截止
     */
    public AssistantSession begin(long expectedVersion, UUID requestId, UUID leaseToken, Instant now) {
        requireOpen(expectedVersion, now);
        if (pending.isPresent()) { throw failure(AssistantReason.STALE_REQUEST); }
        if (acceptedQuestions == 4) { throw failure(AssistantReason.RESOURCE_LIMIT); }
        if (conversation.turns().stream().anyMatch(turn -> turn.requestId().equals(requestId))) {
            throw failure(AssistantReason.IDEMPOTENCY_CONFLICT);
        }
        Instant nextExpiry = expiry(createdAt, now);
        return copy(State.OPEN, nextVersion(), now, nextExpiry, acceptedQuestions + 1,
                Optional.of(new Pending(requestId, leaseToken, now, earlier(now.plusSeconds(8), nextExpiry))), conversation);
    }

    /**
     * 接收未过期的同一执行结果；调用前仍须复验授权/来源。
     * @param expectedVersion 受理后冻结的会话版本
     * @param leaseToken 唯一执行栅栏
     * @param turn 成功结果的引用和摘要；失败结果可为空但仍消耗本问题
     * @param now 完成时间
     * @return 新版本、清除在途状态
     */
    public AssistantSession complete(long expectedVersion, UUID leaseToken, Optional<AssistantConversation.Turn> turn, Instant now) {
        requireOpen(expectedVersion, now); Pending work = requirePending(leaseToken);
        if (!now.isBefore(work.deadline())) { throw failure(AssistantReason.RESULT_STALE); }
        Objects.requireNonNull(turn);
        long next = nextVersion();
        if (turn.isPresent() && (!turn.get().requestId().equals(work.requestId()) || turn.get().resultVersion() != next)) {
            throw failure(AssistantReason.RESULT_STALE);
        }
        return copy(State.OPEN, next, now, expiry(createdAt, now), acceptedQuestions, Optional.empty(),
                turn.map(conversation::append).orElse(conversation));
    }

    /**
     * 将到期执行标记为结束，不接收其答案；不顺延原请求截止。
     * @param expectedVersion 受理版本
     * @param leaseToken 原栅栏
     * @param now 当前时间
     * @return 无在途请求的新版本
     */
    public AssistantSession timeout(long expectedVersion, UUID leaseToken, Instant now) {
        requireOpen(expectedVersion, now); Pending work = requirePending(leaseToken);
        if (now.isBefore(work.deadline())) { throw failure(AssistantReason.STALE_REQUEST); }
        // 后台超时不是用户活动，不延长空闲期限。
        return copy(State.OPEN, nextVersion(), lastActivityAt, expiresAt, acceptedQuestions, Optional.empty(), conversation);
    }

    /**
     * 显式关闭会话并丢弃内存上下文/在途资格；不执行数据库删除。
     * @param expectedVersion 当前版本
     * @param now 当前时间
     * @return 终态快照，已关闭时幂等
     */
    public AssistantSession close(long expectedVersion, Instant now) {
        requireVersionTime(expectedVersion, now);
        if (state != State.OPEN) { return this; }
        return copy(now.isBefore(expiresAt) ? State.CLOSED : State.EXPIRED, nextVersion(), now, expiresAt,
                acceptedQuestions, Optional.empty(), new AssistantConversation(purpose, List.of()));
    }

    private void requireOpen(long expected, Instant now) {
        requireVersionTime(expected, now);
        if (state == State.CLOSED) { throw failure(AssistantReason.SESSION_CLOSED); }
        if (state == State.EXPIRED || !now.isBefore(expiresAt)) { throw failure(AssistantReason.SESSION_EXPIRED); }
    }
    private void requireVersionTime(long expected, Instant now) {
        requireTime(now);
        if (version != expected) { throw failure(AssistantReason.VERSION_MISMATCH); }
        if (now.isBefore(lastActivityAt)) { throw failure(AssistantReason.CLOCK_UNRELIABLE); }
    }
    private Pending requirePending(UUID token) {
        if (pending.isEmpty() || !pending.get().leaseToken().equals(token)) { throw failure(AssistantReason.RESULT_STALE); }
        return pending.get();
    }
    private long nextVersion() {
        if (version == Long.MAX_VALUE) { throw failure(AssistantReason.RESOURCE_LIMIT); }
        return version + 1;
    }
    private AssistantSession copy(State nextState, long nextVersion, Instant activity, Instant expiry, int count,
            Optional<Pending> work, AssistantConversation context) {
        return new AssistantSession(id, owner, purpose, policyVersion, nextState, nextVersion, createdAt, activity, expiry, count, work, context);
    }
    private static Instant expiry(Instant created, Instant activity) { return earlier(created.plusSeconds(900), activity.plusSeconds(300)); }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static void requireTime(Instant time) {
        if (time == null || time.getNano() % 1_000_000 != 0) { throw new IllegalArgumentException("INVALID_SESSION_TIME"); }
    }
    private static AssistantSessionException failure(AssistantReason reason) { return new AssistantSessionException(reason); }

    /**
     * 一个问题的持久执行资格；不得重试时换token或延长deadline。
     * @param requestId 问题ID
     * @param leaseToken 随机执行栅栏
     * @param startedAt 受理时刻
     * @param deadline 最多八秒，含排队
     */
    public record Pending(UUID requestId, UUID leaseToken, Instant startedAt, Instant deadline) {
        /** 拒绝空身份或无界期限。 */
        public Pending {
            Objects.requireNonNull(requestId); Objects.requireNonNull(leaseToken);
            requireTime(startedAt); requireTime(deadline);
            if (!deadline.isAfter(startedAt) || deadline.isAfter(startedAt.plusSeconds(8))) {
                throw new IllegalArgumentException("INVALID_PENDING_DEADLINE");
            }
        }
        @Override public String toString() { return "AssistantPending[redacted]"; }
    }
    @Override public String toString() { return "AssistantSession[purpose=" + purpose + ", state=" + state + ", version=" + version + "]"; }
}
