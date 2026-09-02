package com.aifriend.template.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.template.domain.SafetyCommandType;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 四类安全指令事务外预检、八音频校验和声学模板生成编排服务。
 *
 * <p>对象存储读取、解码与四组双录一致性计算都在数据库事务外完成。
 * 任一检查失败均不消费音频；未加载经验签方言包时失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class SafetyCommandEnrollmentService {

    private static final int COMMAND_COUNT = 4;
    private static final int AUDIO_COUNT = 8;
    private static final int MAX_TEMPLATE_BYTES = 262_144;

    private final SafetyCommandTemplateRepositoryPort repositoryPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final DigestService digestService;
    private final AudioObjectConsumptionService audioObjectConsumptionService;
    private final AcousticTemplatePort acousticTemplatePort;
    private final SafetyCommandEnrollmentTransactionService transactionService;
    private final SafetyCommandTemplateMapper templateMapper;

    /**
     * 创建安全指令注册编排服务。
     *
     * @param repositoryPort 安全指令持久化端口
     * @param consentGrantQueryPort 语音模板政策版本授权端口
     * @param digestService 幂等与请求摘要服务
     * @param audioObjectConsumptionService 音频事务外完整校验服务
     * @param acousticTemplatePort 本地声学模板端口
     * @param transactionService 四模板与八音频原子提交服务
     * @param templateMapper 安全指令元数据映射器
     */
    public SafetyCommandEnrollmentService(
            SafetyCommandTemplateRepositoryPort repositoryPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            DigestService digestService,
            AudioObjectConsumptionService audioObjectConsumptionService,
            AcousticTemplatePort acousticTemplatePort,
            SafetyCommandEnrollmentTransactionService transactionService,
            SafetyCommandTemplateMapper templateMapper) {
        this.repositoryPort = repositoryPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.digestService = digestService;
        this.audioObjectConsumptionService = audioObjectConsumptionService;
        this.acousticTemplatePort = acousticTemplatePort;
        this.transactionService = transactionService;
        this.templateMapper = templateMapper;
    }

    /**
     * 使用八段互不相同的已上传录音整批注册四类安全指令。
     *
     * @param ownerUserId 已验证 JWT 派生的 owner UUID
     * @param idempotencyKey 整批注册幂等键，不得记录日志
     * @param command 四类安全指令双录命令
     * @return 四类注册成功或安全重放结果
     * @throws BusinessException 授权、音频、声学一致性或版本无效时抛出
     */
    public SafetyCommandEnrollmentResult enroll(
            UUID ownerUserId,
            String idempotencyKey,
            EnrollSafetyCommandsCommand command) {
        EnrollSafetyCommandsCommand normalized = normalize(command);
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(normalized.fingerprintInput());
        SafetyCommandEnrollment replay = repositoryPort.findEnrollment(
                ownerUserId, idempotencyHash).orElse(null);
        if (replay != null) {
            return validateReplay(ownerUserId, replay, requestHash);
        }
        validateConsent(ownerUserId, normalized.consentPolicyVersion());

        List<String> publicAudioIds = normalized.commands().stream()
                .flatMap(item -> List.of(
                        item.firstAudioObjectId(), item.secondAudioObjectId()).stream())
                .toList();
        List<ValidatedAudioObject> audioObjects = audioObjectConsumptionService.validateAll(
                ownerUserId, publicAudioIds, AudioPurpose.SAFETY_COMMAND_ENROLLMENT);
        List<AcousticEnrollmentCandidate> candidates = enrollCandidates(audioObjects);
        return transactionService.replace(
                ownerUserId, idempotencyHash, requestHash,
                normalized, audioObjects, candidates);
    }

    private EnrollSafetyCommandsCommand normalize(EnrollSafetyCommandsCommand command) {
        if (command == null || command.commands() == null
                || command.commands().size() != COMMAND_COUNT
                || !StringUtils.hasText(command.consentPolicyVersion())
                || command.consentPolicyVersion().strip().length() > 40) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<SafetyCommandEnrollmentItem> normalized = new ArrayList<>(COMMAND_COUNT);
        Set<SafetyCommandType> types = EnumSet.noneOf(SafetyCommandType.class);
        Set<String> audioIds = new HashSet<>();
        for (SafetyCommandEnrollmentItem item : command.commands()) {
            if (item == null || item.type() == null
                    || !StringUtils.hasText(item.firstAudioObjectId())
                    || !StringUtils.hasText(item.secondAudioObjectId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            String firstAudioId = item.firstAudioObjectId().strip();
            String secondAudioId = item.secondAudioObjectId().strip();
            if (!types.add(item.type())
                    || !audioIds.add(firstAudioId) || !audioIds.add(secondAudioId)) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            normalized.add(new SafetyCommandEnrollmentItem(
                    item.type(), firstAudioId, secondAudioId));
        }
        if (!types.equals(EnumSet.allOf(SafetyCommandType.class))
                || audioIds.size() != AUDIO_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        normalized.sort(Comparator.comparing(SafetyCommandEnrollmentItem::type));
        return new EnrollSafetyCommandsCommand(
                normalized, command.consentPolicyVersion().strip());
    }

    private SafetyCommandEnrollmentResult validateReplay(
            UUID ownerUserId,
            SafetyCommandEnrollment replay,
            byte[] requestHash) {
        if (!digestService.constantTimeEquals(replay.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        List<com.aifriend.template.domain.SafetyCommandTemplate> templates =
                repositoryPort.findActiveByOwner(ownerUserId);
        if (templates.size() != COMMAND_COUNT
                || templates.stream().anyMatch(
                        template -> !template.enrollmentId().equals(replay.id()))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return new SafetyCommandEnrollmentResult(
                true, templates.stream().map(templateMapper::toSummary).toList());
    }

    private void validateConsent(UUID ownerUserId, String policyVersion) {
        if (!consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE, policyVersion)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private List<AcousticEnrollmentCandidate> enrollCandidates(
            List<ValidatedAudioObject> audioObjects) {
        List<AcousticEnrollmentCandidate> candidates = new ArrayList<>(COMMAND_COUNT);
        for (int commandIndex = 0; commandIndex < COMMAND_COUNT; commandIndex++) {
            AcousticEnrollmentCandidate candidate = acousticTemplatePort.enroll(
                    toSample(audioObjects.get(commandIndex * 2)),
                    toSample(audioObjects.get(commandIndex * 2 + 1)));
            validateCandidate(candidate);
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private AcousticEnrollmentSample toSample(ValidatedAudioObject audioObject) {
        return new AcousticEnrollmentSample(
                audioObject.mediaType(), audioObject.audioContent(),
                audioObject.actualDurationMs());
    }

    private void validateCandidate(AcousticEnrollmentCandidate candidate) {
        boolean invalid = candidate == null
                || candidate.template().length < 1
                || candidate.template().length > MAX_TEMPLATE_BYTES
                || !within(candidate.dialectCode(), 2, 40)
                || !within(candidate.dialectPackageVersion(), 1, 60)
                || !within(candidate.modelVersion(), 1, 60)
                || !within(candidate.thresholdVersion(), 1, 60);
        if (invalid) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    private boolean within(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum;
    }
}
