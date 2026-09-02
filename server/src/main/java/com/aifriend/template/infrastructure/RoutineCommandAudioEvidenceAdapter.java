package com.aifriend.template.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.template.application.RoutineCommandAudioEvidencePort;
import com.aifriend.template.application.RoutineCommandAudioSnapshot;
import com.aifriend.template.application.RoutineCommandLearningJob;
import com.aifriend.voice.application.AudioObjectContentValidationService;
import com.aifriend.voice.application.AudioObjectRepositoryPort;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.StoredAudioObject;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 已消费 TASK 音频的事务外受限读取与完整性复验适配器。
 *
 * <p>本适配器不改变音频状态；对象必须仍在创建时固化的留存期内，且所有
 * 元数据、存储版本、摘要、魔数和真实时长必须重新验证。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandAudioEvidenceAdapter
        implements RoutineCommandAudioEvidencePort {

    private final AudioObjectRepositoryPort repositoryPort;
    private final AudioObjectStoragePort storagePort;
    private final AudioObjectContentValidationService validationService;
    private final Clock clock;

    /**
     * 创建日常指令音频证据适配器。
     *
     * @param repositoryPort 音频元数据端口
     * @param storagePort 私有对象存储端口
     * @param validationService 音频内容完整校验服务
     * @param clock UTC 时钟
     */
    public RoutineCommandAudioEvidenceAdapter(
            AudioObjectRepositoryPort repositoryPort,
            AudioObjectStoragePort storagePort,
            AudioObjectContentValidationService validationService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.storagePort = storagePort;
        this.validationService = validationService;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public RoutineCommandAudioSnapshot read(RoutineCommandLearningJob job) {
        Instant now = Instant.now(clock);
        AudioObject audio = repositoryPort.findByIdAndOwner(
                        job.audioObjectId(), job.ownerUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUDIO_INVALID));
        boolean readable = audio.purpose() == AudioPurpose.TASK
                && audio.status() == AudioObjectStatus.CONSUMED
                && audio.uploadedAt() != null
                && audio.consumedAt() != null
                && audio.deletedAt() == null
                && audio.storageVersion() != null
                && audio.retentionUntil().equals(job.sourceRetentionUntil())
                && audio.retentionUntil().isAfter(now);
        if (!readable) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        try (StoredAudioObject stored = storagePort.readExact(
                        audio.objectKey(), audio.storageVersion(),
                        audio.expectedSizeBytes());
                ValidatedAudioObject validated = validationService.validate(
                        audio, stored)) {
            byte[] content = validated.audioContent();
            try {
                return new RoutineCommandAudioSnapshot(
                        validated.mediaType(), validated.actualDurationMs(), content);
            } finally {
                Arrays.fill(content, (byte) 0);
            }
        }
    }
}
