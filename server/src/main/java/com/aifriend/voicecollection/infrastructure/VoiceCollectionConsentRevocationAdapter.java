package com.aifriend.voicecollection.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.consent.application.ConsentRevocationCleanupHandler;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.voicecollection.application.VoiceTrainingDatasetCleanupPort;

/**
 * 测试语音采集授权撤回的 MySQL 逻辑清理适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class VoiceCollectionConsentRevocationAdapter
        implements ConsentRevocationCleanupHandler {

    private final JdbcTemplate jdbcTemplate;
    private final VoiceTrainingDatasetCleanupPort datasetCleanupPort;

    /**
     * 创建采集撤权清理适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     * @param datasetCleanupPort 训练数据集撤权清理端口
     */
    public VoiceCollectionConsentRevocationAdapter(
            JdbcTemplate jdbcTemplate,
            VoiceTrainingDatasetCleanupPort datasetCleanupPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.datasetCleanupPort = datasetCleanupPort;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup(UUID userId, ConsentType type, Instant revokedAt) {
        Timestamp timestamp = Timestamp.from(revokedAt);
        if (type == ConsentType.VOICE_MODEL_TRAINING) {
            datasetCleanupPort.deleteAll(userId);
            jdbcTemplate.update(
                    "UPDATE voice_collection_sample SET training_eligible=FALSE,"
                            + "updated_at=?,version=version+1 WHERE owner_user_id=UUID_TO_BIN(?) "
                            + "AND status='ACTIVE' AND training_eligible=TRUE",
                    timestamp,
                    userId.toString());
            return;
        }
        if (type != ConsentType.TEST_VOICE_COLLECTION) {
            return;
        }
        datasetCleanupPort.deleteAll(userId);
        jdbcTemplate.update(
                "UPDATE audio_object SET retention_until=LEAST(retention_until,?),"
                        + "updated_at=?,version=version+1 WHERE owner_user_id=UUID_TO_BIN(?) "
                        + "AND id IN (SELECT audio_object_id FROM voice_collection_sample "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE') "
                        + "AND status<>'DELETED'",
                timestamp,
                timestamp,
                userId.toString(),
                userId.toString());
        jdbcTemplate.update(
                "UPDATE voice_collection_sample SET category=NULL,prompt_code=NULL,"
                        + "environment=NULL,dialect_code=NULL,review_status='PENDING',"
                        + "reviewed_transcript_cipher=NULL,review_policy_version=NULL,"
                        + "reviewed_at=NULL,training_eligible=FALSE,"
                        + "status='DELETE_REQUESTED',"
                        + "deleted_at=?,updated_at=?,version=version+1 "
                        + "WHERE owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE'",
                timestamp,
                timestamp,
                userId.toString());
    }
}
