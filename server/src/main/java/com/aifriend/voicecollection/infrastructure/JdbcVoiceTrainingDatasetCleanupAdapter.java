package com.aifriend.voicecollection.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.voicecollection.application.VoiceTrainingDatasetCleanupPort;

/**
 * 训练数据集撤权清理 JDBC 适配器。
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class JdbcVoiceTrainingDatasetCleanupAdapter
        implements VoiceTrainingDatasetCleanupPort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建训练数据集撤权清理适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcVoiceTrainingDatasetCleanupAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAll(UUID ownerUserId) {
        jdbcTemplate.update(
                "DELETE FROM voice_training_dataset WHERE owner_user_id=UUID_TO_BIN(?)",
                ownerUserId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public void deleteContaining(UUID ownerUserId, UUID sampleId) {
        jdbcTemplate.update(
                "DELETE dataset_row FROM voice_training_dataset dataset_row "
                        + "JOIN voice_training_dataset_member dataset_member "
                        + "ON dataset_member.dataset_id=dataset_row.id "
                        + "WHERE dataset_row.owner_user_id=UUID_TO_BIN(?) "
                        + "AND dataset_member.sample_id=UUID_TO_BIN(?)",
                ownerUserId.toString(),
                sampleId.toString());
    }
}
