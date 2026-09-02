package com.aifriend.contact.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 联系人称呼事务外预检、双音频校验和声学模板生成编排服务。
 *
 * <p>对象存储读取、解码和两遍一致性计算都在数据库事务外完成；正式声学引擎未加载时
 * 失败关闭，且不会消费音频或写入称呼。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactAliasEnrollmentService {

    private static final int MAX_TEMPLATE_BYTES = 262_144;

    private final ContactBindingRepositoryPort bindingRepositoryPort;
    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final DigestService digestService;
    private final AudioObjectConsumptionService audioObjectConsumptionService;
    private final AcousticTemplatePort acousticTemplatePort;
    private final ContactAliasTransactionService transactionService;
    private final ContactAliasMapper aliasMapper;

    /**
     * 创建联系人称呼注册编排服务。
     *
     * @param bindingRepositoryPort 联系人绑定端口
     * @param aliasRepositoryPort 联系人称呼端口
     * @param consentGrantQueryPort 当前语音模板授权查询端口
     * @param digestService 幂等与请求摘要服务
     * @param audioObjectConsumptionService 音频事务外校验服务
     * @param acousticTemplatePort 本地声学模板端口
     * @param transactionService 称呼原子提交服务
     * @param aliasMapper 称呼展示映射器
     */
    public ContactAliasEnrollmentService(
            ContactBindingRepositoryPort bindingRepositoryPort,
            ContactAliasRepositoryPort aliasRepositoryPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            DigestService digestService,
            AudioObjectConsumptionService audioObjectConsumptionService,
            AcousticTemplatePort acousticTemplatePort,
            ContactAliasTransactionService transactionService,
            ContactAliasMapper aliasMapper) {
        this.bindingRepositoryPort = bindingRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.digestService = digestService;
        this.audioObjectConsumptionService = audioObjectConsumptionService;
        this.acousticTemplatePort = acousticTemplatePort;
        this.transactionService = transactionService;
        this.aliasMapper = aliasMapper;
    }

    /**
     * 使用两遍互不相同的已上传录音创建联系人方言称呼。
     *
     * @param ownerUserId 已验证 JWT 派生的 owner UUID
     * @param publicContactId ct_ 前缀联系人编号
     * @param idempotencyKey 创建幂等键，不得记录日志
     * @param command 称呼注册命令
     * @return 创建成功或安全重放得到的称呼
     * @throws BusinessException 绑定、授权、音频、声学一致性或模板版本无效时抛出
     */
    public ContactAliasSummary create(
            UUID ownerUserId,
            String publicContactId,
            String idempotencyKey,
            CreateContactAliasCommand command) {
        CreateContactAliasCommand normalized = normalize(command);
        UUID contactId = PublicIdCodec.parseContactId(publicContactId);
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(normalized.fingerprintInput());

        ContactAlias replay = aliasRepositoryPort.findByOwnerAndCreateKey(
                ownerUserId, idempotencyHash).orElse(null);
        if (replay != null) {
            return validateReplay(replay, requestHash);
        }
        precheckEligibility(ownerUserId, contactId, normalized.expectedContactVersion());

        List<ValidatedAudioObject> audioObjects = audioObjectConsumptionService.validateAll(
                ownerUserId,
                List.of(normalized.firstAudioObjectId(), normalized.secondAudioObjectId()),
                AudioPurpose.ALIAS_ENROLLMENT);
        AcousticEnrollmentCandidate candidate = acousticTemplatePort.enroll(
                toSample(audioObjects.get(0)), toSample(audioObjects.get(1)));
        validateCandidate(candidate);
        return transactionService.create(
                ownerUserId, contactId, idempotencyHash, requestHash,
                normalized, audioObjects, candidate);
    }

    private CreateContactAliasCommand normalize(CreateContactAliasCommand command) {
        if (command == null || !command.confirmed()
                || !StringUtils.hasText(command.displayText())
                || command.displayText().strip().length() > 40
                || !StringUtils.hasText(command.firstAudioObjectId())
                || !StringUtils.hasText(command.secondAudioObjectId())
                || command.expectedContactVersion() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String phoneticHint = StringUtils.hasText(command.phoneticHint())
                ? command.phoneticHint().strip() : null;
        if (phoneticHint != null && phoneticHint.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new CreateContactAliasCommand(
                command.displayText().strip(), phoneticHint,
                command.firstAudioObjectId(), command.secondAudioObjectId(),
                command.expectedContactVersion(), true);
    }

    private ContactAliasSummary validateReplay(ContactAlias replay, byte[] requestHash) {
        if (!digestService.constantTimeEquals(replay.createRequestHash(), requestHash)
                || replay.status() != ContactAliasStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return aliasMapper.toSummary(replay);
    }

    private void precheckEligibility(UUID ownerUserId, UUID contactId, long expectedVersion) {
        ContactBinding binding = bindingRepositoryPort.findByOwnerAndId(ownerUserId, contactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (binding.status() != ContactStatus.ACTIVE_NO_ALIAS
                && binding.status() != ContactStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.LOCAL_VERIFICATION_REQUIRED);
        }
        if (binding.version() + 1 != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (!consentGrantQueryPort.isGranted(ownerUserId, ConsentType.VOICE_TEMPLATE)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private AcousticEnrollmentSample toSample(ValidatedAudioObject audioObject) {
        return new AcousticEnrollmentSample(
                audioObject.mediaType(),
                audioObject.audioContent(),
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
