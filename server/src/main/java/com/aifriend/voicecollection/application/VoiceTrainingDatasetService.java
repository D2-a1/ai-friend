package com.aifriend.voicecollection.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 个人武冈话训练数据集冻结与实时复验服务。
 *
 * <p>本服务只冻结样本成员、版本和不可逆摘要，不读取音频对象、不解密人工复核文字，
 * 也不创建训练任务。每次向后续训练链交付成员前都会重新检查授权、状态、版本和留存期。</p>
 *
 * @author codex
 * @since 1.0.0
 */
@Service
public class VoiceTrainingDatasetService {

    /** 当前个人训练数据集选择规则版本。 */
    public static final String DATASET_POLICY_VERSION = "voice-training-dataset-v1";

    /** 个人短期自用阶段单个数据集最大样本数。 */
    public static final int MAXIMUM_SAMPLE_COUNT = 500;

    private static final String DATASET_VERSION_PATTERN = "[a-z0-9][a-z0-9._-]{2,59}";

    private final VoiceTrainingDatasetStorePort storePort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final VoiceCollectionProperties properties;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建个人训练数据集服务。
     *
     * @param storePort 数据集短事务存储端口
     * @param consentGrantQueryPort 当前授权查询端口
     * @param properties 采集、训练和人工复核政策配置
     * @param digestService SHA-256 摘要服务
     * @param clock UTC 时钟
     */
    public VoiceTrainingDatasetService(
            VoiceTrainingDatasetStorePort storePort,
            ConsentGrantQueryPort consentGrantQueryPort,
            VoiceCollectionProperties properties,
            DigestService digestService,
            Clock clock) {
        this.storePort = storePort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.properties = properties;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 把当前全部合格样本冻结为一个不可变数据集版本。
     *
     * <p>同 owner、同版本重复调用只在既有清单仍通过实时复验时返回原结果；
     * 样本已撤权、删除、过期或版本变化时必须使用新版本重新冻结。</p>
     *
     * @param ownerUserId 当前 owner UUID
     * @param datasetVersion 调用方指定的数据集版本
     * @return 已冻结并通过实时复验的选择结果
     * @throws BusinessException 当授权、版本或样本事实不满足时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public VoiceTrainingDatasetSelection freeze(
            UUID ownerUserId, String datasetVersion) {
        validateInput(ownerUserId, datasetVersion);
        storePort.lockOwner(ownerUserId);
        requireCurrentConsents(ownerUserId);
        Instant now = Instant.now(clock);
        return storePort.findByVersion(ownerUserId, datasetVersion)
                .map(existing -> resolveExisting(ownerUserId, existing, now))
                .orElseGet(() -> createNew(ownerUserId, datasetVersion, now));
    }

    /**
     * 在未来训练读取前重新验证既有数据集。
     *
     * @param ownerUserId 当前 owner UUID
     * @param datasetVersion 数据集版本
     * @return 仍可使用的有序样本选择
     * @throws BusinessException 当数据集不存在或任一实时门禁变化时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public VoiceTrainingDatasetSelection resolveForTraining(
            UUID ownerUserId, String datasetVersion) {
        validateInput(ownerUserId, datasetVersion);
        storePort.lockOwner(ownerUserId);
        requireCurrentConsents(ownerUserId);
        VoiceTrainingDatasetRecord existing = storePort
                .findByVersion(ownerUserId, datasetVersion)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_CONFLICT));
        return resolveExisting(ownerUserId, existing, Instant.now(clock));
    }

    private VoiceTrainingDatasetSelection createNew(
            UUID ownerUserId,
            String datasetVersion,
            Instant now) {
        List<VoiceTrainingDatasetCandidate> candidates = storePort.findEligibleForUpdate(
                ownerUserId,
                properties.reviewPolicyVersion(),
                now,
                MAXIMUM_SAMPLE_COUNT + 1);
        try {
            requireBoundedCandidates(candidates);
            candidates.sort(Comparator.comparing(candidate -> candidate.sampleId().toString()));
            UUID datasetId = UUID.randomUUID();
            DatasetMaterial material = materialize(
                    datasetId, datasetVersion, candidates);
            VoiceTrainingDatasetRecord dataset = new VoiceTrainingDatasetRecord(
                    datasetId,
                    ownerUserId,
                    datasetVersion,
                    DATASET_POLICY_VERSION,
                    properties.trainingPolicyVersion(),
                    properties.reviewPolicyVersion(),
                    material.members().size(),
                    material.manifestSha256(),
                    now);
            storePort.insert(dataset, material.members());
            return toSelection(dataset, candidates);
        } finally {
            clearSensitiveData(candidates);
        }
    }

    private VoiceTrainingDatasetSelection resolveExisting(
            UUID ownerUserId,
            VoiceTrainingDatasetRecord dataset,
            Instant now) {
        requireCurrentPolicy(ownerUserId, dataset);
        List<VoiceTrainingDatasetCandidate> candidates =
                storePort.findEligibleDatasetMembersForUpdate(
                        ownerUserId,
                        dataset.datasetId(),
                        properties.reviewPolicyVersion(),
                        now,
                        MAXIMUM_SAMPLE_COUNT + 1);
        try {
            if (candidates.size() != dataset.sampleCount()) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            candidates.sort(Comparator.comparing(candidate -> candidate.sampleId().toString()));
            DatasetMaterial material = materialize(
                    dataset.datasetId(), dataset.datasetVersion(), candidates);
            if (!digestService.constantTimeEquals(
                    dataset.manifestSha256(), material.manifestSha256())) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return toSelection(dataset, candidates);
        } finally {
            clearSensitiveData(candidates);
        }
    }

    private DatasetMaterial materialize(
            UUID datasetId,
            String datasetVersion,
            List<VoiceTrainingDatasetCandidate> candidates) {
        List<VoiceTrainingDatasetMember> members = new ArrayList<>(candidates.size());
        StringBuilder canonical = new StringBuilder(256 + candidates.size() * 256);
        canonical.append(DATASET_POLICY_VERSION).append('|')
                .append(properties.trainingPolicyVersion()).append('|')
                .append(properties.reviewPolicyVersion()).append('|')
                .append(datasetVersion).append('|')
                .append(candidates.size()).append('\n');
        for (int index = 0; index < candidates.size(); index++) {
            VoiceTrainingDatasetCandidate candidate = candidates.get(index);
            byte[] transcriptSha256 = digestService.sha256(
                    candidate.reviewedTranscriptCipher());
            VoiceTrainingDatasetMember member = new VoiceTrainingDatasetMember(
                    candidate.sampleId(),
                    index,
                    candidate.sampleVersion(),
                    candidate.audioSha256(),
                    transcriptSha256,
                    candidate.category(),
                    candidate.promptCode(),
                    candidate.environment(),
                    candidate.dialectCode());
            members.add(member);
            appendCanonical(canonical, datasetId, candidate, transcriptSha256);
        }
        return new DatasetMaterial(
                List.copyOf(members), digestService.sha256(canonical.toString()));
    }

    private void appendCanonical(
            StringBuilder canonical,
            UUID datasetId,
            VoiceTrainingDatasetCandidate candidate,
            byte[] transcriptSha256) {
        canonical.append(datasetId).append('|')
                .append(candidate.sampleId()).append('|')
                .append(candidate.audioObjectId()).append('|')
                .append(candidate.sampleVersion()).append('|')
                .append(candidate.category().name()).append('|')
                .append(candidate.promptCode()).append('|')
                .append(candidate.environment().name()).append('|')
                .append(candidate.dialectCode()).append('|')
                .append(HexFormat.of().formatHex(candidate.audioSha256())).append('|')
                .append(HexFormat.of().formatHex(transcriptSha256)).append('|')
                .append(candidate.reviewedAt().toEpochMilli()).append('|')
                .append(candidate.retentionUntil().toEpochMilli()).append('\n');
    }

    private VoiceTrainingDatasetSelection toSelection(
            VoiceTrainingDatasetRecord dataset,
            List<VoiceTrainingDatasetCandidate> candidates) {
        return new VoiceTrainingDatasetSelection(
                dataset.datasetId(),
                dataset.datasetVersion(),
                candidates.stream().map(VoiceTrainingDatasetCandidate::sampleId).toList(),
                dataset.manifestSha256(),
                dataset.createdAt());
    }

    private void requireCurrentConsents(UUID ownerUserId) {
        if (!consentGrantQueryPort.isGrantedForPolicy(
                    ownerUserId,
                    ConsentType.TEST_VOICE_COLLECTION,
                    properties.policyVersion())
                || !consentGrantQueryPort.isGrantedForPolicy(
                    ownerUserId,
                    ConsentType.VOICE_MODEL_TRAINING,
                    properties.trainingPolicyVersion())) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private void requireCurrentPolicy(
            UUID ownerUserId,
            VoiceTrainingDatasetRecord dataset) {
        if (!ownerUserId.equals(dataset.ownerUserId())
                || !DATASET_POLICY_VERSION.equals(dataset.datasetPolicyVersion())
                || !properties.trainingPolicyVersion().equals(
                    dataset.trainingPolicyVersion())
                || !properties.reviewPolicyVersion().equals(dataset.reviewPolicyVersion())
                || dataset.sampleCount() < 1
                || dataset.sampleCount() > MAXIMUM_SAMPLE_COUNT
                || dataset.manifestSha256() == null
                || dataset.manifestSha256().length != 32) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void requireBoundedCandidates(List<VoiceTrainingDatasetCandidate> candidates) {
        if (candidates == null
                || candidates.isEmpty()
                || candidates.size() > MAXIMUM_SAMPLE_COUNT) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }

    private void validateInput(UUID ownerUserId, String datasetVersion) {
        if (ownerUserId == null
                || datasetVersion == null
                || !datasetVersion.matches(DATASET_VERSION_PATTERN)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void clearSensitiveData(List<VoiceTrainingDatasetCandidate> candidates) {
        if (candidates != null) {
            candidates.forEach(VoiceTrainingDatasetCandidate::clearSensitiveData);
        }
    }

    private record DatasetMaterial(
            List<VoiceTrainingDatasetMember> members,
            byte[] manifestSha256) {
    }
}
