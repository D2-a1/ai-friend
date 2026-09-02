package com.aifriend.retention.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.ContactUnboundCleanupJob;
import com.aifriend.retention.application.ContactUnboundCleanupRepositoryPort;

/**
 * MySQL 联系人解绑后续清理适配器。
 *
 * <p>owner 只从已解绑联系人绑定反查，不读取或信任 Outbox JSON 负载。
 * 所有写操作由上层单条事务统一提交。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class JdbcContactUnboundCleanupAdapter
        implements ContactUnboundCleanupRepositoryPort {

    private static final String LOCK_NEXT_READY_SQL = """
            SELECT BIN_TO_UUID(event.id) event_id,
                   BIN_TO_UUID(event.aggregate_id) contact_id,
                   BIN_TO_UUID(binding.owner_user_id) owner_user_id
            FROM outbox_event event
            JOIN contact_binding binding ON binding.id=event.aggregate_id
            WHERE event.aggregate_type='CONTACT_BINDING'
              AND event.event_type='CONTACT_UNBOUND'
              AND event.status='PENDING'
              AND event.available_at<=?
              AND binding.status='REVOKED'
            ORDER BY event.available_at,event.created_at,event.id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """;

    private static final String SCRUB_ALIASES_SQL = """
            UPDATE contact_alias
            SET display_text_cipher=NULL,
                phonetic_hint_cipher=NULL,
                template_cipher=NULL,
                template_digest=NULL,
                status='DELETED',
                deleted_at=COALESCE(deleted_at,?),
                updated_at=?,
                version=version+1
            WHERE owner_user_id=UUID_TO_BIN(?)
              AND binding_id=UUID_TO_BIN(?)
              AND (display_text_cipher IS NOT NULL
                   OR phonetic_hint_cipher IS NOT NULL
                   OR template_cipher IS NOT NULL
                   OR template_digest IS NOT NULL
                   OR status<>'DELETED')
            """;

    private static final String CANCEL_TASKS_SQL = """
            UPDATE task_session session
            SET session.state='CANCELLED',
                session.selected_contact_id=NULL,
                session.summary_hash=NULL,
                session.plan_id=NULL,
                session.plan_expires_at=NULL,
                session.session_version=session.session_version+1,
                session.updated_at=?
            WHERE session.owner_user_id=UUID_TO_BIN(?)
              AND session.state NOT IN (
                  'REJECTED','CANCELLED','COMPLETED','PARTIAL','FAILED'
              )
              AND (session.selected_contact_id=UUID_TO_BIN(?)
                   OR EXISTS (
                       SELECT 1
                       FROM task_candidate candidate
                       WHERE candidate.task_session_id=session.id
                         AND candidate.owner_user_id=UUID_TO_BIN(?)
                         AND candidate.contact_id=UUID_TO_BIN(?)
                   ))
            """;

    private static final String DELETE_CANCELLED_CANDIDATE_SQL = """
            DELETE candidate
            FROM task_candidate candidate
            JOIN task_session session ON session.id=candidate.task_session_id
            WHERE candidate.owner_user_id=UUID_TO_BIN(?)
              AND candidate.contact_id=UUID_TO_BIN(?)
              AND session.owner_user_id=UUID_TO_BIN(?)
              AND session.state='CANCELLED'
              AND session.updated_at=?
            """;

    private static final String COMPLETE_EVENT_SQL = """
            UPDATE outbox_event
            SET status='COMPLETED'
            WHERE id=UUID_TO_BIN(?)
              AND aggregate_type='CONTACT_BINDING'
              AND event_type='CONTACT_UNBOUND'
              AND status='PENDING'
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建联系人解绑清理适配器。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcContactUnboundCleanupAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ContactUnboundCleanupJob> lockNextReady(Instant now) {
        List<ContactUnboundCleanupJob> jobs = jdbcTemplate.query(
                LOCK_NEXT_READY_SQL,
                (resultSet, rowNumber) -> new ContactUnboundCleanupJob(
                        UUID.fromString(resultSet.getString("event_id")),
                        UUID.fromString(resultSet.getString("contact_id")),
                        UUID.fromString(resultSet.getString("owner_user_id"))),
                Timestamp.from(now));
        if (jobs.size() > 1) {
            throw new IllegalStateException("联系人解绑清理锁定结果超过一条");
        }
        return jobs.stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public int scrubAliases(
            UUID ownerUserId,
            UUID contactId,
            Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbcTemplate.update(
                SCRUB_ALIASES_SQL,
                timestamp,
                timestamp,
                ownerUserId.toString(),
                contactId.toString());
    }

    /** {@inheritDoc} */
    @Override
    public int cancelNonTerminalTasks(
            UUID ownerUserId,
            UUID contactId,
            Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        int cancelled = jdbcTemplate.update(
                CANCEL_TASKS_SQL,
                timestamp,
                ownerUserId.toString(),
                contactId.toString(),
                ownerUserId.toString(),
                contactId.toString());
        jdbcTemplate.update(
                DELETE_CANCELLED_CANDIDATE_SQL,
                ownerUserId.toString(),
                contactId.toString(),
                ownerUserId.toString(),
                timestamp);
        return cancelled;
    }

    /** {@inheritDoc} */
    @Override
    public void markCompleted(UUID eventId) {
        int changed = jdbcTemplate.update(
                COMPLETE_EVENT_SQL,
                eventId.toString());
        if (changed != 1) {
            throw new IllegalStateException("联系人解绑清理事件完成状态冲突");
        }
    }
}
