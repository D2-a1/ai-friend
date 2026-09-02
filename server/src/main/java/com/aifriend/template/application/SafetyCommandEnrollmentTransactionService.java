package com.aifriend.template.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;
import com.aifriend.voice.application.AudioObjectConsumptionTransactionService;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 四类安全指令模板整批替换与八音频状态的原子提交服务。
 *
 * <p>固定锁顺序为 owner 安全指令命名空间、幂等批次、八个音频 UUID。
 * 声学模板生成与互区分已在事务外完成，本事务只做有界数据库不变量复验。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class SafetyCommandEnrollmentTransactionService {

    private static final int COMMAND_COUNT = 4;

    private final SafetyCommandTemplateRepositoryPort repositoryPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final AudioObjectConsumptionTransactionService audioTransactionService;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final SafetyCommandTemplateMapper templateMapper;
    private final SafetyCommandOutboxPort outboxPort;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建安全指令整批原子提交服务。
     *
     * @param repositoryPort 安全指令持久化端口
     * @param consentGrantQueryPort 政策版本授权端口
     * @param audioTransactionService 八音频批量消费事务服务
     * @param sensitiveDataProtector 声学模板 AES-GCM 加密器
     * @param digestService 模板完整性摘要服务
     * @param templateMapper 安全指令元数据映射器
     * @param outboxPort 本地投影与缓存刷新事件端口
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public SafetyCommandEnrollmentTransactionService(
            SafetyCommandTemplateRepositoryPort repositoryPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            AudioObjectConsumptionTransactionService audioTransactionService,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            SafetyCommandTemplateMapper templateMapper,
            SafetyCommandOutboxPort outboxPort,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.audioTransactionService = audioTransactionService;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.templateMapper = templateMapper;
        this.outboxPort = outboxPort;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 在短事务内整批替换四类模板并同时消费八段注册录音。
     *
     * @param ownerUserId 已认证 owner UUID
     * @param idempotencyHash 幂等键摘要
     * @param requestHash 请求语义摘要
     * @param command 已规范化四类指令命令
     * @param validatedAudioObjects 事务外完成完整校验的八段音频
     * @param candidates 按指令类型排序的四个互可区分模板候选
     * @return 四类注册成功或锁内安全重放结果
     * @throws BusinessException 授权、幂等、模板数量或音频快照变化时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public SafetyCommandEnrollmentResult replace(
            UUID ownerUserId,
            byte[] idempotencyHash,
            byte[] requestHash,
            EnrollSafetyCommandsCommand command,
            List<ValidatedAudioObject> validatedAudioObjects,
            List<AcousticEnrollmentCandidate> candidates) {
        Instant now = Instant.now(clock);
        long namespaceVersion = repositoryPort.lockNamespace(ownerUserId, now);
        SafetyCommandEnrollment replay = repositoryPort.findEnrollmentForUpdate(
                ownerUserId, idempotencyHash).orElse(null);
        if (replay != null) {
            return validateReplay(ownerUserId, replay, requestHash);
        }
        if (candidates == null || candidates.size() != COMMAND_COUNT
                || command.commands().size() != COMMAND_COUNT) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        if (!consentGrantQueryPort.isGrantedForPolicy(
                ownerUserId, ConsentType.VOICE_TEMPLATE,
                command.consentPolicyVersion())) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }

        return audioTransactionService.consumeAll(validatedAudioObjects, ignored -> {
            int replacedCount = repositoryPort.replaceActiveAndFlush(ownerUserId, now);
            if (replacedCount != 0 && replacedCount != COMMAND_COUNT) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }

            UUID enrollmentId = UUID.randomUUID();
            SafetyCommandEnrollment enrollment = new SafetyCommandEnrollment(
                    enrollmentId, ownerUserId, idempotencyHash, requestHash,
                    command.consentPolicyVersion(), now);
            repositoryPort.saveEnrollment(enrollment);

            List<SafetyCommandTemplate> savedTemplates =
                    java.util.stream.IntStream.range(0, COMMAND_COUNT)
                            .mapToObj(index -> createTemplate(
                                    enrollmentId, ownerUserId,
                                    command.commands().get(index),
                                    candidates.get(index), now))
                            .map(repositoryPort::saveTemplate)
                            .toList();
            repositoryPort.incrementNamespace(ownerUserId, namespaceVersion, now);
            outboxPort.appendReplaced(enrollmentId, ownerUserId, now);
            auditEventPort.append(
                    ownerUserId, "SAFETY_COMMAND_ENROLL", "SUCCESS", null, now);
            return new SafetyCommandEnrollmentResult(
                    true, savedTemplates.stream().map(templateMapper::toSummary).toList());
        });
    }

    private SafetyCommandEnrollmentResult validateReplay(
            UUID ownerUserId,
            SafetyCommandEnrollment replay,
            byte[] requestHash) {
        if (!digestService.constantTimeEquals(replay.requestHash(), requestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        List<SafetyCommandTemplate> templates = repositoryPort.findActiveByOwner(ownerUserId);
        if (templates.size() != COMMAND_COUNT
                || templates.stream().anyMatch(
                        template -> !template.enrollmentId().equals(replay.id()))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return new SafetyCommandEnrollmentResult(
                true, templates.stream().map(templateMapper::toSummary).toList());
    }

    private SafetyCommandTemplate createTemplate(
            UUID enrollmentId,
            UUID ownerUserId,
            SafetyCommandEnrollmentItem item,
            AcousticEnrollmentCandidate candidate,
            Instant now) {
        return new SafetyCommandTemplate(
                UUID.randomUUID(), enrollmentId, ownerUserId, item.type(),
                candidate.dialectCode(), candidate.dialectPackageVersion(),
                candidate.modelVersion(), candidate.thresholdVersion(),
                sensitiveDataProtector.encryptBytes(candidate.template()),
                digestService.sha256(candidate.template()),
                SafetyCommandTemplateStatus.ACTIVE, 0, now, now, null);
    }
}
