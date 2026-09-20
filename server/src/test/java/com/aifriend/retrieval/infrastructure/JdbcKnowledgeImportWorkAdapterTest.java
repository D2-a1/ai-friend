package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.retrieval.application.KnowledgeImportLeasePort.Claim;
import com.aifriend.retrieval.domain.KnowledgeImportJob;

class JdbcKnowledgeImportWorkAdapterTest {
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void readsRemainingFromDatabaseAndBindsBothVersionsAndCurrentToken() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var row = mock(ResultSet.class);
        when(row.getObject("remaining_micros")).thenReturn(250_000L);
        doAnswer(c -> {
            String sql = c.getArgument(0);
            assertThat(sql).contains("UTC_TIMESTAMP(3)", "c.version=?", "c.corpus_revision=?", "j.version=?",
                    "j.status='PROCESSING'", "j.lease_token=c.lease_token", "j.deadline");
            RowMapper mapper = c.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var adapter = new JdbcKnowledgeImportWorkAdapter(jdbc, transactions);
        assertThat(adapter.remaining(claim())).isEqualTo(Duration.ofMillis(250));
        when(row.getObject("remaining_micros")).thenReturn(0L);
        assertThatThrownBy(() -> adapter.remaining(claim())).isInstanceOf(ConcurrencyFailureException.class);
        when(row.getObject("remaining_micros")).thenReturn(300_000_001L);
        assertThatThrownBy(() -> adapter.remaining(claim())).isInstanceOf(ConcurrencyFailureException.class);
        when(row.getObject("remaining_micros")).thenReturn(null);
        assertThatThrownBy(() -> adapter.remaining(claim())).isInstanceOf(ConcurrencyFailureException.class);
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void dueScanIsBoundedAndIncludesExpiredJobsWithoutLoadingSourceText() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(new UUID(0, 1).toString());
        doAnswer(c -> {
            String sql = c.getArgument(0);
            assertThat(sql).contains("LIMIT ?", "status='PENDING'", "status='PROCESSING'", "deadline<=UTC_TIMESTAMP(3)")
                    .doesNotContain("original_text", "knowledge_document");
            RowMapper mapper = c.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var adapter = new JdbcKnowledgeImportWorkAdapter(jdbc, transactions);
        assertThat(adapter.due(1)).containsExactly(new UUID(0, 1));
        assertThatThrownBy(() -> adapter.due(11)).hasMessage("INVALID_WORK_SCAN_LIMIT");
        assertThatThrownBy(() -> adapter.due(0)).hasMessage("INVALID_WORK_SCAN_LIMIT");
    }

    @Test @SuppressWarnings("unchecked")
    void missingLeaseIsNotPositiveTime() {
        var jdbc = mock(JdbcTemplate.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertThatThrownBy(() -> new JdbcKnowledgeImportWorkAdapter(jdbc, transactions).remaining(claim()))
                .isInstanceOf(ConcurrencyFailureException.class);
    }

    private static Claim claim() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        return new Claim(KnowledgeImportJob.pending(new UUID(0, 1), now, now.plusSeconds(100))
                .claim(now, new UUID(0, 2), Duration.ofSeconds(30)), 3, 4);
    }
}
