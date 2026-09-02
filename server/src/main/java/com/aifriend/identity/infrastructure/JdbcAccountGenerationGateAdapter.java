package com.aifriend.identity.infrastructure;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.identity.application.AccountGenerationGatePort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * MySQL 删除墓碑账号代次门禁。
 *
 * <p>调用方必须在账号创建事务中使用本适配器。最新墓碑行使用写锁，保证双门禁判断与
 * 新账号创建属于同一个事务边界。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountGenerationGateAdapter implements AccountGenerationGatePort {

    private static final String LATEST_TOMBSTONE_SQL = """
            SELECT old_account_generation,re_registration_not_before
            FROM deletion_tombstone
            WHERE subject_hash=?
            ORDER BY old_account_generation DESC
            LIMIT 1 FOR UPDATE
            """;

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建账号代次门禁适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcAccountGenerationGateAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public long nextGeneration(byte[] subjectHash, Instant now) {
        List<TombstoneGate> tombstones = jdbcTemplate.query(
                LATEST_TOMBSTONE_SQL,
                (resultSet, rowNumber) -> new TombstoneGate(
                        resultSet.getLong("old_account_generation"),
                        resultSet.getTimestamp("re_registration_not_before").toInstant()),
                subjectHash);
        if (tombstones.isEmpty()) {
            return 1L;
        }
        TombstoneGate latest = tombstones.get(0);
        if (now.isBefore(latest.reRegistrationNotBefore())) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED);
        }
        return Math.addExact(latest.oldAccountGeneration(), 1L);
    }

    private record TombstoneGate(long oldAccountGeneration, Instant reRegistrationNotBefore) {
    }
}
