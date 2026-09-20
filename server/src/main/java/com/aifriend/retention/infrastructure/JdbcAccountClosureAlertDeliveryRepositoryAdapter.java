package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertDeliveryRepositoryPort;
import com.aifriend.retention.application.AccountClosureAlertSubmission;
import com.aifriend.retention.application.AccountClosureAlertType;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 使用 MySQL 短事务物化、重试并确认注销告警投递的适配器。
 *
 * <p>源 Outbox 只在全部责任组投递事实写入后才置为已完成；外部通知调用不在本适配器
 * 事务内执行。数据库不保存 owner、用户内容、接收人地址或回执原文。供应商消息流水号
 * 仅在 SUBMITTED 阶段以 AES-GCM 密文短期保存，最终送达后立即清除。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcAccountClosureAlertDeliveryRepositoryAdapter
        implements AccountClosureAlertDeliveryRepositoryPort {

    /** 参数化数据库访问组件。 */
    private final JdbcTemplate jdbcTemplate;
    /** 供应商流水号加密组件。 */
    private final SensitiveDataProtector sensitiveDataProtector;

    /**
     * 创建注销告警投递数据库适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param sensitiveDataProtector 敏感数据保护器
     */
    public JdbcAccountClosureAlertDeliveryRepositoryAdapter(
            JdbcTemplate jdbcTemplate,
            SensitiveDataProtector sensitiveDataProtector) {
        this.jdbcTemplate = jdbcTemplate;
        this.sensitiveDataProtector = sensitiveDataProtector;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int materializePending(Instant now, int batchSize) {
        Objects.requireNonNull(now, "告警物化时间不能为空");
        int safeBatchSize = requireBatchSize(batchSize);
        List<OutboxAlertRow> events = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),event_type,"
                        + "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.occurredAt')),"
                        + "JSON_UNQUOTE(JSON_EXTRACT("
                        + "payload_json,'$.acknowledgementDueAt')) "
                        + "FROM outbox_event WHERE aggregate_type='ACCOUNT_CLOSURE' "
                        + "AND status='PENDING' AND available_at<=? AND event_type IN ("
                        + "'ACCOUNT_CLOSURE_DELAY_WARNING','ACCOUNT_CLOSURE_P0_OPENED',"
                        + "'ACCOUNT_CLOSURE_P0_ESCALATED',"
                        + "'ACCOUNT_CLOSURE_DEADLINE_BREACHED') "
                        + "ORDER BY available_at,id LIMIT " + safeBatchSize
                        + " FOR UPDATE SKIP LOCKED",
                (resultSet, rowNumber) -> new OutboxAlertRow(
                        UUID.fromString(resultSet.getString(1)),
                        AccountClosureAlertType.valueOf(resultSet.getString(2)),
                        parseInstant(resultSet.getString(3), "告警发生时间"),
                        parseOptionalInstant(resultSet.getString(4))),
                Timestamp.from(now));
        int materialized = 0;
        Timestamp timestamp = Timestamp.from(now);
        for (OutboxAlertRow event : events) {
            requireAcknowledgementBoundary(event);
            for (AccountClosureAlertAudience audience : event.type().audiences()) {
                int changed = jdbcTemplate.update(
                        "INSERT INTO account_closure_alert_delivery("
                                + "id,outbox_event_id,audience,event_type,occurred_at,"
                                + "acknowledgement_due_at,status,retry_count,next_attempt_at,"
                                + "created_at,updated_at,version) "
                                + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,"
                                + "'PENDING',0,?,?,?,0)",
                        UUID.randomUUID().toString(),
                        event.outboxEventId().toString(),
                        audience.name(),
                        event.type().name(),
                        Timestamp.from(event.occurredAt()),
                        timestamp(event.acknowledgementDueAt()),
                        timestamp,
                        timestamp,
                        timestamp);
                if (changed != 1) {
                    throw new IllegalStateException("注销告警责任组投递物化不完整");
                }
                materialized++;
            }
            int consumed = jdbcTemplate.update(
                    "UPDATE outbox_event SET status='COMPLETED' "
                            + "WHERE id=UUID_TO_BIN(?) AND status='PENDING'",
                    event.outboxEventId().toString());
            if (consumed != 1) {
                throw new IllegalStateException("注销告警源事件消费不完整");
            }
        }
        return materialized;
    }

    /** {@inheritDoc} */
    @Override
    public List<AccountClosureAlertDelivery> listReady(Instant now, int batchSize) {
        Objects.requireNonNull(now, "告警查询时间不能为空");
        int safeBatchSize = requireBatchSize(batchSize);
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),audience,event_type,occurred_at,"
                        + "acknowledgement_due_at,retry_count,status,provider_reference_cipher "
                        + "FROM account_closure_alert_delivery "
                        + "WHERE status IN ('PENDING','SUBMITTED') "
                        + "AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT ?",
                (resultSet, rowNumber) -> {
                    String status = resultSet.getString(7);
                    byte[] providerReferenceCipher = resultSet.getBytes(8);
                    String providerReference = null;
                    if ("SUBMITTED".equals(status)) {
                        if (providerReferenceCipher == null) {
                            throw new IllegalStateException("已提交告警缺少供应商流水号");
                        }
                        providerReference = sensitiveDataProtector.decrypt(
                                providerReferenceCipher);
                    } else if (providerReferenceCipher != null) {
                        throw new IllegalStateException("未提交告警不得携带供应商流水号");
                    }
                    return new AccountClosureAlertDelivery(
                            UUID.fromString(resultSet.getString(1)),
                            AccountClosureAlertAudience.valueOf(resultSet.getString(2)),
                            AccountClosureAlertType.valueOf(resultSet.getString(3)),
                            resultSet.getTimestamp(4).toInstant(),
                            instant(resultSet.getTimestamp(5)),
                            resultSet.getInt(6),
                            providerReference);
                },
                Timestamp.from(now),
                safeBatchSize);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmSubmitted(
            UUID deliveryId,
            String providerReference,
            Instant submittedAt,
            Instant verifyAt) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(submittedAt, "告警提交时间不能为空");
        requireFutureAttempt(submittedAt, verifyAt, "告警首次复验时间");
        new AccountClosureAlertSubmission(providerReference);
        List<SubmissionRow> rows = jdbcTemplate.query(
                "SELECT status,provider_reference_cipher "
                        + "FROM account_closure_alert_delivery "
                        + "WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new SubmissionRow(
                        resultSet.getString(1), resultSet.getBytes(2)),
                deliveryId.toString());
        if (rows.isEmpty()) {
            return false;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("注销告警提交状态不唯一");
        }
        SubmissionRow row = rows.get(0);
        if ("SUBMITTED".equals(row.status())) {
            if (!sameProviderReference(row.providerReferenceCipher(), providerReference)) {
                throw new IllegalStateException("注销告警供应商流水号冲突");
            }
            return true;
        }
        if (!"PENDING".equals(row.status()) || row.providerReferenceCipher() != null) {
            return false;
        }
        byte[] providerReferenceCipher = sensitiveDataProtector.encrypt(providerReference);
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_alert_delivery SET status='SUBMITTED',"
                        + "provider_reference_cipher=?,last_attempt_at=?,next_attempt_at=?,"
                        + "updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='PENDING' "
                        + "AND provider_reference_cipher IS NULL",
                providerReferenceCipher,
                Timestamp.from(submittedAt),
                Timestamp.from(verifyAt),
                Timestamp.from(submittedAt),
                deliveryId.toString());
        if (changed != 1) {
            throw new IllegalStateException("注销告警提交确认写入不完整");
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void scheduleVerification(
            UUID deliveryId,
            Instant checkedAt,
            Instant verifyAt) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(checkedAt, "告警复验时间不能为空");
        requireFutureAttempt(checkedAt, verifyAt, "告警下次复验时间");
        jdbcTemplate.update(
                "UPDATE account_closure_alert_delivery SET last_attempt_at=?,"
                        + "next_attempt_at=?,updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='SUBMITTED'",
                Timestamp.from(checkedAt),
                Timestamp.from(verifyAt),
                Timestamp.from(checkedAt),
                deliveryId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public void resetFailedSubmission(
            UUID deliveryId,
            Instant attemptedAt,
            Instant nextAttemptAt) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(attemptedAt, "告警尝试时间不能为空");
        requireFutureAttempt(attemptedAt, nextAttemptAt, "告警下次提交时间");
        jdbcTemplate.update(
                "UPDATE account_closure_alert_delivery SET status='PENDING',"
                        + "provider_reference_cipher=NULL,retry_count=retry_count+1,"
                        + "last_attempt_at=?,next_attempt_at=?,updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='SUBMITTED'",
                Timestamp.from(attemptedAt),
                Timestamp.from(nextAttemptAt),
                Timestamp.from(attemptedAt),
                deliveryId.toString());
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmDelivered(
            UUID deliveryId,
            byte[] receiptHash,
            Instant deliveredAt) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(deliveredAt, "告警投递确认时间不能为空");
        requireDigest(receiptHash);
        List<DeliveryReceiptRow> rows = jdbcTemplate.query(
                "SELECT status,receipt_hash FROM account_closure_alert_delivery "
                        + "WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new DeliveryReceiptRow(
                        resultSet.getString(1), resultSet.getBytes(2)),
                deliveryId.toString());
        if (rows.isEmpty()) {
            return false;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("注销告警投递状态不唯一");
        }
        DeliveryReceiptRow row = rows.get(0);
        if ("DELIVERED".equals(row.status())) {
            if (!MessageDigest.isEqual(row.receiptHash(), receiptHash)) {
                throw new IllegalStateException("注销告警投递回执冲突");
            }
            return true;
        }
        if (!"SUBMITTED".equals(row.status())) {
            return false;
        }
        int changed = jdbcTemplate.update(
                "UPDATE account_closure_alert_delivery SET status='DELIVERED',"
                        + "delivered_at=?,receipt_hash=?,provider_reference_cipher=NULL,"
                        + "last_attempt_at=?,updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status='SUBMITTED'",
                Timestamp.from(deliveredAt),
                receiptHash,
                Timestamp.from(deliveredAt),
                Timestamp.from(deliveredAt),
                deliveryId.toString());
        if (changed != 1) {
            throw new IllegalStateException("注销告警投递确认写入不完整");
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void markRetry(
            UUID deliveryId,
            Instant attemptedAt,
            Instant nextAttemptAt) {
        Objects.requireNonNull(deliveryId, "告警投递 UUID 不能为空");
        Objects.requireNonNull(attemptedAt, "告警尝试时间不能为空");
        requireFutureAttempt(attemptedAt, nextAttemptAt, "告警下次尝试时间");
        jdbcTemplate.update(
                "UPDATE account_closure_alert_delivery SET retry_count=retry_count+1,"
                        + "last_attempt_at=?,next_attempt_at=?,updated_at=?,version=version+1 "
                        + "WHERE id=UUID_TO_BIN(?) AND status IN ('PENDING','SUBMITTED')",
                Timestamp.from(attemptedAt),
                Timestamp.from(nextAttemptAt),
                Timestamp.from(attemptedAt),
                deliveryId.toString());
    }

    private boolean sameProviderReference(byte[] cipher, String expectedReference) {
        if (cipher == null) {
            return false;
        }
        byte[] actual = sensitiveDataProtector.decrypt(cipher).getBytes(UTF_8);
        byte[] expected = expectedReference.getBytes(UTF_8);
        try {
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private void requireAcknowledgementBoundary(OutboxAlertRow event) {
        if (event.type() == AccountClosureAlertType.ACCOUNT_CLOSURE_P0_OPENED) {
            if (event.acknowledgementDueAt() == null
                    || !event.acknowledgementDueAt().isAfter(event.occurredAt())) {
                throw new IllegalStateException("注销 P0 告警缺少有效接手截止时间");
            }
        } else if (event.acknowledgementDueAt() != null) {
            throw new IllegalStateException("非 P0 注销告警不得携带接手截止时间");
        }
    }

    private int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("注销告警投递批次必须在 1 至 100 之间");
        }
        return batchSize;
    }

    private void requireDigest(byte[] value) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException("注销告警投递回执摘要无效");
        }
    }

    private void requireFutureAttempt(Instant now, Instant next, String label) {
        if (next == null || !next.isAfter(now)) {
            throw new IllegalArgumentException(label + "必须晚于当前尝试");
        }
    }

    private Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + "缺失");
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalStateException(fieldName + "无效", exception);
        }
    }

    private Instant parseOptionalInstant(String value) {
        return value == null ? null : parseInstant(value, "告警接手截止时间");
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record OutboxAlertRow(
            UUID outboxEventId,
            AccountClosureAlertType type,
            Instant occurredAt,
            Instant acknowledgementDueAt) {
    }

    private record SubmissionRow(String status, byte[] providerReferenceCipher) {
    }

    private record DeliveryReceiptRow(String status, byte[] receiptHash) {
    }
}