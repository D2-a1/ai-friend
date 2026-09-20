package com.aifriend.consent.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.application.ConsentRevocationCleanupPort;
import com.aifriend.consent.domain.ConsentType;

/**
 * 顺序执行所有业务域撤权清理的组合适配器。
 *
 * <p>由 {@code ConsentService} 的事务包围；任何处理器失败都会回滚授权记录与全部清理。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class CompositeConsentRevocationCleanupAdapter
        implements ConsentRevocationCleanupPort {

    private final List<ConsentRevocationCleanupHandler> handlers;

    /**
     * 创建组合适配器。
     *
     * @param handlers 业务域清理处理器
     */
    public CompositeConsentRevocationCleanupAdapter(
            List<ConsentRevocationCleanupHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup(UUID userId, ConsentType type, Instant revokedAt) {
        handlers.forEach(handler -> handler.cleanup(userId, type, revokedAt));
    }
}
