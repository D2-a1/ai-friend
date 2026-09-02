package com.aifriend.contact.infrastructure;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.contact.application.ContactAliasOutboxPort;

/**
 * MySQL 联系人称呼 Outbox 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaContactAliasOutboxAdapter implements ContactAliasOutboxPort {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, event_type, payload_json,
                status, available_at, created_at
            ) VALUES (?, 'CONTACT_ALIAS', ?, ?, ?, 'PENDING', ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建联系人称呼 Outbox 适配器。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器
     */
    public JpaContactAliasOutboxAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public void appendCreated(
            UUID aliasId,
            UUID bindingId,
            UUID ownerUserId,
            Instant createdAt) {
        append(aliasId, bindingId, ownerUserId, "CONTACT_ALIAS_CREATED", createdAt);
    }

    /** {@inheritDoc} */
    @Override
    public void appendDeleted(
            UUID aliasId,
            UUID bindingId,
            UUID ownerUserId,
            Instant createdAt) {
        append(aliasId, bindingId, ownerUserId, "CONTACT_ALIAS_DELETED", createdAt);
    }

    private void append(
            UUID aliasId,
            UUID bindingId,
            UUID ownerUserId,
            String eventType,
            Instant createdAt) {
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbcTemplate.update(
                INSERT_SQL,
                uuidBytes(UUID.randomUUID()),
                uuidBytes(aliasId),
                eventType,
                toPayload(bindingId, ownerUserId),
                timestamp,
                timestamp);
    }

    private String toPayload(UUID bindingId, UUID ownerUserId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "bindingId", bindingId.toString(),
                    "ownerUserId", ownerUserId.toString(),
                    "rebuildAliasProjection", true,
                    "clearAliasCaches", true));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("联系人称呼事件序列化失败", exception);
        }
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
