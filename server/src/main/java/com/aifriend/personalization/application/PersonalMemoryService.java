package com.aifriend.personalization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.personalization.domain.PersonalMemoryRecord;
import com.aifriend.personalization.domain.PersonalMemoryStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 长期个人偏好的查看、更正和删除用例服务。
 *
 * <p>创建/更正要求能力开关和当前政策版本的独立同意；查看及删除不会因开关关闭而受阻。
 * 偏好内容不能确认任务、选择联系人或生成动作计划。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class PersonalMemoryService {

    private final PersonalMemoryRepositoryPort repositoryPort;
    private final ConsentGrantQueryPort consentPort;
    private final PersonalMemoryProperties properties;
    private final PersonalMemoryCodec codec;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建长期偏好服务。
     *
     * @param repositoryPort 加密偏好持久化端口
     * @param consentPort 分项授权查询端口
     * @param properties 功能开关和政策版本
     * @param codec 偏好保护器
     * @param digestService 幂等摘要器
     * @param auditEventPort 审计事件端口
     * @param clock UTC 时钟
     */
    public PersonalMemoryService(
            PersonalMemoryRepositoryPort repositoryPort,
            ConsentGrantQueryPort consentPort,
            PersonalMemoryProperties properties,
            PersonalMemoryCodec codec,
            DigestService digestService,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.consentPort = consentPort;
        this.properties = properties;
        this.codec = codec;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 查看当前长期偏好；能力关闭时仍允许用户读取和删除已有数据。
     *
     * @param ownerUserId 当前用户 UUID
     * @return 当前管理状态
     */
    @Transactional(readOnly = true)
    public PersonalMemorySnapshot get(UUID ownerUserId) {
        boolean consentGranted = hasCurrentConsent(ownerUserId);
        return snapshot(repositoryPort.findByOwner(ownerUserId).orElse(null), consentGranted);
    }

    /**
     * 创建或以乐观锁更正长期偏好。
     *
     * @param ownerUserId 当前用户 UUID
     * @param idempotencyKey 幂等键原文，只在当前调用内存中使用
     * @param preferences 三个有限偏好
     * @param expectedVersion 首次创建为 0，更正时为当前版本
     * @return 更新后的管理状态
     */
    @Transactional(rollbackFor = Exception.class)
    public PersonalMemorySnapshot update(
            UUID ownerUserId,
            String idempotencyKey,
            PersonalMemoryPreferences preferences,
            long expectedVersion) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED,
                    "长期个人偏好功能尚未开启");
        }
        if (!hasCurrentConsent(ownerUserId)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED,
                    "请先明确同意长期个人偏好授权");
        }
        if (preferences == null || expectedVersion < 0L
                || !validIdempotencyKey(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(
                idempotencyKey + "|" + codec.canonical(preferences)
                        + "|" + expectedVersion);
        PersonalMemoryRecord current = repositoryPort
                .findByOwnerForUpdate(ownerUserId).orElse(null);
        if (current != null
                && sameDigest(current.updateIdempotencyKeyHash(), keyHash)) {
            if (!sameDigest(current.updateRequestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return snapshot(current, true);
        }
        if ((current == null && expectedVersion != 0L)
                || (current != null && current.version() != expectedVersion)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        Instant now = Instant.now(clock);
        PersonalMemoryCodec.EncodedPersonalMemory encoded = codec.encode(preferences);
        PersonalMemoryRecord next = new PersonalMemoryRecord(
                ownerUserId, encoded.cipher(), encoded.digest(), properties.policyVersion(),
                PersonalMemoryStatus.ACTIVE, keyHash, requestHash, null, null,
                current == null ? 1L : current.version() + 1L,
                current == null ? now : current.createdAt(), now, null);
        PersonalMemoryRecord saved = repositoryPort.save(next);
        auditEventPort.append(ownerUserId, "PERSONAL_MEMORY_UPDATED", "SUCCESS",
                "PERSONAL_MEMORY", now);
        return snapshot(saved, true);
    }

    /**
     * 清除偏好内容并保留不含内容的最小幂等墓碑。
     *
     * @param ownerUserId 当前用户 UUID
     * @param idempotencyKey 幂等键原文，只在当前调用内存中使用
     * @param confirmed 用户明确确认删除
     * @param expectedVersion 当前资源版本
     * @return 删除后的管理状态
     */
    @Transactional(rollbackFor = Exception.class)
    public PersonalMemorySnapshot delete(
            UUID ownerUserId,
            String idempotencyKey,
            boolean confirmed,
            long expectedVersion) {
        if (!confirmed || expectedVersion < 1L
                || !validIdempotencyKey(idempotencyKey)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(
                idempotencyKey + "|personal-memory-delete-v1|"
                        + expectedVersion + "|true");
        PersonalMemoryRecord current = repositoryPort.findByOwnerForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (current.status() == PersonalMemoryStatus.DELETED) {
            if (sameDigest(current.deleteIdempotencyKeyHash(), keyHash)
                    && sameDigest(current.deleteRequestHash(), requestHash)) {
                return snapshot(current, hasCurrentConsent(ownerUserId));
            }
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (current.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        Instant now = Instant.now(clock);
        PersonalMemoryRecord deleted = new PersonalMemoryRecord(
                ownerUserId, null, null, current.policyVersion(),
                PersonalMemoryStatus.DELETED, null, null, keyHash, requestHash,
                current.version() + 1L, current.createdAt(), now, now);
        PersonalMemoryRecord saved = repositoryPort.save(deleted);
        auditEventPort.append(ownerUserId, "PERSONAL_MEMORY_DELETED", "SUCCESS",
                "PERSONAL_MEMORY", now);
        return snapshot(saved, hasCurrentConsent(ownerUserId));
    }

    private boolean validIdempotencyKey(String value) {
        return StringUtils.hasText(value)
                && value.length() >= 16 && value.length() <= 128;
    }

    private boolean sameDigest(byte[] expected, byte[] actual) {
        return expected != null && actual != null
                && digestService.constantTimeEquals(expected, actual);
    }

    private boolean hasCurrentConsent(UUID ownerUserId) {
        return consentPort.isGrantedForPolicy(
                ownerUserId, ConsentType.PERSONAL_MEMORY, properties.policyVersion());
    }

    private PersonalMemorySnapshot snapshot(
            PersonalMemoryRecord record,
            boolean consentGranted) {
        if (record == null) {
            return new PersonalMemorySnapshot(properties.enabled(), consentGranted,
                    properties.policyVersion(), null, null, null);
        }
        PersonalMemoryPreferences preferences = record.status() == PersonalMemoryStatus.ACTIVE
                ? codec.decode(record.preferencesCipher(), record.preferencesDigest()) : null;
        return new PersonalMemorySnapshot(properties.enabled(), consentGranted,
                properties.policyVersion(), preferences, record.version(), record.updatedAt());
    }
}
