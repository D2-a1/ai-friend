package com.aifriend.personalization.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;

/**
 * 长期个人偏好授权撤回的同步内容清理器。
 *
 * <p>撤回事务中立即清空密文和摘要，只保留不含内容的删除事实。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class PersonalMemoryConsentRevocationHandler
        implements ConsentRevocationCleanupHandler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建撤权清理器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public PersonalMemoryConsentRevocationHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup(UUID userId, ConsentType type, Instant revokedAt) {
        if (type != ConsentType.PERSONAL_MEMORY) {
            return;
        }
        Timestamp timestamp = Timestamp.from(revokedAt);
        jdbcTemplate.update(
                "UPDATE personal_assistant_memory SET preferences_cipher=NULL,"
                        + "preferences_digest=NULL,status='DELETED',"
                        + "update_idempotency_key_hash=NULL,update_request_hash=NULL,"
                        + "delete_idempotency_key_hash=NULL,delete_request_hash=NULL,"
                        + "deleted_at=?,updated_at=?,version=version+1 "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE'",
                timestamp, timestamp, userId.toString());
    }
}
