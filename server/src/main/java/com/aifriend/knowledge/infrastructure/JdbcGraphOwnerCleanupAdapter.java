package com.aifriend.knowledge.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifriend.knowledge.application.GraphOwnerCleanupPort;
import com.aifriend.knowledge.application.GraphProjectionException;
import com.aifriend.knowledge.application.GraphProjectionException.Kind;

/**
 * 独立于能力开关的图谱清理；损坏的世代、版本或摘要不阻止撤权删除。
 * 先锁app_user，与投影发布串行，再依外键顺序删除并逐表复验。
 * @author Codex
 * @since 1.0.0
 */
@Component
public final class JdbcGraphOwnerCleanupAdapter implements GraphOwnerCleanupPort {
    private static final List<String> TABLES = List.of(
            "knowledge_graph_edge", "knowledge_graph_node", "knowledge_graph_snapshot");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /**
     * 创建不访问数据库的清理器。
     * @param source 同源数据源
     * @param transactions 同源事务管理器
     */
    @Autowired
    public JdbcGraphOwnerCleanupAdapter(DataSource source, PlatformTransactionManager transactions) {
        this(new JdbcTemplate(Objects.requireNonNull(source)), transactions);
        jdbc.setQueryTimeout(2);
    }

    // 模拟SQL及事务专用构造。
    JdbcGraphOwnerCleanupAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public int purgeOwner(UUID ownerUserId) {
        String owner = Objects.requireNonNull(ownerUserId).toString();
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                var accounts = jdbc.query("SELECT BIN_TO_UUID(id) id FROM app_user WHERE id=UUID_TO_BIN(?) FOR UPDATE",
                        (rs, row) -> rs.getString("id"), owner);
                // 允许已删除账号/无账号的孤儿清理，但拒绝错误owner的结果。
                if (accounts.size() > 1 || (!accounts.isEmpty() && !owner.equals(accounts.get(0)))) {
                    throw new GraphProjectionException(Kind.INVALID);
                }
                int changed = 0;
                for (String table : TABLES) {
                    int count = jdbc.update("DELETE FROM " + table + " WHERE owner_user_id=UUID_TO_BIN(?)", owner);
                    if (count < 0) { throw new GraphProjectionException(Kind.INVALID); }
                    changed = Math.addExact(changed, count);
                }
                for (String table : TABLES) {
                    Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                            + " WHERE owner_user_id=UUID_TO_BIN(?)", Long.class, owner);
                    if (remaining == null || remaining != 0) { throw new GraphProjectionException(Kind.INVALID); }
                }
                return changed;
            }));
        } catch (DataAccessException | TransactionException exception) {
            throw new GraphProjectionException(Kind.STORAGE_UNAVAILABLE);
        }
    }
}
