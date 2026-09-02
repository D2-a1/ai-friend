package com.aifriend.identity.infrastructure;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.shared.security.ActiveAccountStatusPort;

/**
 * MySQL ACTIVE 账号安全门禁适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcActiveAccountStatusAdapter implements ActiveAccountStatusPort {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建账号状态门禁适配器。
     *
     * @param jdbcTemplate 参数化数据库访问组件
     */
    public JdbcActiveAccountStatusAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isActive(UUID userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id=UUID_TO_BIN(?) AND status='ACTIVE'",
                Long.class,
                userId.toString());
        return count != null && count == 1L;
    }
}
