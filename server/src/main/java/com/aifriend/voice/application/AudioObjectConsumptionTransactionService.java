package com.aifriend.voice.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;

/**
 * 音频对象锁定复验与一次性消费事务服务。
 *
 * <p>对象存储读取和解码已在事务外完成。本服务通过 owner 范围写锁复验
 * 状态、元数据版本和存储版本，使业务回调与 CONSUMED 状态同事务提交。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AudioObjectConsumptionTransactionService {

    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final Clock clock;

    /**
     * 创建音频一次性消费事务服务。
     *
     * @param audioObjectRepositoryPort 音频元数据持久化端口
     * @param clock UTC 时钟
     */
    public AudioObjectConsumptionTransactionService(
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            Clock clock) {
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.clock = clock;
    }

    /**
     * 加锁复验已校验快照，执行业务回调并原子标记消费。
     *
     * <p>业务回调抛出运行时异常时整个事务回滚，音频仍保持 ISSUED；
     * 同一对象的并发或重复消费在写锁后因状态或版本变化被拒绝。
     *
     * @param validatedAudioObject 事务外完成内容校验的音频快照
     * @param consumer 只执行有界数据库逻辑的消费回调
     * @param <T> 业务消费结果类型
     * @return 业务消费结果
     * @throws BusinessException 当 owner、状态、用途、元数据版本、存储版本或留存期发生变化时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> T consume(
            ValidatedAudioObject validatedAudioObject,
            AudioObjectConsumer<T> consumer) {
        return consumeAll(
                List.of(validatedAudioObject),
                audioObjects -> consumer.consume(audioObjects.get(0)));
    }

    /**
     * 按 UUID 稳定顺序锁定并复验一组音频，使业务回调与全部 CONSUMED 状态原子提交。
     *
     * <p>调用方可以先在同一外层事务中锁定 owner 和业务聚合；本方法使用默认
     * {@code REQUIRED} 传播加入该事务。回调失败时全部音频仍保持 ISSUED。
     *
     * @param validatedAudioObjects 事务外完成内容校验的互不相同音频快照
     * @param consumer 只执行有界数据库逻辑的批量消费回调
     * @param <T> 业务消费结果类型
     * @return 业务消费结果
     * @throws BusinessException 快照为空、重复或任一对象状态和版本变化时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> T consumeAll(
            List<ValidatedAudioObject> validatedAudioObjects,
            AudioObjectBatchConsumer<T> consumer) {
        if (validatedAudioObjects == null || validatedAudioObjects.isEmpty()
                || validatedAudioObjects.size() > 10 || consumer == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (new HashSet<>(validatedAudioObjects.stream()
                .map(ValidatedAudioObject::audioObjectId).toList()).size()
                != validatedAudioObjects.size()) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        if (validatedAudioObjects.stream()
                .map(ValidatedAudioObject::ownerUserId).distinct().count() != 1) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        Instant now = Instant.now(clock);
        List<ValidatedAudioObject> lockOrder = validatedAudioObjects.stream()
                .sorted(Comparator.comparing(value -> value.audioObjectId().toString()))
                .toList();
        List<AudioObject> lockedAudioObjects = new ArrayList<>(lockOrder.size());
        for (ValidatedAudioObject validated : lockOrder) {
            AudioObject current = audioObjectRepositoryPort.findByIdAndOwnerForUpdate(
                    validated.audioObjectId(), validated.ownerUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUDIO_INVALID));
            lockedAudioObjects.add(prepareForConsumption(
                    current, validated, now));
        }
        T consumedResult = consumer.consume(List.copyOf(validatedAudioObjects));
        lockedAudioObjects.forEach(
                audioObject -> audioObjectRepositoryPort.save(audioObject.markConsumed(now)));
        return consumedResult;
    }

    private AudioObject prepareForConsumption(
            AudioObject current,
            ValidatedAudioObject validated,
            Instant now) {
        boolean unchanged = current.status() == AudioObjectStatus.ISSUED
                && current.consumedAt() == null
                && current.deletedAt() == null
                && current.version() == validated.metadataVersion()
                && current.ownerUserId().equals(validated.ownerUserId())
                && current.purpose() == validated.purpose()
                && validated.storageVersion() != null
                && !validated.storageVersion().isBlank();
        if (!unchanged) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (!current.retentionUntil().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        boolean hasUploadedAt = current.uploadedAt() != null;
        boolean hasStorageVersion = current.storageVersion() != null
                && !current.storageVersion().isBlank();
        if (hasUploadedAt != hasStorageVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (hasUploadedAt) {
            if (!validated.storageVersion().equals(current.storageVersion())) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return current;
        }
        if (!current.uploadExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return current.markUploaded(validated.storageVersion(), now);
    }
}
