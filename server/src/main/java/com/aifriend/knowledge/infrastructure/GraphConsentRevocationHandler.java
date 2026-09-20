package com.aifriend.knowledge.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.knowledge.application.GraphOwnerCleanupPort;

/**
 * 独立关系用途撤回时同步清理投影；不开新事务、不受查询开关控制。
 * 失败向上传播，使撤权记录与清理在既有事务中一起回滚。
 * @author Codex
 * @since 1.0.0
 */
@Component
public final class GraphConsentRevocationHandler implements ConsentRevocationCleanupHandler {
    private final GraphOwnerCleanupPort cleanup;

    /**
     * 创建清理处理器。
     * @param cleanup 加入调用方事务的图谱清理端口
     */
    public GraphConsentRevocationHandler(GraphOwnerCleanupPort cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    /** {@inheritDoc} */
    @Override public void cleanup(UUID userId, ConsentType type, Instant revokedAt) {
        if (type == ConsentType.CONTACT_GRAPH) { cleanup.purgeOwner(userId); }
    }
}
