package com.aifriend.template.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandRuntimeStorePort;
import com.aifriend.template.application.RoutineCommandTemplateRecord;

/**
 * MySQL 日常指令运行时 owner 范围只读投影适配器。
 *
 * <p>只返回 ACTIVE 模板并以 31 行探测数据库硬上限异常；不记录模板密文或摘要。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandRuntimeJdbcAdapter
        implements RoutineCommandRuntimeStorePort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建运行时模板 JDBC 投影适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public RoutineCommandRuntimeJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> findNamespaceVersionForUpdate(UUID ownerUserId) {
        return namespaceVersion(ownerUserId, true);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> findNamespaceVersion(UUID ownerUserId) {
        return namespaceVersion(ownerUserId, false);
    }

    /** {@inheritDoc} */
    @Override
    public List<RoutineCommandTemplateRecord> findActiveByOwner(UUID ownerUserId) {
        return jdbcTemplate.query("""
                SELECT BIN_TO_UUID(id) id,intent,dialect_code,
                    dialect_package_version,template_model_version,threshold_version,
                    template_cipher,template_digest,usage_count,last_confirmed_at,
                    version,updated_at
                FROM routine_command_template
                WHERE owner_user_id=UUID_TO_BIN(?) AND status='ACTIVE'
                ORDER BY last_confirmed_at,id
                LIMIT 31
                """,
                (resultSet, rowNumber) -> new RoutineCommandTemplateRecord(
                        UUID.fromString(resultSet.getString("id")),
                        TaskIntent.valueOf(resultSet.getString("intent")),
                        resultSet.getString("dialect_code"),
                        resultSet.getString("dialect_package_version"),
                        resultSet.getString("template_model_version"),
                        resultSet.getString("threshold_version"),
                        resultSet.getBytes("template_cipher"),
                        resultSet.getBytes("template_digest"),
                        resultSet.getInt("usage_count"),
                        resultSet.getTimestamp("last_confirmed_at").toInstant(),
                        resultSet.getLong("version"),
                        resultSet.getTimestamp("updated_at").toInstant()),
                ownerUserId.toString());
    }

    private Optional<Long> namespaceVersion(UUID ownerUserId, boolean forUpdate) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        List<Long> versions = jdbcTemplate.query(
                "SELECT version FROM routine_command_namespace "
                        + "WHERE owner_user_id=UUID_TO_BIN(?)" + suffix,
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                ownerUserId.toString());
        if (versions.size() > 1) {
            throw new IllegalStateException("日常指令命名空间不唯一");
        }
        return versions.stream().findFirst().map(version -> {
            if (version < 1) {
                throw new IllegalStateException("日常指令命名空间版本无效");
            }
            return version;
        });
    }
}
