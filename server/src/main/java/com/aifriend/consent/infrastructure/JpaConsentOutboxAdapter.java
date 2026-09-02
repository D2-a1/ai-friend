package com.aifriend.consent.infrastructure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentOutboxPort;
import com.aifriend.consent.domain.ConsentType;

/**
 * 授权撤回清理事件 JPA Outbox 适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaConsentOutboxAdapter implements ConsentOutboxPort {

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 创建授权 Outbox 适配器。
     *
     * @param repository Outbox Repository
     * @param objectMapper JSON 序列化器
     */
    public JpaConsentOutboxAdapter(OutboxEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入与授权记录同事务提交的撤回事件。
     *
     * @param consentRecordId 授权记录 UUID
     * @param userId 用户 UUID
     * @param type 授权类型
     * @param createdAt 创建时间
     * @throws IllegalStateException 当最小事件负载无法序列化时抛出
     */
    @Override
    public void appendRevocation(UUID consentRecordId, UUID userId, ConsentType type, Instant createdAt) {
        String payload = toPayload(userId, type);
        repository.save(new OutboxEventEntity(
                UUID.randomUUID(),
                "CONSENT_RECORD",
                consentRecordId,
                "CONSENT_REVOKED",
                payload,
                createdAt));
    }

    /**
     * 生成不含微信身份、令牌、语音或正文的最小撤回事件负载。
     *
     * @param userId 用户内部 UUID
     * @param type 授权类型
     * @return 最小 JSON 负载
     * @throws IllegalStateException 当 JSON 序列化失败时抛出
     */
    private String toPayload(UUID userId, ConsentType type) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "userId", userId.toString(),
                    "consentType", type.name()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("授权撤回事件序列化失败", exception);
        }
    }
}
