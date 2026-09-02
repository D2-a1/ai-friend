package com.aifriend.voicecollection.application;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.voice.application.AudioObjectConsumptionService;
import com.aifriend.voice.domain.AudioPurpose;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 封闭测试语音采集用例服务。
 *
 * <p>对象存储读取和音频解码发生在数据库事务外；样本登记与音频一次性消费原子提交。
 * 本服务不自动转写、不训练，只加密保存用户在本机试听后明确确认的人工文字，
 * 也不把样本交给正式任务识别链。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class VoiceCollectionService {

    private final JdbcTemplate jdbcTemplate;
    private final AudioObjectConsumptionService audioObjectConsumptionService;
    private final ConsentGrantQueryPort consentGrantQueryPort;
    private final VoiceTrainingDatasetCleanupPort datasetCleanupPort;
    private final VoiceCollectionProperties properties;
    private final DigestService digestService;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final Clock clock;

    /**
     * 创建封闭测试语音采集服务。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param audioObjectConsumptionService 音频校验和一次性消费服务
     * @param consentGrantQueryPort 当前授权查询端口
     * @param datasetCleanupPort 训练数据集撤权清理端口
     * @param properties 采集政策和留存配置
     * @param digestService SHA-256 摘要服务
     * @param sensitiveDataProtector 人工复核文字加密组件
     * @param clock UTC 时钟
     */
    public VoiceCollectionService(
            JdbcTemplate jdbcTemplate,
            AudioObjectConsumptionService audioObjectConsumptionService,
            ConsentGrantQueryPort consentGrantQueryPort,
            VoiceTrainingDatasetCleanupPort datasetCleanupPort,
            VoiceCollectionProperties properties,
            DigestService digestService,
            SensitiveDataProtector sensitiveDataProtector,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.audioObjectConsumptionService = audioObjectConsumptionService;
        this.consentGrantQueryPort = consentGrantQueryPort;
        this.datasetCleanupPort = datasetCleanupPort;
        this.properties = properties;
        this.digestService = digestService;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.clock = clock;
    }

    /**
     * 校验独立授权并登记一个已上传的测试语音样本。
     *
     * @param ownerUserId 当前已认证 owner UUID
     * @param idempotencyKey 创建幂等键原文，仅在当前调用内存使用
     * @param command 样本最小元数据
     * @return 新样本或原幂等样本
     */
    public VoiceCollectionSampleView create(
            UUID ownerUserId,
            String idempotencyKey,
            CreateVoiceCollectionSampleCommand command) {
        validateCreate(ownerUserId, idempotencyKey, command);
        requireCurrentConsent(ownerUserId, command.consentPolicyVersion());
        String reviewedTranscript = normalizeTranscript(command.reviewedTranscript());
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = createRequestHash(command, reviewedTranscript);
        Optional<VoiceCollectionSampleView> replay = findByCreateKey(
                ownerUserId, keyHash, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        byte[] reviewedTranscriptCipher = sensitiveDataProtector.encrypt(reviewedTranscript);
        try {
            return audioObjectConsumptionService.consume(
                    ownerUserId,
                    command.audioObjectId(),
                    AudioPurpose.TEST_VOICE_COLLECTION,
                    audio -> insertInConsumptionTransaction(
                            ownerUserId, audio.audioObjectId(), keyHash, requestHash,
                            reviewedTranscriptCipher, command));
        } catch (DuplicateKeyException | BusinessException exception) {
            Optional<VoiceCollectionSampleView> concurrentReplay = findByCreateKey(
                    ownerUserId, keyHash, requestHash);
            if (concurrentReplay.isPresent()) {
                return concurrentReplay.get();
            }
            throw exception;
        } finally {
            Arrays.fill(reviewedTranscriptCipher, (byte) 0);
        }
    }

    /**
     * 查询当前用户仍有效的采集样本，不返回已请求删除的元数据。
     *
     * @param ownerUserId 当前已认证 owner UUID
     * @return 最多一百条、按创建时间倒序排列的样本
     */
    @Transactional(readOnly = true)
    public List<VoiceCollectionSampleView> listActive(UUID ownerUserId) {
        if (ownerUserId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),category,prompt_code,environment,dialect_code,"
                        + "status,review_status,training_eligible,reviewed_at,"
                        + "retention_until,version,created_at "
                        + "FROM voice_collection_sample WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND status='ACTIVE' AND retention_until>? "
                        + "ORDER BY created_at DESC LIMIT 100",
                (resultSet, rowNumber) -> mapActive(resultSet),
                ownerUserId.toString(),
                Timestamp.from(Instant.now(clock)));
    }

    /**
     * 受理当前用户对单个样本的删除请求并立即清除可识别元数据。
     *
     * @param ownerUserId 当前已认证 owner UUID
     * @param publicSampleId vs_ 前缀样本编号
     * @param idempotencyKey 删除幂等键原文
     * @param confirmed 必须为 true 的明确确认
     * @param expectedVersion 客户端最后读取的版本
     * @return 删除受理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public VoiceCollectionDeletionView delete(
            UUID ownerUserId,
            String publicSampleId,
            String idempotencyKey,
            boolean confirmed,
            long expectedVersion) {
        validateDelete(ownerUserId, idempotencyKey, confirmed, expectedVersion);
        UUID sampleId = PublicIdCodec.parseVoiceCollectionSampleId(publicSampleId);
        byte[] keyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(
                publicSampleId + "|" + confirmed + "|" + expectedVersion);
        lockOwner(ownerUserId);
        Optional<VoiceCollectionDeletionView> replay = findDeletionByKey(
                ownerUserId, keyHash, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SampleForDeletion sample = findForDeletion(ownerUserId, sampleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (sample.status() != VoiceCollectionStatus.ACTIVE
                || sample.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        Instant now = Instant.now(clock);
        datasetCleanupPort.deleteContaining(ownerUserId, sampleId);
        int changed = jdbcTemplate.update(
                "UPDATE voice_collection_sample SET category=NULL,prompt_code=NULL,"
                        + "environment=NULL,dialect_code=NULL,status='DELETE_REQUESTED',"
                        + "review_status='PENDING',reviewed_transcript_cipher=NULL,"
                        + "review_policy_version=NULL,reviewed_at=NULL,"
                        + "training_eligible=FALSE,delete_idempotency_key_hash=?,"
                        + "delete_request_hash=?,deleted_at=?,"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE' AND version=?",
                keyHash,
                requestHash,
                Timestamp.from(now),
                Timestamp.from(now),
                sampleId.toString(),
                ownerUserId.toString(),
                expectedVersion);
        if (changed != 1) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        jdbcTemplate.update(
                "UPDATE audio_object SET retention_until=LEAST(retention_until,?),"
                        + "updated_at=?,version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) AND status<>'DELETED'",
                Timestamp.from(now),
                Timestamp.from(now),
                sample.audioObjectId().toString(),
                ownerUserId.toString());
        return new VoiceCollectionDeletionView(
                publicSampleId, VoiceCollectionStatus.DELETE_REQUESTED, now);
    }

    private VoiceCollectionSampleView insertInConsumptionTransaction(
            UUID ownerUserId,
            UUID audioObjectId,
            byte[] keyHash,
            byte[] requestHash,
            byte[] reviewedTranscriptCipher,
            CreateVoiceCollectionSampleCommand command) {
        lockOwner(ownerUserId);
        Optional<VoiceCollectionSampleView> replay = findByCreateKey(
                ownerUserId, keyHash, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Instant now = Instant.now(clock);
        Instant retentionUntil = jdbcTemplate.queryForObject(
                "SELECT retention_until FROM audio_object WHERE id=UUID_TO_BIN(?)",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
                audioObjectId.toString());
        if (retentionUntil == null
                || !retentionUntil.isAfter(now)
                || retentionUntil.isAfter(now.plus(properties.rawAudioMaxAge()))) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        UUID sampleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO voice_collection_sample(id,owner_user_id,audio_object_id,"
                        + "category,prompt_code,environment,dialect_code,review_status,"
                        + "reviewed_transcript_cipher,review_policy_version,reviewed_at,"
                        + "consent_policy_version,"
                        + "training_eligible,status,create_idempotency_key_hash,create_request_hash,"
                        + "retention_until,version,created_at,updated_at) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,"
                        + "'CONFIRMED',?,?,?,?,FALSE,'ACTIVE',?,?,?,0,?,?)",
                sampleId.toString(),
                ownerUserId.toString(),
                audioObjectId.toString(),
                command.category().name(),
                command.promptCode(),
                command.environment().name(),
                command.dialectCode(),
                reviewedTranscriptCipher,
                command.reviewPolicyVersion(),
                Timestamp.from(now),
                command.consentPolicyVersion(),
                keyHash,
                requestHash,
                Timestamp.from(retentionUntil),
                Timestamp.from(now),
                Timestamp.from(now));
        return new VoiceCollectionSampleView(
                PublicIdCodec.voiceCollectionSampleId(sampleId),
                command.category(),
                command.promptCode(),
                command.environment(),
                command.dialectCode(),
                VoiceCollectionStatus.ACTIVE,
                VoiceCollectionReviewStatus.CONFIRMED,
                false,
                now,
                retentionUntil,
                0L,
                now);
    }

    private Optional<VoiceCollectionSampleView> findByCreateKey(
            UUID ownerUserId,
            byte[] keyHash,
            byte[] expectedRequestHash) {
        List<StoredCreateReplay> rows = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),category,prompt_code,environment,dialect_code,status,"
                        + "review_status,training_eligible,reviewed_at,retention_until,version,"
                        + "created_at,create_request_hash "
                        + "FROM voice_collection_sample WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND create_idempotency_key_hash=?",
                (resultSet, rowNumber) -> new StoredCreateReplay(
                        new VoiceCollectionSampleView(
                                PublicIdCodec.voiceCollectionSampleId(
                                        UUID.fromString(resultSet.getString(1))),
                                nullableCategory(resultSet.getString(2)),
                                resultSet.getString(3),
                                nullableEnvironment(resultSet.getString(4)),
                                resultSet.getString(5),
                                VoiceCollectionStatus.valueOf(resultSet.getString(6)),
                                VoiceCollectionReviewStatus.valueOf(resultSet.getString(7)),
                                resultSet.getBoolean(8),
                                nullableInstant(resultSet.getTimestamp(9)),
                                resultSet.getTimestamp(10).toInstant(),
                                resultSet.getLong(11),
                                resultSet.getTimestamp(12).toInstant()),
                        resultSet.getBytes(13)),
                ownerUserId.toString(),
                keyHash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredCreateReplay stored = rows.get(0);
        if (!MessageDigest.isEqual(stored.requestHash(), expectedRequestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (stored.view().status() != VoiceCollectionStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        if (!stored.view().retentionUntil().isAfter(Instant.now(clock))) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return Optional.of(stored.view());
    }

    private Optional<VoiceCollectionDeletionView> findDeletionByKey(
            UUID ownerUserId,
            byte[] keyHash,
            byte[] expectedRequestHash) {
        List<StoredDeletionReplay> rows = jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),status,deleted_at,delete_request_hash "
                        + "FROM voice_collection_sample WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND delete_idempotency_key_hash=?",
                (resultSet, rowNumber) -> new StoredDeletionReplay(
                        new VoiceCollectionDeletionView(
                                PublicIdCodec.voiceCollectionSampleId(
                                        UUID.fromString(resultSet.getString(1))),
                                VoiceCollectionStatus.valueOf(resultSet.getString(2)),
                                resultSet.getTimestamp(3).toInstant()),
                        resultSet.getBytes(4)),
                ownerUserId.toString(),
                keyHash);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        StoredDeletionReplay stored = rows.get(0);
        if (!MessageDigest.isEqual(stored.requestHash(), expectedRequestHash)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return Optional.of(stored.view());
    }

    private Optional<SampleForDeletion> findForDeletion(UUID ownerUserId, UUID sampleId) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(audio_object_id),status,version "
                        + "FROM voice_collection_sample WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new SampleForDeletion(
                        UUID.fromString(resultSet.getString(1)),
                        VoiceCollectionStatus.valueOf(resultSet.getString(2)),
                        resultSet.getLong(3)),
                sampleId.toString(),
                ownerUserId.toString()).stream().findFirst();
    }

    private VoiceCollectionSampleView mapActive(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new VoiceCollectionSampleView(
                PublicIdCodec.voiceCollectionSampleId(UUID.fromString(resultSet.getString(1))),
                VoiceCollectionCategory.valueOf(resultSet.getString(2)),
                resultSet.getString(3),
                VoiceCollectionEnvironment.valueOf(resultSet.getString(4)),
                resultSet.getString(5),
                VoiceCollectionStatus.valueOf(resultSet.getString(6)),
                VoiceCollectionReviewStatus.valueOf(resultSet.getString(7)),
                resultSet.getBoolean(8),
                nullableInstant(resultSet.getTimestamp(9)),
                resultSet.getTimestamp(10).toInstant(),
                resultSet.getLong(11),
                resultSet.getTimestamp(12).toInstant());
    }

    private void requireCurrentConsent(UUID ownerUserId, String policyVersion) {
        if (!properties.policyVersion().equals(policyVersion)
                || !consentGrantQueryPort.isGrantedForPolicy(
                        ownerUserId, ConsentType.TEST_VOICE_COLLECTION, policyVersion)) {
            throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
        }
    }

    private void validateCreate(
            UUID ownerUserId,
            String idempotencyKey,
            CreateVoiceCollectionSampleCommand command) {
        if (ownerUserId == null || command == null || command.category() == null
                || command.environment() == null
                || !validKey(idempotencyKey)
                || command.audioObjectId() == null
                || !command.audioObjectId().matches("au_[A-Fa-f0-9]{32}")
                || command.promptCode() == null
                || !command.promptCode().matches("[a-z0-9_]{3,64}")
                || command.dialectCode() == null
                || !command.dialectCode().matches("[A-Za-z0-9-]{2,40}")
                || command.consentPolicyVersion() == null
                || !command.reviewConfirmed()
                || command.reviewPolicyVersion() == null
                || !properties.reviewPolicyVersion().equals(command.reviewPolicyVersion())
                || command.reviewedTranscript() == null
                || normalizeTranscript(command.reviewedTranscript()).isBlank()
                || normalizeTranscript(command.reviewedTranscript()).length() > 120
                || containsForbiddenControlCharacter(
                    normalizeTranscript(command.reviewedTranscript()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateDelete(
            UUID ownerUserId,
            String idempotencyKey,
            boolean confirmed,
            long expectedVersion) {
        if (ownerUserId == null || !validKey(idempotencyKey)
                || !confirmed || expectedVersion < 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean validKey(String idempotencyKey) {
        return idempotencyKey != null
                && idempotencyKey.length() >= 16
                && idempotencyKey.length() <= 128;
    }

    private byte[] createRequestHash(
            CreateVoiceCollectionSampleCommand command,
            String reviewedTranscript) {
        return digestService.sha256(command.audioObjectId() + "|"
                + command.category().name() + "|" + command.promptCode() + "|"
                + command.environment().name() + "|" + command.dialectCode() + "|"
                + command.consentPolicyVersion() + "|" + reviewedTranscript + "|"
                + command.reviewConfirmed() + "|" + command.reviewPolicyVersion());
    }

    private void lockOwner(UUID ownerUserId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT 1 FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getInt(1),
                ownerUserId.toString());
        if (rows.size() != 1) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
    }

    private VoiceCollectionCategory nullableCategory(String value) {
        return value == null ? null : VoiceCollectionCategory.valueOf(value);
    }

    private VoiceCollectionEnvironment nullableEnvironment(String value) {
        return value == null ? null : VoiceCollectionEnvironment.valueOf(value);
    }

    private Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String normalizeTranscript(String transcript) {
        return transcript == null ? "" : transcript.strip().replaceAll("\\s+", " ");
    }

    private boolean containsForbiddenControlCharacter(String transcript) {
        return transcript.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            return type == Character.CONTROL || type == Character.FORMAT;
        });
    }

    private record StoredCreateReplay(VoiceCollectionSampleView view, byte[] requestHash) {
    }

    private record StoredDeletionReplay(VoiceCollectionDeletionView view, byte[] requestHash) {
    }

    private record SampleForDeletion(
            UUID audioObjectId,
            VoiceCollectionStatus status,
            long version) {
    }
}
