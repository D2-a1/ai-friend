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

import com.aifriend.contact.application.ContactCleanupOutboxPort;

/**
 * MySQL 联系人解绑清理事件 Outbox 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaContactCleanupOutboxAdapter implements ContactCleanupOutboxPort {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_event (
                id, aggregate_type, aggregate_id, event_type, payload_json,
                status, available_at, created_at
            ) VALUES (?, 'CONTACT_BINDING', ?, 'CONTACT_UNBOUND', ?, 'PENDING', ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 创建联系人解绑 Outbox 适配器。
     *
     * @param jdbcTemplate JDBC 模板
     * @param objectMapper JSON 序列化器
     */
    public JpaContactCleanupOutboxAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入不含微信主体、定位或备注的最小清理事件。
     *
     * @param contactId 联系人绑定 UUID
     * @param ownerUserId owner UUID
     * @param createdAt 创建时间
     * @throws IllegalStateException 当最小负载无法序列化时抛出
     */
    @Override
    public void appendUnbound(UUID contactId, UUID ownerUserId, Instant createdAt) {
        String payload = toPayload(ownerUserId);
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbcTemplate.update(
                INSERT_SQL,
                uuidBytes(UUID.randomUUID()),
                uuidBytes(contactId),
                payload,
                timestamp,
                timestamp);
    }

    private String toPayload(UUID ownerUserId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ownerUserId", ownerUserId.toString(),
                    "clearAliases", true,
                    "clearTaskContext", true,
                    "clearCaches", true));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("联系人解绑事件序列化失败", exception);
        }
    }

    private byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
