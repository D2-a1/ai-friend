package com.aifriend.voicecollection.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.voicecollection.application.VoiceTrainingDatasetCandidate;
import com.aifriend.voicecollection.application.VoiceTrainingDatasetMember;
import com.aifriend.voicecollection.application.VoiceTrainingDatasetRecord;
import com.aifriend.voicecollection.application.VoiceTrainingDatasetStorePort;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportMember;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

/**
 * 个人训练数据集 MySQL 短事务适配器。
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class JdbcVoiceTrainingDatasetAdapter implements VoiceTrainingDatasetStorePort {

    private static final String CANDIDATE_COLUMNS =
            "BIN_TO_UUID(sample_row.id),BIN_TO_UUID(sample_row.audio_object_id),"
                    + "sample_row.version,sample_row.category,sample_row.prompt_code,"
                    + "sample_row.environment,sample_row.dialect_code,audio_row.expected_sha256,"
                    + "sample_row.reviewed_transcript_cipher,sample_row.reviewed_at,"
                    + "sample_row.retention_until ";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建个人训练数据集 JDBC 适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcVoiceTrainingDatasetAdapter(JdbcTemplate jdbcTemplate) {
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
    public Optional<VoiceTrainingDatasetRecord> findByVersion(
            UUID ownerUserId,
            String datasetVersion) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(id),BIN_TO_UUID(owner_user_id),dataset_version,"
                        + "dataset_policy_version,training_policy_version,"
                        + "review_policy_version,sample_count,manifest_sha256,created_at "
                        + "FROM voice_training_dataset WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND dataset_version=? FOR UPDATE",
                (resultSet, rowNumber) -> new VoiceTrainingDatasetRecord(
                        UUID.fromString(resultSet.getString(1)),
                        UUID.fromString(resultSet.getString(2)),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getString(6),
                        resultSet.getInt(7),
                        resultSet.getBytes(8),
                        resultSet.getTimestamp(9).toInstant()),
                ownerUserId.toString(),
                datasetVersion).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public List<VoiceTrainingDatasetCandidate> findEligibleForUpdate(
            UUID ownerUserId,
            String reviewPolicyVersion,
            Instant now,
            int limit) {
        return jdbcTemplate.query(
                "SELECT " + CANDIDATE_COLUMNS
                        + "FROM voice_collection_sample sample_row "
                        + "JOIN audio_object audio_row ON audio_row.id=sample_row.audio_object_id "
                        + "AND audio_row.owner_user_id=sample_row.owner_user_id "
                        + "WHERE sample_row.owner_user_id=UUID_TO_BIN(?) "
                        + "AND sample_row.status='ACTIVE' "
                        + "AND sample_row.training_eligible=TRUE "
                        + "AND sample_row.review_status='CONFIRMED' "
                        + "AND sample_row.review_policy_version=? "
                        + "AND sample_row.retention_until>? "
                        + "AND sample_row.category IS NOT NULL "
                        + "AND sample_row.prompt_code IS NOT NULL "
                        + "AND sample_row.environment IS NOT NULL "
                        + "AND sample_row.dialect_code IS NOT NULL "
                        + "AND audio_row.purpose='TEST_VOICE_COLLECTION' "
                        + "AND audio_row.status='CONSUMED' AND audio_row.retention_until>? "
                        + "ORDER BY sample_row.id LIMIT ? FOR UPDATE",
                (resultSet, rowNumber) -> mapCandidate(resultSet),
                ownerUserId.toString(),
                reviewPolicyVersion,
                Timestamp.from(now),
                Timestamp.from(now),
                limit);
    }

    /** {@inheritDoc} */
    @Override
    public List<VoiceTrainingDatasetCandidate> findEligibleDatasetMembersForUpdate(
            UUID ownerUserId,
            UUID datasetId,
            String reviewPolicyVersion,
            Instant now,
            int limit) {
        return jdbcTemplate.query(
                "SELECT " + CANDIDATE_COLUMNS
                        + "FROM voice_training_dataset_member dataset_member "
                        + "JOIN voice_training_dataset dataset_row "
                        + "ON dataset_row.id=dataset_member.dataset_id "
                        + "JOIN voice_collection_sample sample_row "
                        + "ON sample_row.id=dataset_member.sample_id "
                        + "AND sample_row.owner_user_id=dataset_row.owner_user_id "
                        + "JOIN audio_object audio_row ON audio_row.id=sample_row.audio_object_id "
                        + "AND audio_row.owner_user_id=sample_row.owner_user_id "
                        + "WHERE dataset_row.id=UUID_TO_BIN(?) "
                        + "AND dataset_row.owner_user_id=UUID_TO_BIN(?) "
                        + "AND sample_row.status='ACTIVE' "
                        + "AND sample_row.training_eligible=TRUE "
                        + "AND sample_row.review_status='CONFIRMED' "
                        + "AND sample_row.review_policy_version=? "
                        + "AND sample_row.retention_until>? "
                        + "AND sample_row.category IS NOT NULL "
                        + "AND sample_row.prompt_code IS NOT NULL "
                        + "AND sample_row.environment IS NOT NULL "
                        + "AND sample_row.dialect_code IS NOT NULL "
                        + "AND audio_row.purpose='TEST_VOICE_COLLECTION' "
                        + "AND audio_row.status='CONSUMED' AND audio_row.retention_until>? "
                        + "ORDER BY dataset_member.member_order LIMIT ? FOR UPDATE",
                (resultSet, rowNumber) -> mapCandidate(resultSet),
                datasetId.toString(),
                ownerUserId.toString(),
                reviewPolicyVersion,
                Timestamp.from(now),
                Timestamp.from(now),
                limit);
    }

    /** {@inheritDoc} */
    @Override
    public List<VoiceTrainingInputExportMember> findExportMembers(
            UUID ownerUserId,
            UUID datasetId,
            int limit) {
        return jdbcTemplate.query(
                "SELECT BIN_TO_UUID(dataset_member.sample_id),"
                        + "BIN_TO_UUID(sample_row.audio_object_id),"
                        + "dataset_member.member_order,dataset_member.sample_version,"
                        + "sample_row.version,dataset_member.audio_sha256,"
                        + "dataset_member.reviewed_transcript_sha256,"
                        + "sample_row.reviewed_transcript_cipher,dataset_member.category,"
                        + "dataset_member.prompt_code,dataset_member.environment,"
                        + "dataset_member.dialect_code "
                        + "FROM voice_training_dataset_member dataset_member "
                        + "JOIN voice_training_dataset dataset_row "
                        + "ON dataset_row.id=dataset_member.dataset_id "
                        + "JOIN voice_collection_sample sample_row "
                        + "ON sample_row.id=dataset_member.sample_id "
                        + "AND sample_row.owner_user_id=dataset_row.owner_user_id "
                        + "WHERE dataset_row.id=UUID_TO_BIN(?) "
                        + "AND dataset_row.owner_user_id=UUID_TO_BIN(?) "
                        + "ORDER BY dataset_member.member_order LIMIT ?",
                (resultSet, rowNumber) -> new VoiceTrainingInputExportMember(
                        UUID.fromString(resultSet.getString(1)),
                        UUID.fromString(resultSet.getString(2)),
                        resultSet.getInt(3),
                        resultSet.getLong(4),
                        resultSet.getLong(5),
                        resultSet.getBytes(6),
                        resultSet.getBytes(7),
                        resultSet.getBytes(8),
                        VoiceCollectionCategory.valueOf(resultSet.getString(9)),
                        resultSet.getString(10),
                        VoiceCollectionEnvironment.valueOf(resultSet.getString(11)),
                        resultSet.getString(12)),
                datasetId.toString(),
                ownerUserId.toString(),
                limit);
    }

    /** {@inheritDoc} */
    @Override
    public void insert(
            VoiceTrainingDatasetRecord dataset,
            List<VoiceTrainingDatasetMember> members) {
        jdbcTemplate.update(
                "INSERT INTO voice_training_dataset(id,owner_user_id,dataset_version,"
                        + "dataset_policy_version,training_policy_version,review_policy_version,"
                        + "sample_count,manifest_sha256,created_at) VALUES("
                        + "UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?)",
                dataset.datasetId().toString(),
                dataset.ownerUserId().toString(),
                dataset.datasetVersion(),
                dataset.datasetPolicyVersion(),
                dataset.trainingPolicyVersion(),
                dataset.reviewPolicyVersion(),
                dataset.sampleCount(),
                dataset.manifestSha256(),
                Timestamp.from(dataset.createdAt()));
        for (VoiceTrainingDatasetMember member : members) {
            jdbcTemplate.update(
                    "INSERT INTO voice_training_dataset_member(dataset_id,sample_id,member_order,"
                            + "sample_version,audio_sha256,reviewed_transcript_sha256,"
                            + "category,prompt_code,environment,dialect_code) VALUES("
                            + "UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,?,?,?,?,?)",
                    dataset.datasetId().toString(),
                    member.sampleId().toString(),
                    member.ordinal(),
                    member.sampleVersion(),
                    member.audioSha256(),
                    member.reviewedTranscriptSha256(),
                    member.category().name(),
                    member.promptCode(),
                    member.environment().name(),
                    member.dialectCode());
        }
    }

    private VoiceTrainingDatasetCandidate mapCandidate(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new VoiceTrainingDatasetCandidate(
                UUID.fromString(resultSet.getString(1)),
                UUID.fromString(resultSet.getString(2)),
                resultSet.getLong(3),
                VoiceCollectionCategory.valueOf(resultSet.getString(4)),
                resultSet.getString(5),
                VoiceCollectionEnvironment.valueOf(resultSet.getString(6)),
                resultSet.getString(7),
                resultSet.getBytes(8),
                resultSet.getBytes(9),
                resultSet.getTimestamp(10).toInstant(),
                resultSet.getTimestamp(11).toInstant());
    }
}
