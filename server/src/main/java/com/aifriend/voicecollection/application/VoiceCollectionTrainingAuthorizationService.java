package com.aifriend.voicecollection.application;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 单条测试语音样本训练授权用例服务。
 *
 * <p>训练授权不创建训练任务、标签或模型产物。授予资格必须同时命中独立全局训练同意；
 * 撤回始终允许，并立即把样本排除在未来训练选择范围之外。</p>
 *
 * @author codex
 * @since 1.0.0
 */
@Service
public class VoiceCollectionTrainingAuthorizationService {

    private final VoiceCollectionTrainingAuthorizationStorePort storePort;
    private final VoiceTrainingDatasetCleanupPort datasetCleanupPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final VoiceCollectionProperties properties;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建样本训练授权服务。
     *
     * @param storePort 样本训练授权短事务存储端口
     * @param datasetCleanupPort 训练数据集撤权清理端口
     * @param consentGrantQueryPort 当前授权查询端口
     * @param properties 采集与训练政策版本配置
     * @param digestService SHA-256 摘要服务
     * @param clock UTC 时钟
     */
    public VoiceCollectionTrainingAuthorizationService(
            VoiceCollectionTrainingAuthorizationStorePort storePort,
            VoiceTrainingDatasetCleanupPort datasetCleanupPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            VoiceCollectionProperties properties,
            DigestService digestService,
            Clock clock) {
        this.storePort = storePort;
        this.datasetCleanupPort = datasetCleanupPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.properties = properties;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 授予或撤回单条测试样本的训练资格。
     *
     * @param ownerUserId 当前已认证 owner UUID
     * @param publicSampleId vs_ 前缀样本编号
     * @param idempotencyKey 幂等键原文，仅在当前调用内存使用
     * @param command 训练授权命令
     * @return 当前训练资格、样本版本和决定时间
     * @throws BusinessException 当授权、owner、状态、留存、版本或幂等摘要不满足时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public VoiceCollectionTrainingAuthorizationView update(
            UUID ownerUserId,
            String publicSampleId,
            String idempotencyKey,
            VoiceCollectionTrainingAuthorizationCommand command) {
        validate(ownerUserId, idempotencyKey, command);
        UUID sampleId = PublicIdCodec.parseVoiceCollectionSampleId(publicSampleId);
        byte[] idempotencyKeyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = requestHash(publicSampleId, command);
        storePort.lockOwner(ownerUserId);
        Optional<VoiceCollectionTrainingAuthorizationRecord> replay =
                storePort.findByIdempotencyKey(ownerUserId, idempotencyKeyHash);
        if (replay.isPresent()) {
            return replay(ownerUserId, replay.get(), requestHash);
        }
        if (command.decision() == ConsentDecision.GRANTED) {
            requireTrainingConsent(ownerUserId, command.policyVersion());
        }
        VoiceCollectionTrainingSample sample = storePort.findForUpdate(ownerUserId, sampleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Instant now = Instant.now(clock);
        boolean targetEligibility = command.decision() == ConsentDecision.GRANTED;
        ensureMutable(sample, command.expectedVersion(), now, targetEligibility);
        long resultingVersion = sample.version();
        if (sample.trainingEligible() != targetEligibility) {
            boolean updated = storePort.updateEligibility(
                    ownerUserId, sampleId, sample.version(), targetEligibility, now);
            if (!updated) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            resultingVersion++;
        }
        if (!targetEligibility) {
            datasetCleanupPort.deleteContaining(ownerUserId, sampleId);
        }
        VoiceCollectionTrainingAuthorizationRecord record =
                new VoiceCollectionTrainingAuthorizationRecord(
                        UUID.randomUUID(), sampleId, command.decision(), command.policyVersion(),
                        idempotencyKeyHash, requestHash, targetEligibility,
                        resultingVersion, now);
        storePort.append(ownerUserId, record);
        return toView(record);
    }

    private VoiceCollectionTrainingAuthorizationView replay(
            UUID ownerUserId,
            VoiceCollectionTrainingAuthorizationRecord record,
            byte[] expectedRequestHash) {
        if (!MessageDigest.isEqual(record.requestHash(), expectedRequestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        VoiceCollectionTrainingSample sample = storePort
                .findForUpdate(ownerUserId, record.sampleId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (sample.status() != VoiceCollectionStatus.ACTIVE
                || !sample.retentionUntil().isAfter(Instant.now(clock))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return new VoiceCollectionTrainingAuthorizationView(
                PublicIdCodec.voiceCollectionSampleId(sample.sampleId()),
                sample.trainingEligible(), sample.version(), record.decidedAt());
    }

    private void requireTrainingConsent(UUID ownerUserId, String policyVersion) {
        if (!properties.trainingPolicyVersion().equals(policyVersion)
                || !consentGrantQueryPort.isGrantedForPolicy(
                        ownerUserId, ConsentType.VOICE_MODEL_TRAINING, policyVersion)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private void ensureMutable(
            VoiceCollectionTrainingSample sample,
            long expectedVersion,
            Instant now,
            boolean granting) {
        if (sample.status() != VoiceCollectionStatus.ACTIVE
                || !sample.retentionUntil().isAfter(now)
                || sample.version() != expectedVersion
                || (granting
                    && sample.reviewStatus() != VoiceCollectionReviewStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void validate(
            UUID ownerUserId,
            String idempotencyKey,
            VoiceCollectionTrainingAuthorizationCommand command) {
        if (ownerUserId == null || command == null || command.decision() == null
                || !command.confirmed() || command.expectedVersion() < 0L
                || idempotencyKey == null || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128
                || command.policyVersion() == null
                || command.policyVersion().isBlank()
                || command.policyVersion().length() > 60
                || (command.decision() == ConsentDecision.GRANTED
                    && !properties.trainingPolicyVersion().equals(command.policyVersion()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private byte[] requestHash(
            String publicSampleId,
            VoiceCollectionTrainingAuthorizationCommand command) {
        return digestService.sha256(publicSampleId + "|" + command.decision().name() + "|"
                + command.confirmed() + "|" + command.policyVersion() + "|"
                + command.expectedVersion());
    }

    private VoiceCollectionTrainingAuthorizationView toView(
            VoiceCollectionTrainingAuthorizationRecord record) {
        return new VoiceCollectionTrainingAuthorizationView(
                PublicIdCodec.voiceCollectionSampleId(record.sampleId()),
                record.trainingEligible(), record.sampleVersion(), record.decidedAt());
    }
}
