package com.aifriend.assistant.application;

import java.util.Optional;
import java.util.UUID;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantSession;

/**
 * 会话创建/读取存储；问答受理/完成仍须后续同事务请求账本，不得以本端口拼装非原子执行。
 * @author Codex
 * @since 1.0.0
 */
public interface AssistantSessionRepository {
    /**
     * 同owner同创建键重放同一会话，不延长期限；不同用途冲突。
     * @param owner 认证主体，不能直接绑定请求正文
     * @param purpose 独立用途
     * @param clientRequestId 原始幂等键
     * @return 原始或新建会话；已过期会话不会被复活
     */
    AssistantSession create(UUID owner, Purpose purpose, String clientRequestId);
    /**
     * 返回当前归属的有效会话，不能用于直接返回缓存答案。
     * @param owner 认证主体
     * @param sessionId 目标会话
     * @return 不存在时empty；过期/关闭/政策不符失败关闭
     */
    Optional<AssistantSession> find(UUID owner, UUID sessionId);
}
