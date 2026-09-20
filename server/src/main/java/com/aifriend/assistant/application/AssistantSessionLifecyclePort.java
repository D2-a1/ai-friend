package com.aifriend.assistant.application;

import java.util.Objects;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantSession;

/**
 * 会话内容生命周期；关闭/撤权保留幂等墓碑，账号注销由既有分批清理删除两张表。
 * @author Codex
 * @since 1.0.0
 */
public interface AssistantSessionLifecyclePort {
    /**
     * 关闭指定会话并原子清理结果及上下文；不要求同意仍有效，不读取内容。
     * @param owner 认证ACTIVE主体
     * @param sessionId 会话ID
     * @param expectedVersion 当前版本；已终态重复关闭不再推进版本
     * @return 不含内容的关闭结果
     */
    Closed close(UUID owner,UUID sessionId,long expectedVersion);
    /**
     * 加入既有撤权事务，仅清理指定owner和用途；不受功能开关控制。
     * @param owner 撤权主体
     * @param purpose 独立用途
     * @return 改动的会话与请求行数
     */
    int revoke(UUID owner,Purpose purpose);
    /**
     * 有界清理到期会话；每个候选均锁owner后重新读DB时间，不因旧扫描误清活跃会话。
     * 独立知识维护开关开启时自动调度，每轮最多16个候选；关闭/撤权入口不依赖该开关。
     * @param limit 1至64个候选
     * @return 实际终结的会话数
     */
    int expireBatch(int limit);
    /**
     * 关闭结果。
     * @param sessionId 目标会话
     * @param version 终态版本
     * @param state CLOSED或EXPIRED
     * @param purpose 原会话用途
     * @param expiresAt 原会话截止，不因关闭延长
     */
    record Closed(UUID sessionId,long version,AssistantSession.State state,Purpose purpose,java.time.Instant expiresAt) {
        /** 不允许返回OPEN冒充关闭完成。 */
        public Closed {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(state);
            Objects.requireNonNull(purpose); Objects.requireNonNull(expiresAt);
            if(version<0 || state==AssistantSession.State.OPEN) throw new IllegalArgumentException("INVALID_CLOSE_RESULT");
        }
        @Override public String toString() { return "AssistantClosed[state="+state+"]"; }
    }
}
