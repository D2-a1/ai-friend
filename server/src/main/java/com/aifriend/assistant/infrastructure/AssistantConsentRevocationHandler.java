package com.aifriend.assistant.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.aifriend.assistant.application.AssistantSessionLifecyclePort;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;

/**
 * 独立用途撤权清理扩展，生命周期端口加入既有事务；由AssistantSessionConfiguration在开关关闭时也装配。
 * @author Codex
 * @since 1.0.0
 */
public final class AssistantConsentRevocationHandler implements ConsentRevocationCleanupHandler {
    private final AssistantSessionLifecyclePort lifecycle;
    /**
     * 创建按独立用途撤回问答资料的处理器。
     * @param lifecycle 同事务清理端口
     */
    public AssistantConsentRevocationHandler(AssistantSessionLifecyclePort lifecycle) { this.lifecycle=Objects.requireNonNull(lifecycle); }
    /** {@inheritDoc} */
    @Override public void cleanup(UUID userId,ConsentType type,Instant revokedAt) {
        if(type==ConsentType.KNOWLEDGE_MODEL) lifecycle.revoke(userId,Purpose.PUBLIC_KNOWLEDGE);
        else if(type==ConsentType.CONTACT_GRAPH) lifecycle.revoke(userId,Purpose.CONTACT_GRAPH);
    }
}
