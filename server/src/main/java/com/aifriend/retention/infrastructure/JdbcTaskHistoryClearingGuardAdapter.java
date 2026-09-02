package com.aifriend.retention.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.task.application.TaskHistoryClearingGuardPort;

/**
 * MySQL 任务历史清除门闩查询适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcTaskHistoryClearingGuardAdapter implements TaskHistoryClearingGuardPort {
    private final JdbcTemplate jdbcTemplate;
    /**
     * 创建门闩查询适配器。
     *
     * @param jdbcTemplate owner 隔离的数据库访问组件
     */
    public JdbcTaskHistoryClearingGuardAdapter(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    /** {@inheritDoc} */
    @Override public boolean isClearing(UUID ownerUserId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_history_deletion WHERE owner_user_id=UUID_TO_BIN(?) AND status='CLEARING'", Long.class, ownerUserId.toString());
        return count != null && count > 0;
    }
}
