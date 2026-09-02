package com.aifriend.voice.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.aifriend.identity.infrastructure.AppUserJpaRepository;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectRepositoryPort;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;

/**
 * MySQL 临时音频对象持久化适配器。
 *
 * <p>对象键只在当前调用内存中解密，落库前每次使用随机 IV 重新加密。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JpaAudioObjectAdapter implements AudioObjectRepositoryPort {

    private final AudioObjectJpaRepository repository;
    private final AppUserJpaRepository userRepository;
    private final SensitiveDataProtector sensitiveDataProtector;

    /**
     * 创建临时音频对象持久化适配器。
     *
     * @param repository 音频对象 Repository
     * @param userRepository owner 账号锁 Repository
     * @param sensitiveDataProtector 对象键加解密器
     */
    public JpaAudioObjectAdapter(
            AudioObjectJpaRepository repository,
            AppUserJpaRepository userRepository,
            SensitiveDataProtector sensitiveDataProtector) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.sensitiveDataProtector = sensitiveDataProtector;
    }

    /**
     * 锁定 owner 账号，串行化当前用户的创建幂等检查。
     *
     * @param ownerUserId owner UUID
     * @throws BusinessException 当前账号不存在时抛出
     */
    @Override
    public void lockOwner(UUID ownerUserId) {
        userRepository.findByIdForUpdate(ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AudioObject> findByIdempotencyKeyForUpdate(
            UUID ownerUserId,
            byte[] idempotencyKeyDigest) {
        return repository.findByOwnerAndIdempotencyKeyForUpdate(
                ownerUserId, idempotencyKeyDigest).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AudioObject> findByIdForUpdate(UUID audioObjectId) {
        return repository.findByIdForUpdate(audioObjectId).map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AudioObject> findByIdAndOwner(
            UUID audioObjectId,
            UUID ownerUserId) {
        return repository.findByIdAndOwnerUserId(audioObjectId, ownerUserId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AudioObject> findByIdAndOwnerForUpdate(
            UUID audioObjectId,
            UUID ownerUserId) {
        return repository.findByIdAndOwnerForUpdate(audioObjectId, ownerUserId)
                .map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public List<AudioObject> findExpiredIssued(Instant now, int batchSize) {
        return repository
                .findByStatusAndUploadedAtIsNullAndUploadExpiresAtLessThanEqualOrderByUploadExpiresAtAsc(
                        AudioObjectStatus.ISSUED, now, PageRequest.of(0, batchSize))
                .getContent().stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public AudioObject save(AudioObject audioObject) {
        AudioObjectEntity saved = repository.save(new AudioObjectEntity(
                audioObject.id(),
                audioObject.ownerUserId(),
                audioObject.purpose(),
                audioObject.mediaType(),
                audioObject.expectedSizeBytes(),
                audioObject.expectedDurationMs(),
                audioObject.expectedSha256(),
                sensitiveDataProtector.encrypt(audioObject.objectKey()),
                audioObject.uploadTokenDigest(),
                audioObject.idempotencyKeyDigest(),
                audioObject.requestDigest(),
                audioObject.status(),
                audioObject.uploadExpiresAt(),
                audioObject.retentionUntil(),
                audioObject.storageVersion(),
                audioObject.uploadedAt(),
                audioObject.consumedAt(),
                audioObject.deletedAt(),
                audioObject.version(),
                audioObject.createdAt(),
                audioObject.updatedAt()));
        return toDomain(saved);
    }

    private AudioObject toDomain(AudioObjectEntity entity) {
        return new AudioObject(
                entity.getId(),
                entity.getOwnerUserId(),
                entity.getPurpose(),
                entity.getMediaType(),
                entity.getExpectedSizeBytes(),
                entity.getExpectedDurationMs(),
                entity.getExpectedSha256(),
                sensitiveDataProtector.decrypt(entity.getObjectKeyCipher()),
                entity.getUploadTokenHash(),
                entity.getIdempotencyKeyHash(),
                entity.getRequestHash(),
                entity.getStatus(),
                entity.getUploadExpiresAt(),
                entity.getRetentionUntil(),
                entity.getStorageVersion(),
                entity.getUploadedAt(),
                entity.getConsumedAt(),
                entity.getDeletedAt(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
