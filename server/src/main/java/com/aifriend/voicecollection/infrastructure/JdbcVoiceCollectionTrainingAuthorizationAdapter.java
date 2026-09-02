package com.aifriend.voicecollection.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingAuthorizationRecord;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingAuthorizationStorePort;
import com.aifriend.voicecollection.application.VoiceCollectionTrainingSample;
import com.aifriend.voicecollection.domain.VoiceCollectionReviewStatus;
import com.aifriend.voicecollection.domain.VoiceCollectionStatus;

/**
 * 样本训练授权 MySQL 短事务适配器。
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class JdbcVoiceCollectionTrainingAuthorizationAdapter
        implements VoiceCollectionTrainingAuthorizationStorePort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建样本训练授权 JDBC 适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcVoiceCollectionTrainingAuthorizationAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void lockOwner(UUID ownerUserId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT 1 FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getInt(1),
                ownerUserId.toString());
        if (rows.size() != 1) {
            throw new IllegalStateException("当前用户不存在");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<VoiceCollectionTrainingAuthorizationRecord> findByIdempotencyKey(
            UUID ownerUserId,
            byte[] idempotencyKeyHash) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),BIN_TO_UUID(sample_id),decision,policy_version,"
                        + "idempotency_key_hash,request_hash,resulting_training_eligible,"
                        + "resulting_sample_version,decided_at "
                        + "FROM voice_collection_training_authorization "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND idempotency_key_hash=?",
                (resultSet, rowNumber) -> new VoiceCollectionTrainingAuthorizationRecord(
                        UUID.fromString(resultSet.getString(1)),
                        UUID.fromString(resultSet.getString(2)),
                        ConsentDecision.valueOf(resultSet.getString(3)),
                        resultSet.getString(4),
                        resultSet.getBytes(5),
                        resultSet.getBytes(6),
                        resultSet.getBoolean(7),
                        resultSet.getLong(8),
                        resultSet.getTimestamp(9).toInstant()),
                ownerUserId.toString(),
                idempotencyKeyHash).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<VoiceCollectionTrainingSample> findForUpdate(
            UUID ownerUserId,
            UUID sampleId) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),status,training_eligible,review_status,"
                        + "retention_until,version "
                        + "FROM voice_collection_sample WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) FOR UPDATE",
                (resultSet, rowNumber) -> new VoiceCollectionTrainingSample(
                        UUID.fromString(resultSet.getString(1)),
                        VoiceCollectionStatus.valueOf(resultSet.getString(2)),
                        resultSet.getBoolean(3),
                        VoiceCollectionReviewStatus.valueOf(resultSet.getString(4)),
                        resultSet.getTimestamp(5).toInstant(),
                        resultSet.getLong(6)),
                sampleId.toString(),
                ownerUserId.toString()).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean updateEligibility(
            UUID ownerUserId,
            UUID sampleId,
            long expectedVersion,
            boolean trainingEligible,
            Instant updatedAt) {
        return jdbcTemplate.update(
                "UPDATE voice_collection_sample SET training_eligible=?,updated_at=?,"
                        + "version=version+1 WHERE id=UUID_TO_BIN(?) "
                        + "AND owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE' AND version=?",
                trainingEligible,
                Timestamp.from(updatedAt),
                sampleId.toString(),
                ownerUserId.toString(),
                expectedVersion) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public void append(
            UUID ownerUserId,
            VoiceCollectionTrainingAuthorizationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO voice_collection_training_authorization("
                        + "id,owner_user_id,sample_id,decision,policy_version,"
                        + "idempotency_key_hash,request_hash,resulting_training_eligible,"
                        + "resulting_sample_version,decided_at) VALUES("
                        + "UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?)",
                record.id().toString(),
                ownerUserId.toString(),
                record.sampleId().toString(),
                record.decision().name(),
                record.policyVersion(),
                record.idempotencyKeyHash(),
                record.requestHash(),
                record.trainingEligible(),
                record.sampleVersion(),
                Timestamp.from(record.decidedAt()));
    }

}
