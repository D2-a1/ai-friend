package com.aifriend.voice.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 音频对象事务外读取与一次性消费编排服务。
 *
 * <p>首先按 owner 范围读取元数据快照，然后在不持有数据库锁的情况下
 * 执行对象存储读取、魔数和解码校验，最后交给独立事务服务加锁复验。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AudioObjectConsumptionService {

    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final AudioObjectStoragePort audioObjectStoragePort;
    private final AudioObjectContentValidationService contentValidationService;
    private final AudioObjectConsumptionTransactionService transactionService;
    private final Clock clock;

    /**
     * 创建音频对象一次性消费编排服务。
     *
     * @param audioObjectRepositoryPort 音频元数据持久化端口
     * @param audioObjectStoragePort 私有音频对象存储端口
     * @param contentValidationService 音频内容完整校验服务
     * @param transactionService 事务内锁定与一次性提交服务
     * @param clock UTC 时钟
     */
    public AudioObjectConsumptionService(
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioObjectStoragePort audioObjectStoragePort,
            AudioObjectContentValidationService contentValidationService,
            AudioObjectConsumptionTransactionService transactionService,
            Clock clock) {
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.audioObjectStoragePort = audioObjectStoragePort;
        this.contentValidationService = contentValidationService;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    /**
     * 校验并一次性消费指定 owner 和用途的音频对象。
     *
     * @param ownerUserId 当前已认证用户 UUID，不能来自客户端自报
     * @param publicAudioObjectId au_ 前缀音频对象编号
     * @param requiredPurpose 当前业务接口要求的唯一用途
     * @param consumer 只执行有界数据库逻辑的消费回调
     * @param <T> 业务消费结果类型
     * @return 业务消费结果
     * @throws BusinessException 当对象不存在、越权、用途错误、内容无效或发生版本冲突时抛出
     */
    public <T> T consume(
            UUID ownerUserId,
            String publicAudioObjectId,
            AudioPurpose requiredPurpose,
            AudioObjectConsumer<T> consumer) {
        if (consumer == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<ValidatedAudioObject> validated = validateAll(
                ownerUserId, List.of(publicAudioObjectId), requiredPurpose);
        return transactionService.consumeAll(
                validated,
                audioObjects -> consumer.consume(audioObjects.get(0)));
    }

    /**
     * 在事务外按请求顺序读取并完整校验一组互不相同的音频对象。
     *
     * <p>该方法只生成待事务内复验的快照，不会推进音频状态。调用方必须随后使用
     * {@link AudioObjectConsumptionTransactionService#consumeAll(List, AudioObjectBatchConsumer)}
     * 在业务事务内原子消费。
     *
     * @param ownerUserId 当前已认证用户 UUID，不能来自客户端自报
     * @param publicAudioObjectIds au_ 前缀音频对象编号，数量 1—10
     * @param requiredPurpose 当前业务接口要求的唯一用途
     * @return 按请求原顺序排列的已校验音频快照
     * @throws BusinessException 对象重复、不存在、越权、用途错误或内容无效时抛出
     */
    public List<ValidatedAudioObject> validateAll(
            UUID ownerUserId,
            List<String> publicAudioObjectIds,
            AudioPurpose requiredPurpose) {
        if (ownerUserId == null || requiredPurpose == null
                || publicAudioObjectIds == null || publicAudioObjectIds.isEmpty()
                || publicAudioObjectIds.size() > 10) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<UUID> audioObjectIds = publicAudioObjectIds.stream()
                .map(PublicIdCodec::parseAudioObjectId)
                .toList();
        if (new HashSet<>(audioObjectIds).size() != audioObjectIds.size()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        Instant now = Instant.now(clock);
        return audioObjectIds.stream()
                .map(audioObjectId -> validateOne(
                        ownerUserId, audioObjectId, requiredPurpose, now))
                .toList();
    }

    private ValidatedAudioObject validateOne(
            UUID ownerUserId,
            UUID audioObjectId,
            AudioPurpose requiredPurpose,
            Instant now) {
        AudioObject audioObject = audioObjectRepositoryPort
                .findByIdAndOwner(audioObjectId, ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUDIO_INVALID));
        validateReadable(audioObject, requiredPurpose, now);
        try (StoredAudioObject storedAudioObject = readStorage(audioObject)) {
            return contentValidationService.validate(
                    audioObject, storedAudioObject);
        }
    }

    private StoredAudioObject readStorage(AudioObject audioObject) {
        if (audioObject.storageVersion() == null) {
            return audioObjectStoragePort.readCurrent(
                    audioObject.objectKey(),
                    audioObject.expectedSizeBytes());
        }
        return audioObjectStoragePort.readExact(
                audioObject.objectKey(),
                audioObject.storageVersion(),
                audioObject.expectedSizeBytes());
    }

    private void validateReadable(
            AudioObject audioObject,
            AudioPurpose requiredPurpose,
            Instant now) {
        if (audioObject.purpose() != requiredPurpose) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        if (audioObject.status() != AudioObjectStatus.ISSUED) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        boolean hasUploadedAt = audioObject.uploadedAt() != null;
        boolean hasStorageVersion = audioObject.storageVersion() != null
                && !audioObject.storageVersion().isBlank();
        boolean uploadStateConsistent = hasUploadedAt == hasStorageVersion;
        boolean uploadCanBeDiscovered = hasUploadedAt
                || audioObject.uploadExpiresAt().isAfter(now);
        boolean readable = uploadStateConsistent
                && uploadCanBeDiscovered
                && audioObject.consumedAt() == null
                && audioObject.deletedAt() == null
                && audioObject.retentionUntil().isAfter(now);
        if (!readable) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
    }
}
