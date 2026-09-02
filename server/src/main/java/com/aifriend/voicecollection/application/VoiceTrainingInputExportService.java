package com.aifriend.voicecollection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectContentValidationService;
import com.aifriend.voice.application.AudioObjectRepositoryPort;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.StoredAudioObject;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 个人武冈话训练输入本机导出服务。
 *
 * <p>数据库事务只用于 V30 数据集复验；对象存储读取、音频解码、解密和文件写入
 * 均在事务外逐条执行。最终发布前再次完整复验数据集，期间发生撤权、删除、
 * 过期或版本变化时关闭暂存会话并失败清理。</p>
 *
 * @author codex
 * @since 1.0.0
 */
@Service
public class VoiceTrainingInputExportService {

    private final VoiceTrainingDatasetService datasetService;
    private final VoiceTrainingDatasetStorePort datasetStorePort;
    private final VoiceTrainingDatasetSplitPlanner splitPlanner;
    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final AudioObjectStoragePort audioObjectStoragePort;
    private final AudioObjectContentValidationService contentValidationService;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final VoiceTrainingInputSinkPort sinkPort;
    private final VoiceTrainingInputExportProperties properties;
    private final Clock clock;

    /**
     * 创建本机训练输入导出服务。
     *
     * @param datasetService V30 数据集冻结与实时复验服务
     * @param datasetStorePort 数据集成员读取端口
     * @param splitPlanner 训练/验证确定性划分器
     * @param audioObjectRepositoryPort owner 范围音频元数据端口
     * @param audioObjectStoragePort 私有对象存储端口
     * @param contentValidationService 音频内容完整校验服务
     * @param sensitiveDataProtector 人工复核文字解密组件
     * @param digestService SHA-256 与常量时间比较服务
     * @param sinkPort 本机训练输入流式写入端口
     * @param properties 默认关闭的导出配置
     * @param clock UTC 时钟
     */
    public VoiceTrainingInputExportService(
            VoiceTrainingDatasetService datasetService,
            VoiceTrainingDatasetStorePort datasetStorePort,
            VoiceTrainingDatasetSplitPlanner splitPlanner,
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioObjectStoragePort audioObjectStoragePort,
            AudioObjectContentValidationService contentValidationService,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            VoiceTrainingInputSinkPort sinkPort,
            VoiceTrainingInputExportProperties properties,
            Clock clock) {
        this.datasetService = datasetService;
        this.datasetStorePort = datasetStorePort;
        this.splitPlanner = splitPlanner;
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.audioObjectStoragePort = audioObjectStoragePort;
        this.contentValidationService = contentValidationService;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.sinkPort = sinkPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 把一个已冻结数据集导出到受控本机目录。
     *
     * <p>本方法没有公开 Controller。调用方只能在本机离线进程中显式启用配置后使用。</p>
     *
     * @param ownerUserId 数据集所属 owner UUID
     * @param datasetVersion 已冻结数据集版本
     * @return 原子发布后的本机导出回执
     * @throws BusinessException 当能力未启用或任一实时门禁变化时抛出
     */
    public VoiceTrainingInputExportReceipt export(
            UUID ownerUserId,
            String datasetVersion) {
        if (!properties.enabled()) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        VoiceTrainingDatasetSelection initial = datasetService.resolveForTraining(
                ownerUserId, datasetVersion);
        List<VoiceTrainingInputExportMember> members = datasetStorePort.findExportMembers(
                ownerUserId,
                initial.datasetId(),
                VoiceTrainingDatasetService.MAXIMUM_SAMPLE_COUNT + 1);
        try {
            requireMemberListMatches(initial, members);
            List<VoiceTrainingInputSplit> splitPlan = splitPlanner.plan(members);
            if (splitPlan == null || splitPlan.size() != members.size()) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            try (VoiceTrainingInputSink sink = sinkPort.open(
                    initial.datasetId(),
                    initial.datasetVersion(),
                    initial.manifestSha256())) {
                for (int index = 0; index < members.size(); index++) {
                    writeMember(
                            ownerUserId,
                            initial,
                            members.get(index),
                            splitPlan.get(index),
                            index,
                            sink);
                }
                VoiceTrainingDatasetSelection current = datasetService.resolveForTraining(
                        ownerUserId, datasetVersion);
                requireSameDataset(initial, current);
                return sink.commit(initial.sampleIds().size());
            }
        } finally {
            clearMemberSensitiveData(members);
        }
    }

    private void writeMember(
            UUID ownerUserId,
            VoiceTrainingDatasetSelection dataset,
            VoiceTrainingInputExportMember member,
            VoiceTrainingInputSplit split,
            int expectedOrder,
            VoiceTrainingInputSink sink) {
        requireMemberMatches(dataset, member, expectedOrder);
        byte[] reviewedTranscriptCipher = member.reviewedTranscriptCipher();
        byte[] transcriptUtf8 = null;
        byte[] audioContent = null;
        try {
            if (!digestService.constantTimeEquals(
                    member.reviewedTranscriptSha256(),
                    digestService.sha256(reviewedTranscriptCipher))) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            transcriptUtf8 = sensitiveDataProtector.decryptBytes(reviewedTranscriptCipher);
            AudioObject audioObject = requireAudioObject(ownerUserId, member);
            try (StoredAudioObject stored = audioObjectStoragePort.readExact(
                    audioObject.objectKey(),
                    audioObject.storageVersion(),
                    audioObject.expectedSizeBytes());
                    ValidatedAudioObject validated = contentValidationService.validate(
                            audioObject, stored)) {
                if (!"audio/wav".equals(validated.mediaType())) {
                    throw new BusinessException(ErrorCode.AUDIO_INVALID);
                }
                audioContent = validated.audioContent();
                sink.write(
                        new VoiceTrainingInputItem(
                                expectedOrder,
                                member.category(),
                                member.promptCode(),
                                member.environment(),
                                member.dialectCode(),
                                split,
                                validated.actualDurationMs(),
                                member.audioSha256(),
                                digestService.sha256(transcriptUtf8)),
                        audioContent,
                        transcriptUtf8);
            }
        } finally {
            Arrays.fill(reviewedTranscriptCipher, (byte) 0);
            if (transcriptUtf8 != null) {
                Arrays.fill(transcriptUtf8, (byte) 0);
            }
            if (audioContent != null) {
                Arrays.fill(audioContent, (byte) 0);
            }
            member.clearSensitiveData();
        }
    }

    private AudioObject requireAudioObject(
            UUID ownerUserId,
            VoiceTrainingInputExportMember member) {
        AudioObject audioObject = audioObjectRepositoryPort
                .findByIdAndOwner(member.audioObjectId(), ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        Instant now = Instant.now(clock);
        boolean matches = audioObject.purpose() == AudioPurpose.TEST_VOICE_COLLECTION
                && audioObject.status() == AudioObjectStatus.CONSUMED
                && audioObject.retentionUntil() != null
                && audioObject.retentionUntil().isAfter(now)
                && audioObject.storageVersion() != null
                && !audioObject.storageVersion().isBlank()
                && audioObject.objectKey() != null
                && !audioObject.objectKey().isBlank()
                && audioObject.expectedSizeBytes() >= 1L
                && audioObject.expectedSizeBytes()
                    <= VoiceTrainingInputExportProperties.MAXIMUM_AUDIO_BYTES
                && digestService.constantTimeEquals(
                    member.audioSha256(), audioObject.expectedSha256());
        if (!matches) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return audioObject;
    }

    private void requireMemberListMatches(
            VoiceTrainingDatasetSelection dataset,
            List<VoiceTrainingInputExportMember> members) {
        if (members == null || members.size() != dataset.sampleIds().size()) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        for (int index = 0; index < members.size(); index++) {
            requireMemberMatches(dataset, members.get(index), index);
        }
    }

    private void requireMemberMatches(
            VoiceTrainingDatasetSelection dataset,
            VoiceTrainingInputExportMember member,
            int expectedOrder) {
        boolean matches = member != null
                && member.memberOrder() == expectedOrder
                && dataset.sampleIds().get(expectedOrder).equals(member.sampleId())
                && member.audioObjectId() != null
                && member.frozenSampleVersion() == member.currentSampleVersion()
                && member.audioSha256() != null
                && member.audioSha256().length == 32
                && member.reviewedTranscriptSha256() != null
                && member.reviewedTranscriptSha256().length == 32
                && member.reviewedTranscriptCipher() != null
                && member.reviewedTranscriptCipher().length > 28
                && member.category() != null
                && member.promptCode() != null
                && member.environment() != null
                && member.dialectCode() != null;
        if (!matches) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void requireSameDataset(
            VoiceTrainingDatasetSelection initial,
            VoiceTrainingDatasetSelection current) {
        boolean same = initial.datasetId().equals(current.datasetId())
                && initial.datasetVersion().equals(current.datasetVersion())
                && initial.sampleIds().equals(current.sampleIds())
                && digestService.constantTimeEquals(
                    initial.manifestSha256(), current.manifestSha256());
        if (!same) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void clearMemberSensitiveData(
            List<VoiceTrainingInputExportMember> members) {
        if (members != null) {
            members.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(VoiceTrainingInputExportMember::clearSensitiveData);
        }
    }
}
