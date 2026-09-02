package com.aifriend.voice.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.config.AiFriendProperties;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.voice.domain.AudioObject;
import com.aifriend.voice.domain.AudioObjectStatus;
import com.aifriend.voice.domain.AudioPurpose;
import com.aifriend.voicecollection.application.VoiceCollectionProperties;

/**
 * 受限音频上传凭证创建服务。
 *
 * <p>owner 只来自已验证 JWT；同一 owner 的创建请求先锁账号行，保证幂等轮换串行。
 * 数据库只保存上传秘密摘要和加密对象键，正式存储目标未配置时在落库前失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AudioUploadTicketService {

    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of(
            "audio/mp4", "audio/aac", "audio/wav", "audio/ogg");

    private final AudioObjectRepositoryPort audioObjectRepositoryPort;
    private final AudioUploadTokenPort audioUploadTokenPort;
    private final AudioUploadTargetPort audioUploadTargetPort;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final AudioStorageProperties audioStorageProperties;
    private final AiFriendProperties aiFriendProperties;
    private final VoiceCollectionProperties voiceCollectionProperties;
    private final Clock clock;

    /**
     * 创建受限音频上传凭证服务。
     *
     * @param audioObjectRepositoryPort 音频对象持久化端口
     * @param audioUploadTokenPort 上传秘密生成端口
     * @param audioUploadTargetPort 对象存储上传目标端口
     * @param consentGrantQueryPort 当前分项授权查询端口
     * @param digestService SHA-256 与常量时间比较服务
     * @param auditEventPort 去标识化审计端口
     * @param audioStorageProperties 音频上传配置
     * @param aiFriendProperties 全局留存配置
     * @param voiceCollectionProperties 测试语音采集留存配置
     * @param clock UTC 时钟
     */
    public AudioUploadTicketService(
            AudioObjectRepositoryPort audioObjectRepositoryPort,
            AudioUploadTokenPort audioUploadTokenPort,
            AudioUploadTargetPort audioUploadTargetPort,
            ConsentGrantQueryPort consentGrantQueryPort,
            DigestService digestService,
            AuditEventPort auditEventPort,
            AudioStorageProperties audioStorageProperties,
            AiFriendProperties aiFriendProperties,
            VoiceCollectionProperties voiceCollectionProperties,
            Clock clock) {
        this.audioObjectRepositoryPort = audioObjectRepositoryPort;
        this.audioUploadTokenPort = audioUploadTokenPort;
        this.audioUploadTargetPort = audioUploadTargetPort;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.audioStorageProperties = audioStorageProperties;
        this.aiFriendProperties = aiFriendProperties;
        this.voiceCollectionProperties = voiceCollectionProperties;
        this.clock = clock;
    }

    /**
     * 创建或幂等恢复当前用户的音频上传凭证。
     *
     * <p>同键同正文复用 audioObjectId 并轮换上传秘密，使丢失响应可安全恢复；
     * 同键不同正文、已上传或已过期对象统一返回冲突，不能覆盖原音频。
     *
     * @param ownerUserId 当前已认证用户 UUID
     * @param idempotencyKey 创建幂等键，不得记录
     * @param command 音频用途和预期元数据
     * @return 仅供当前调用内存使用的上传目标
     * @throws BusinessException 当授权、元数据、幂等语义或对象状态不满足时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatedAudioUploadTicket create(
            UUID ownerUserId,
            String idempotencyKey,
            CreateAudioUploadTicketCommand command) {
        validateCommand(ownerUserId, idempotencyKey, command);
        String normalizedMediaType = normalizeMediaType(command.mediaType());
        String normalizedSha256 = normalizeSha256(command.sha256Hex());
        validateConsent(ownerUserId, command.purpose());
        Instant now = Instant.now(clock);
        byte[] idempotencyDigest = digestService.sha256(idempotencyKey);
        byte[] requestDigest = requestDigest(command, normalizedMediaType, normalizedSha256);

        audioObjectRepositoryPort.lockOwner(ownerUserId);
        return audioObjectRepositoryPort
                .findByIdempotencyKeyForUpdate(ownerUserId, idempotencyDigest)
                .map(existing -> replay(existing, requestDigest, now))
                .orElseGet(() -> createNew(
                        ownerUserId,
                        command,
                        normalizedMediaType,
                        normalizedSha256,
                        idempotencyDigest,
                        requestDigest,
                        now));
    }

    private CreatedAudioUploadTicket replay(
            AudioObject existing,
            byte[] requestDigest,
            Instant now) {
        if (!digestService.constantTimeEquals(existing.requestDigest(), requestDigest)
                || existing.status() != AudioObjectStatus.ISSUED
                || existing.uploadedAt() != null
                || !existing.uploadExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        String rotatedToken = audioUploadTokenPort.issue();
        AudioUploadTarget target = audioUploadTargetPort.createTarget(existing, rotatedToken);
        AudioObject rotated = existing.rotateUploadToken(
                digestService.sha256(rotatedToken), now);
        audioObjectRepositoryPort.save(rotated);
        return toCreated(rotated, target);
    }

    private CreatedAudioUploadTicket createNew(
            UUID ownerUserId,
            CreateAudioUploadTicketCommand command,
            String normalizedMediaType,
            String normalizedSha256,
            byte[] idempotencyDigest,
            byte[] requestDigest,
            Instant now) {
        UUID audioObjectId = UUID.randomUUID();
        String uploadToken = audioUploadTokenPort.issue();
        Instant uploadExpiresAt = now.plus(audioStorageProperties.uploadTicketTtl());
        AudioObject audioObject = new AudioObject(
                audioObjectId,
                ownerUserId,
                command.purpose(),
                normalizedMediaType,
                command.sizeBytes(),
                command.durationMs(),
                HexFormat.of().parseHex(normalizedSha256),
                "temporary/" + audioObjectId.toString().replace("-", ""),
                digestService.sha256(uploadToken),
                idempotencyDigest,
                requestDigest,
                AudioObjectStatus.ISSUED,
                uploadExpiresAt,
                now.plus(retentionFor(command.purpose())),
                null,
                null,
                null,
                null,
                0L,
                now,
                now);
        AudioUploadTarget target = audioUploadTargetPort.createTarget(audioObject, uploadToken);
        AudioObject persisted = audioObjectRepositoryPort.save(audioObject);
        auditEventPort.append(
                ownerUserId,
                "AUDIO_UPLOAD_TICKET_CREATE",
                "SUCCESS",
                command.purpose().name(),
                now);
        return toCreated(persisted, target);
    }

    private CreatedAudioUploadTicket toCreated(
            AudioObject audioObject,
            AudioUploadTarget target) {
        return new CreatedAudioUploadTicket(
                PublicIdCodec.audioObjectId(audioObject.id()),
                target,
                audioObject.uploadExpiresAt(),
                audioObject.objectKey());
    }

    private void validateConsent(UUID ownerUserId, AudioPurpose purpose) {
        if (purpose == AudioPurpose.TEST_VOICE_COLLECTION) {
            if (!consentGrantQueryPort.isGrantedForPolicy(
                    ownerUserId,
                    ConsentType.TEST_VOICE_COLLECTION,
                    voiceCollectionProperties.policyVersion())) {
                throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
            }
            return;
        }
        ConsentType requiredConsent = purpose == AudioPurpose.TASK
                ? ConsentType.TASK_AUDIO : ConsentType.VOICE_TEMPLATE;
        if (!consentGrantQueryPort.isGranted(ownerUserId, requiredConsent)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private java.time.Duration retentionFor(AudioPurpose purpose) {
        return purpose == AudioPurpose.TEST_VOICE_COLLECTION
                ? voiceCollectionProperties.rawAudioMaxAge()
                : aiFriendProperties.retention().temporaryAudioMaxAge();
    }

    private void validateCommand(
            UUID ownerUserId,
            String idempotencyKey,
            CreateAudioUploadTicketCommand command) {
        if (ownerUserId == null
                || command == null
                || command.purpose() == null
                || idempotencyKey == null
                || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128
                || command.sizeBytes() < 1
                || command.sizeBytes() > 20_971_520
                || command.durationMs() < 200
                || command.durationMs() > 60_000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private byte[] requestDigest(
            CreateAudioUploadTicketCommand command,
            String normalizedMediaType,
            String normalizedSha256) {
        String material = command.purpose().name() + "|"
                + normalizedMediaType + "|"
                + command.sizeBytes() + "|"
                + command.durationMs() + "|"
                + normalizedSha256;
        return digestService.sha256(material);
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = mediaType == null
                ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MEDIA_TYPES.contains(normalized)) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return normalized;
    }

    private String normalizeSha256(String sha256Hex) {
        String normalized = sha256Hex == null
                ? "" : sha256Hex.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return normalized;
    }
}
