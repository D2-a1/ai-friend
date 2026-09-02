package com.aifriend.retention.infrastructure;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.retention.application.AccountClosureRepositoryPort;
import com.aifriend.retention.application.AccountClosureView;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * MySQL 账号注销可靠受理事务适配器。
 *
 * <p>固定先锁定 {@code app_user}，再读取注销记录并失效子资源，保持与其他 owner 写事务
 * 一致的锁顺序。所有 SQL 都从认证 owner 派生范围，不接受客户端 owner。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountClosureRepositoryAdapter implements AccountClosureRepositoryPort {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建账号注销持久化适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcAccountClosureRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountClosureView accept(
            UUID ownerUserId,
            byte[] idempotencyKeyHash,
            byte[] requestHash,
            Long expectedVersion,
            Instant acceptedAt,
            Instant reRegistrationNotBefore) {
        AccountRow account = lockAccount(ownerUserId);
        List<ClosureRow> sameKey = queryClosure(
                "SELECT request_hash,accepted_at,re_registration_not_before "
                        + "FROM account_closure_request WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND idempotency_key_hash=?",
                ownerUserId,
                idempotencyKeyHash);
        if (!sameKey.isEmpty()) {
            ClosureRow replay = sameKey.get(0);
            if (!MessageDigest.isEqual(replay.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return replay.view();
        }
        List<ClosureRow> existing = queryClosure(
                "SELECT request_hash,accepted_at,re_registration_not_before "
                        + "FROM account_closure_request WHERE owner_user_id=UUID_TO_BIN(?)",
                ownerUserId,
                null);
        if (!existing.isEmpty() || !"ACTIVE".equals(account.status())) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED);
        }
        if (expectedVersion != null && expectedVersion.longValue() != account.version()) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }

        UUID closureId = UUID.randomUUID();
        Timestamp now = Timestamp.from(acceptedAt);
        jdbcTemplate.update(
                "INSERT INTO account_closure_request(id,owner_user_id,account_generation,"
                        + "idempotency_key_hash,request_hash,status,accepted_at,"
                        + "re_registration_not_before,next_attempt_at,version,created_at,updated_at) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,'ACCEPTED',?,?,?,0,?,?)",
                closureId.toString(), ownerUserId.toString(), account.generation(),
                idempotencyKeyHash, requestHash, now, Timestamp.from(reRegistrationNotBefore),
                now, now, now);
        deactivateAccount(ownerUserId, acceptedAt);
        jdbcTemplate.update(
                "INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,"
                        + "status,available_at,created_at) VALUES(UUID_TO_BIN(?),'ACCOUNT_CLOSURE',"
                        + "UUID_TO_BIN(?),'ACCOUNT_CLOSURE_ACCEPTED',"
                        + "JSON_OBJECT('acceptedAt',?,'reRegistrationNotBefore',?),"
                        + "'PENDING',?,?)",
                UUID.randomUUID().toString(), closureId.toString(), acceptedAt.toString(),
                reRegistrationNotBefore.toString(), now, now);
        return new AccountClosureView(acceptedAt, reRegistrationNotBefore);
    }

    private AccountRow lockAccount(UUID ownerUserId) {
        List<AccountRow> rows = jdbcTemplate.query(
                "SELECT status,account_generation,version FROM app_user "
                        + "WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new AccountRow(
                        resultSet.getString(1), resultSet.getLong(2), resultSet.getLong(3)),
                ownerUserId.toString());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return rows.get(0);
    }

    private List<ClosureRow> queryClosure(String sql, UUID ownerUserId, byte[] keyHash) {
        Object[] arguments = keyHash == null
                ? new Object[]{ownerUserId.toString()}
                : new Object[]{ownerUserId.toString(), keyHash};
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new ClosureRow(
                        resultSet.getBytes(1),
                        resultSet.getTimestamp(2).toInstant(),
                        resultSet.getTimestamp(3).toInstant()),
                arguments);
    }

    private void deactivateAccount(UUID ownerUserId, Instant acceptedAt) {
        Timestamp now = Timestamp.from(acceptedAt);
        String owner = ownerUserId.toString();
        jdbcTemplate.update(
                "UPDATE app_user SET status='DELETING',updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='ACTIVE'",
                now, owner);
        jdbcTemplate.update(
                "UPDATE refresh_token SET status='REVOKED',revoked_at=?,version=version+1 "
                        + "WHERE user_id=UUID_TO_BIN(?) AND status<>'REVOKED'",
                now, owner);
        jdbcTemplate.update(
                "UPDATE token_family SET status='REVOKED',revoked_at=?,version=version+1 "
                        + "WHERE user_id=UUID_TO_BIN(?) AND status<>'REVOKED'",
                now, owner);
        jdbcTemplate.update(
                "UPDATE invitation_session session JOIN contact_invitation invitation "
                        + "ON invitation.id=session.invitation_id SET session.status='TERMINATED',"
                        + "session.terminated_at=?,session.version=session.version+1 "
                        + "WHERE invitation.owner_user_id=UUID_TO_BIN(?) "
                        + "AND session.status NOT IN ('TERMINATED','EXPIRED')",
                now, owner);
        jdbcTemplate.update(
                "UPDATE contact_invitation SET status='REVOKED',version=version+1 "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND status NOT IN ('ACCEPTED','DECLINED','REVOKED','EXPIRED')",
                owner);
        jdbcTemplate.update(
                "UPDATE contact_binding SET status='REVOKED',contact_subject_cipher=NULL,"
                        + "wechat_locator_cipher=NULL,wechat_locator_hash=NULL,remark_cipher=NULL,"
                        + "local_verification_version=NULL,verified_at=NULL,revoked_at=?,updated_at=?,"
                        + "version=version+1 WHERE owner_user_id=UUID_TO_BIN(?) AND status<>'REVOKED'",
                now, now, owner);
        jdbcTemplate.update(
                "UPDATE task_session SET state='CANCELLED',updated_at=?,session_version=session_version+1 "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND state NOT IN "
                        + "('REJECTED','CANCELLED','COMPLETED','PARTIAL','FAILED')",
                now, owner);
    }

    private record AccountRow(String status, long generation, long version) {
    }

    private record ClosureRow(byte[] requestHash, Instant acceptedAt, Instant reRegistrationNotBefore) {
        private AccountClosureView view() {
            return new AccountClosureView(acceptedAt, reRegistrationNotBefore);
        }
    }
}
