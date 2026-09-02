package com.aifriend.retention.infrastructure;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.retention.application.AccountClosureAlertAcknowledgement;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementRepositoryPort;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementTiming;
import com.aifriend.retention.application.AccountClosureAlertAcknowledgementWrite;
import com.aifriend.retention.application.AccountClosureAlertAudience;

/**
 * 使用 MySQL 短事务写入注销 P0 告警唯一接手事实的适配器。
 *
 * <p>锁定注销记录后才判断及时或迟到；迟到且尚未升级时，同事务补写升级时间和
 * Outbox，避免调度延迟使迟到确认错误压掉升级。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountClosureAlertAcknowledgementRepositoryAdapter
        implements AccountClosureAlertAcknowledgementRepositoryPort {

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建注销 P0 告警接手确认数据库适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcAccountClosureAlertAcknowledgementRepositoryAdapter(
            JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountClosureAlertAcknowledgement acknowledge(
            AccountClosureAlertAcknowledgementWrite write) {
        List<TargetRow> targets = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(r.id),d.audience,d.event_type,d.status,"
                        + "r.p0_opened_at,r.acknowledgement_due_at,r.acknowledged_at,"
                        + "r.escalated_at FROM account_closure_alert_delivery d "
                        + "JOIN outbox_event o ON o.id=d.outbox_event_id "
                        + "JOIN account_closure_request r ON r.id=o.aggregate_id "
                        + "WHERE d.id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new TargetRow(
                        UUID.fromString(resultSet.getString(1)),
                        AccountClosureAlertAudience.valueOf(resultSet.getString(2)),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        instant(resultSet.getTimestamp(5)),
                        instant(resultSet.getTimestamp(6)),
                        instant(resultSet.getTimestamp(7)),
                        instant(resultSet.getTimestamp(8))),
                write.deliveryId().toString());
        TargetRow target = requireTarget(targets, write);
        List<AcknowledgementRow> existing = findExisting(target.closureRequestId());
        if (!existing.isEmpty()) {
            return replay(existing, write);
        }
        if (target.acknowledgedAt() != null) {
            throw new IllegalStateException("注销 P0 已存在无法核验的接手时间");
        }
        AccountClosureAlertAcknowledgementTiming timing = write.acknowledgedAt()
                .isBefore(target.acknowledgementDueAt())
                ? AccountClosureAlertAcknowledgementTiming.TIMELY
                : AccountClosureAlertAcknowledgementTiming.LATE;
        UUID acknowledgementId = UUID.randomUUID();
        insertAcknowledgement(acknowledgementId, target, write, timing);
        if (timing == AccountClosureAlertAcknowledgementTiming.LATE
                && target.escalatedAt() == null) {
            markAcknowledgedAndEscalated(target.closureRequestId(), write.acknowledgedAt());
            appendEscalation(target.closureRequestId(), write.acknowledgedAt());
        } else {
            markAcknowledged(target.closureRequestId(), write.acknowledgedAt());
        }
        return new AccountClosureAlertAcknowledgement(
                acknowledgementId,
                write.audience(),
                timing,
                target.acknowledgementDueAt(),
                write.acknowledgedAt());
    }

    private TargetRow requireTarget(
            List<TargetRow> targets,
            AccountClosureAlertAcknowledgementWrite write) {
        if (targets.size() != 1) {
            throw new IllegalStateException("注销 P0 告警投递不存在或不唯一");
        }
        TargetRow target = targets.get(0);
        if (!"ACCOUNT_CLOSURE_P0_OPENED".equals(target.eventType())
                || !"DELIVERED".equals(target.deliveryStatus())
                || target.p0OpenedAt() == null
                || target.acknowledgementDueAt() == null) {
            throw new IllegalStateException("只有已经送达的 P0 告警可以确认接手");
        }
        if (target.audience() != write.audience()) {
            throw new IllegalStateException("运维身份责任组与告警投递不匹配");
        }
        if (write.acknowledgedAt().isBefore(target.p0OpenedAt())) {
            throw new IllegalStateException("接手时间不能早于 P0 告警时间");
        }
        return target;
    }

    private List<AcknowledgementRow> findExisting(UUID closureRequestId) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),audience,timing,acknowledgement_due_at,"
                        + "acknowledged_at,idempotency_key_hash,request_hash "
                        + "FROM account_closure_alert_acknowledgement "
                        + "WHERE closure_request_id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new AcknowledgementRow(
                        UUID.fromString(resultSet.getString(1)),
                        AccountClosureAlertAudience.valueOf(resultSet.getString(2)),
                        AccountClosureAlertAcknowledgementTiming.valueOf(
                                resultSet.getString(3)),
                        resultSet.getTimestamp(4).toInstant(),
                        resultSet.getTimestamp(5).toInstant(),
                        resultSet.getBytes(6),
                        resultSet.getBytes(7)),
                closureRequestId.toString());
    }

    private AccountClosureAlertAcknowledgement replay(
            List<AcknowledgementRow> rows,
            AccountClosureAlertAcknowledgementWrite write) {
        if (rows.size() != 1) {
            throw new IllegalStateException("注销 P0 接手确认事实不唯一");
        }
        AcknowledgementRow row = rows.get(0);
        if (!MessageDigest.isEqual(row.idempotencyKeyHash(), write.idempotencyKeyHash())
                || !MessageDigest.isEqual(row.requestHash(), write.requestHash())) {
            throw new IllegalStateException("注销 P0 接手确认幂等冲突");
        }
        return new AccountClosureAlertAcknowledgement(
                row.id(),
                row.audience(),
                row.timing(),
                row.acknowledgementDueAt(),
                row.acknowledgedAt());
    }

    private void insertAcknowledgement(
            UUID acknowledgementId,
            TargetRow target,
            AccountClosureAlertAcknowledgementWrite write,
            AccountClosureAlertAcknowledgementTiming timing) {
        int changed = jdbcTemplate.update(
                "INSERT INTO account_closure_alert_acknowledgement("
                        + "id,closure_request_id,delivery_id,audience,responder_subject_hash,"
                        + "authentication_context_hash,idempotency_key_hash,request_hash,"
                        + "timing,acknowledgement_due_at,acknowledged_at,created_at) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,?,?)",
                acknowledgementId.toString(),
                target.closureRequestId().toString(),
                write.deliveryId().toString(),
                write.audience().name(),
                write.responderSubjectHash(),
                write.authenticationContextHash(),
                write.idempotencyKeyHash(),
                write.requestHash(),
                timing.name(),
                Timestamp.from(target.acknowledgementDueAt()),
                Timestamp.from(write.acknowledgedAt()),
                Timestamp.from(write.acknowledgedAt()));
        if (changed != 1) {
            throw new IllegalStateException("注销 P0 接手确认写入不完整");
        }
    }

    private void markAcknowledged(UUID closureRequestId, Instant acknowledgedAt) {
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_request SET acknowledged_at=?,updated_at=?,"
                        + "version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND acknowledged_at IS NULL",
                Timestamp.from(acknowledgedAt),
                Timestamp.from(acknowledgedAt),
                closureRequestId.toString());
        requireSingleUpdate(changed);
    }

    private void markAcknowledgedAndEscalated(
            UUID closureRequestId,
            Instant acknowledgedAt) {
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_request SET acknowledged_at=?,escalated_at=?,"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND acknowledged_at IS NULL AND escalated_at IS NULL",
                Timestamp.from(acknowledgedAt),
                Timestamp.from(acknowledgedAt),
                Timestamp.from(acknowledgedAt),
                closureRequestId.toString());
        requireSingleUpdate(changed);
    }

    private void appendEscalation(UUID closureRequestId, Instant occurredAt) {
        int changed = jdbcTemplate.update(
                "INSERT INTO outbox_event(id,aggregate_type,aggregate_id,event_type,"
                        + "payload_json,status,available_at,created_at) VALUES(UUID_TO_BIN(?),"
                        + "'ACCOUNT_CLOSURE',UUID_TO_BIN(?),'ACCOUNT_CLOSURE_P0_ESCALATED',"
                        + "JSON_OBJECT('occurredAt',?),'PENDING',?,?)",
                UUID.randomUUID().toString(),
                closureRequestId.toString(),
                occurredAt.toString(),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        requireSingleUpdate(changed);
    }

    private void requireSingleUpdate(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("注销 P0 接手状态写入不完整");
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record TargetRow(
            UUID closureRequestId,
            AccountClosureAlertAudience audience,
            String eventType,
            String deliveryStatus,
            Instant p0OpenedAt,
            Instant acknowledgementDueAt,
            Instant acknowledgedAt,
            Instant escalatedAt) {
    }

    private record AcknowledgementRow(
            UUID id,
            AccountClosureAlertAudience audience,
            AccountClosureAlertAcknowledgementTiming timing,
            Instant acknowledgementDueAt,
            Instant acknowledgedAt,
            byte[] idempotencyKeyHash,
            byte[] requestHash) {
    }
}
