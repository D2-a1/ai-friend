package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.retention.application.AccountClosureJob;
import com.aifriend.retention.application.AccountClosureJobRepositoryPort;

/**
 * MySQL 账号注销异步作业、重试和可靠内部告警适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountClosureJobRepositoryAdapter implements AccountClosureJobRepositoryPort {
    /** 24 小时预警阈值。 */
    private static final Duration WARNING_AGE = Duration.ofHours(24);
    /** 48 小时 P0 阈值。 */
    private static final Duration P0_AGE = Duration.ofHours(48);
    /** 72 小时在线删除硬期限。 */
    private static final Duration DEADLINE_AGE = Duration.ofHours(72);
    /** P0 接手确认窗口。 */
    private static final Duration ACKNOWLEDGEMENT_WINDOW = Duration.ofMinutes(15);

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建账号注销作业适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcAccountClosureJobRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public List<AccountClosureJob> findReady(Instant now, int batchSize) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),BIN_TO_UUID(owner_user_id),accepted_at,retry_count "
                        + "FROM account_closure_request WHERE status='ACCEPTED' "
                        + "AND next_attempt_at<=? ORDER BY next_attempt_at LIMIT ?",
                (resultSet, rowNumber) -> new AccountClosureJob(
                        UUID.fromString(resultSet.getString(1)),
                        UUID.fromString(resultSet.getString(2)),
                        resultSet.getTimestamp(3).toInstant(),
                        resultSet.getInt(4)),
                Timestamp.from(now),
                batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public void markRetry(UUID jobId, Instant attemptedAt, Instant nextAttemptAt) {
        jdbcTemplate.update(
                "UPDATE account_closure_request SET retry_count=retry_count+1,last_attempt_at=?,"
                        + "next_attempt_at=?,updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='ACCEPTED'",
                Timestamp.from(attemptedAt),
                Timestamp.from(nextAttemptAt),
                Timestamp.from(attemptedAt),
                jobId.toString());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int publishDueAlerts(Instant now, int batchSize) {
        int safeBatchSize = requireBatchSize(batchSize);
        List<AlertRow> rows = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),accepted_at,warning_24h_at,p0_opened_at,"
                        + "acknowledgement_due_at,acknowledged_at,escalated_at,"
                        + "deadline_breached_at "
                        + "FROM account_closure_request WHERE status='ACCEPTED' "
                        + "AND accepted_at<=? ORDER BY accepted_at LIMIT " + safeBatchSize
                        + " FOR UPDATE SKIP LOCKED",
                (resultSet, rowNumber) -> new AlertRow(
                        UUID.fromString(resultSet.getString(1)),
                        instant(resultSet.getTimestamp(2)),
                        instant(resultSet.getTimestamp(3)),
                        instant(resultSet.getTimestamp(4)),
                        instant(resultSet.getTimestamp(5)),
                        instant(resultSet.getTimestamp(6)),
                        instant(resultSet.getTimestamp(7)),
                        instant(resultSet.getTimestamp(8))),
                Timestamp.from(now.minus(WARNING_AGE)));
        int published = 0;
        for (AlertRow row : rows) {
            if (row.warningAt() == null && !row.acceptedAt().plus(WARNING_AGE).isAfter(now)) {
                published += markAndAppend(
                        row.id(), "warning_24h_at", "ACCOUNT_CLOSURE_DELAY_WARNING", now, null);
            }
            Instant acknowledgementDueAt = row.acknowledgementDueAt();
            if (row.p0OpenedAt() == null && !row.acceptedAt().plus(P0_AGE).isAfter(now)) {
                acknowledgementDueAt = now.plus(ACKNOWLEDGEMENT_WINDOW);
                published += markP0AndAppend(row.id(), now, acknowledgementDueAt);
            }
            if (row.p0OpenedAt() != null
                    && row.acknowledgedAt() == null
                    && row.escalatedAt() == null
                    && acknowledgementDueAt != null
                    && !acknowledgementDueAt.isAfter(now)) {
                published += markAndAppend(
                        row.id(), "escalated_at", "ACCOUNT_CLOSURE_P0_ESCALATED", now, null);
            }
            if (row.deadlineBreachedAt() == null
                    && !row.acceptedAt().plus(DEADLINE_AGE).isAfter(now)) {
                published += markAndAppend(
                        row.id(), "deadline_breached_at",
                        "ACCOUNT_CLOSURE_DEADLINE_BREACHED", now, null);
            }
        }
        return published;
    }

    private int markP0AndAppend(UUID jobId, Instant now, Instant acknowledgementDueAt) {
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_request SET p0_opened_at=?,acknowledgement_due_at=?,"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND status='ACCEPTED' AND p0_opened_at IS NULL",
                Timestamp.from(now),
                Timestamp.from(acknowledgementDueAt),
                Timestamp.from(now),
                jobId.toString());
        if (changed == 1) {
            appendAlert(jobId, "ACCOUNT_CLOSURE_P0_OPENED", now, acknowledgementDueAt);
        }
        return changed;
    }

    private int markAndAppend(
            UUID jobId,
            String timestampColumn,
            String eventType,
            Instant now,
            Instant acknowledgementDueAt) {
        if (!List.of(
                "warning_24h_at", "escalated_at", "deadline_breached_at")
                .contains(timestampColumn)) {
            throw new IllegalArgumentException("不支持的账号注销告警字段");
        }
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_request SET " + timestampColumn + "=?,updated_at=?,"
                        + "version=version+1 WHERE id=UUID_TO_BIN(?) AND status='ACCEPTED' "
                        + "AND " + timestampColumn + " IS NULL",
                Timestamp.from(now), Timestamp.from(now), jobId.toString());
        if (changed == 1) {
            appendAlert(jobId, eventType, now, acknowledgementDueAt);
        }
        return changed;
    }

    private void appendAlert(
            UUID jobId,
            String eventType,
            Instant occurredAt,
            Instant acknowledgementDueAt) {
        Timestamp now = Timestamp.from(occurredAt);
        String payload = acknowledgementDueAt == null
                ? "JSON_OBJECT('occurredAt',?)"
                : "JSON_OBJECT('occurredAt',?,'acknowledgementDueAt',?)";
        if (acknowledgementDueAt == null) {
            jdbcTemplate.update(
                    insertOutboxSql(payload),
                    UUID.randomUUID().toString(), jobId.toString(), eventType,
                    occurredAt.toString(), now, now);
        } else {
            jdbcTemplate.update(
                    insertOutboxSql(payload),
                    UUID.randomUUID().toString(), jobId.toString(), eventType,
                    occurredAt.toString(), acknowledgementDueAt.toString(), now, now);
        }
    }

    private String insertOutboxSql(String payload) {
        return "INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,"
                + "status,available_at,created_at) VALUES(UUID_TO_BIN(?),'ACCOUNT_CLOSURE',"
                + "UUID_TO_BIN(?),?," + payload + ",'PENDING',?,?)";
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("账号注销告警批次必须在 1 至 100 之间");
        }
        return batchSize;
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record AlertRow(
            UUID id,
            Instant acceptedAt,
            Instant warningAt,
            Instant p0OpenedAt,
            Instant acknowledgementDueAt,
            Instant acknowledgedAt,
            Instant escalatedAt,
            Instant deadlineBreachedAt) {
    }
}
