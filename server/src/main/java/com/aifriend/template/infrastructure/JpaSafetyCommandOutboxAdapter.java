package com.aifriend.template.infrastructure;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.template.application.SafetyCommandOutboxPort;

/**
 * MySQL 安全指令本地投影与缓存刷新 Outbox 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaSafetyCommandOutboxAdapter implements SafetyCommandOutboxPort {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, event_type, payload_json,
                status, available_at, created_at
            ) VALUES (?, 'SAFETY_COMMAND_ENROLLMENT', ?,
                'SAFETY_COMMAND_TEMPLATES_REPLACED', ?, 'PENDING', ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建安全指令 Outbox 适配器。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器
     */
    public JpaSafetyCommandOutboxAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public void appendReplaced(
            UUID enrollmentId,
            UUID ownerUserId,
            Instant occurredAt) {
        Timestamp timestamp = Timestamp.from(occurredAt);
        jdbcTemplate.update(
                INSERT_SQL,
                uuidBytes(UUID.randomUUID()),
                uuidBytes(enrollmentId),
                toPayload(ownerUserId),
                timestamp,
                timestamp);
    }

    private String toPayload(UUID ownerUserId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ownerUserId", ownerUserId.toString(),
                    "rebuildSafetyCommandProjection", true,
                    "clearSafetyCommandCaches", true));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("安全指令事件序列化失败", exception);
        }
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
