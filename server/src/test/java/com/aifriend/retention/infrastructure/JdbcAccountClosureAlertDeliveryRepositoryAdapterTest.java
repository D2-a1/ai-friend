package com.aifriend.retention.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifriend.retention.application.AccountClosureAlertAudience;
import com.aifriend.retention.application.AccountClosureAlertDelivery;
import com.aifriend.retention.application.AccountClosureAlertType;
import com.aifriend.shared.security.SecurityKeyMaterial;
import com.aifriend.shared.security.SensitiveDataProtector;

class JdbcAccountClosureAlertDeliveryRepositoryAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-20T14:00:00Z");
    private static final String PROVIDER_REFERENCE =
            "1234567890abcdef1234567890abcdef";

    @Test
    void shouldMaterializeWarningForOnCallBeforeConsumingSourceEvent() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubOutboxQuery(
                jdbcTemplate,
                "ACCOUNT_CLOSURE_DELAY_WARNING",
                NOW.minusSeconds(60).toString(),
                null);
        List<String> updates = captureSuccessfulUpdates(jdbcTemplate);

        int materialized = adapter(jdbcTemplate).materializePending(NOW, 50);

        assertEquals(1, materialized);
        assertEquals(2, updates.size());
        assertTrue(updates.get(0).startsWith(
                "INSERT INTO account_closure_alert_delivery"));
        assertTrue(updates.get(1).startsWith("UPDATE outbox_event SET status='COMPLETED'"));
        assertInvocationContains(jdbcTemplate, "aggregate_type='ACCOUNT_CLOSURE'");
        assertInvocationContains(jdbcTemplate, "FOR UPDATE SKIP LOCKED");
    }

    @Test
    void shouldMaterializeP0IndependentlyForBothResponsibilityGroups() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubOutboxQuery(
                jdbcTemplate,
                "ACCOUNT_CLOSURE_P0_OPENED",
                NOW.minusSeconds(60).toString(),
                NOW.plusSeconds(15 * 60).toString());
        List<String> updates = captureSuccessfulUpdates(jdbcTemplate);

        int materialized = adapter(jdbcTemplate).materializePending(NOW, 50);

        assertEquals(2, materialized);
        assertEquals(3, updates.size());
        assertInvocationContains(jdbcTemplate, "ON_CALL");
        assertInvocationContains(jdbcTemplate, "PRIVACY_OFFICER");
    }

    @Test
    void shouldListPendingAndDecryptSubmittedProviderReference() throws Exception {
        JdbcTemplate pendingJdbc = mock(JdbcTemplate.class);
        ResultSet pendingResultSet = deliveryResultSet("PENDING", null);
        stubSingleRowQuery(pendingJdbc, pendingResultSet);

        AccountClosureAlertDelivery pending = adapter(pendingJdbc)
                .listReady(NOW, 50).get(0);

        assertFalse(pending.hasProviderReference());
        assertNull(pending.providerReference());
        assertInvocationContains(pendingJdbc, "status IN ('PENDING','SUBMITTED')");

        JdbcTemplate submittedJdbc = mock(JdbcTemplate.class);
        ResultSet submittedResultSet = deliveryResultSet(
                "SUBMITTED", protector().encrypt(PROVIDER_REFERENCE));
        stubSingleRowQuery(submittedJdbc, submittedResultSet);

        AccountClosureAlertDelivery submitted = adapter(submittedJdbc)
                .listReady(NOW, 50).get(0);

        assertTrue(submitted.hasProviderReference());
        assertEquals(PROVIDER_REFERENCE, submitted.providerReference());
    }

    @Test
    void shouldEncryptProviderReferenceBeforeConfirmingSubmission() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("PENDING");
        when(resultSet.getBytes(2)).thenReturn(null);
        stubSingleRowQuery(jdbcTemplate, resultSet);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertTrue(adapter(jdbcTemplate).confirmSubmitted(
                UUID.randomUUID(),
                PROVIDER_REFERENCE,
                NOW,
                NOW.plusSeconds(30)));

        assertInvocationContains(jdbcTemplate, "status='SUBMITTED'");
        byte[] storedCipher = firstByteArrayUpdateArgument(jdbcTemplate);
        assertEquals(PROVIDER_REFERENCE, protector().decrypt(storedCipher));
        assertFalse(new String(storedCipher, java.nio.charset.StandardCharsets.UTF_8)
                .contains(PROVIDER_REFERENCE));
    }

    @Test
    void shouldConfirmOnlySubmittedDeliveryAndClearProviderReference() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("SUBMITTED");
        when(resultSet.getBytes(2)).thenReturn(null);
        stubSingleRowQuery(jdbcTemplate, resultSet);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        byte[] receiptHash = new byte[32];

        assertTrue(adapter(jdbcTemplate).confirmDelivered(
                UUID.randomUUID(), receiptHash, NOW));

        assertInvocationContains(jdbcTemplate, "status='DELIVERED'");
        assertInvocationContains(jdbcTemplate, "provider_reference_cipher=NULL");
        assertInvocationContains(jdbcTemplate, "status='SUBMITTED'");
    }

    @Test
    void shouldAcceptSameReceiptReplayAndRejectConflictingReceipt() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        byte[] originalHash = new byte[32];
        when(resultSet.getString(1)).thenReturn("DELIVERED");
        when(resultSet.getBytes(2)).thenReturn(originalHash);
        stubSingleRowQuery(jdbcTemplate, resultSet);
        JdbcAccountClosureAlertDeliveryRepositoryAdapter adapter = adapter(jdbcTemplate);

        assertTrue(adapter.confirmDelivered(UUID.randomUUID(), originalHash, NOW));
        byte[] conflictingHash = new byte[32];
        conflictingHash[0] = 1;
        assertThrows(
                IllegalStateException.class,
                () -> adapter.confirmDelivered(UUID.randomUUID(), conflictingHash, NOW));

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void shouldRetainReferenceOnTransportRetryAndClearOnlyExplicitFailure() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcAccountClosureAlertDeliveryRepositoryAdapter adapter = adapter(jdbcTemplate);
        UUID deliveryId = UUID.randomUUID();

        adapter.markRetry(deliveryId, NOW, NOW.plusSeconds(30));
        assertInvocationContains(jdbcTemplate, "status IN ('PENDING','SUBMITTED')");
        assertFalse(invocationsContain(jdbcTemplate, "provider_reference_cipher=NULL"));

        adapter.resetFailedSubmission(deliveryId, NOW, NOW.plusSeconds(60));
        assertInvocationContains(jdbcTemplate, "provider_reference_cipher=NULL");
        assertInvocationContains(jdbcTemplate, "status='PENDING'");
    }

    @Test
    void shouldRejectInvalidBatchAndNonIncreasingScheduleBeforeDatabaseAccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcAccountClosureAlertDeliveryRepositoryAdapter adapter = adapter(jdbcTemplate);

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.materializePending(NOW, 101));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.markRetry(UUID.randomUUID(), NOW, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.scheduleVerification(UUID.randomUUID(), NOW, NOW));

        verify(jdbcTemplate, never()).query(
                anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubOutboxQuery(
            JdbcTemplate jdbcTemplate,
            String eventType,
            String occurredAt,
            String acknowledgementDueAt) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getString(2)).thenReturn(eventType);
        when(resultSet.getString(3)).thenReturn(occurredAt);
        when(resultSet.getString(4)).thenReturn(acknowledgementDueAt);
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubSingleRowQuery(JdbcTemplate jdbcTemplate, ResultSet resultSet)
            throws Exception {
        doAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(
                anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet deliveryResultSet(String status, byte[] providerReferenceCipher)
            throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(UUID.randomUUID().toString());
        when(resultSet.getString(2)).thenReturn("ON_CALL");
        when(resultSet.getString(3)).thenReturn("ACCOUNT_CLOSURE_DELAY_WARNING");
        when(resultSet.getTimestamp(4)).thenReturn(java.sql.Timestamp.from(NOW));
        when(resultSet.getTimestamp(5)).thenReturn(null);
        when(resultSet.getInt(6)).thenReturn(2);
        when(resultSet.getString(7)).thenReturn(status);
        when(resultSet.getBytes(8)).thenReturn(providerReferenceCipher);
        return resultSet;
    }

    private List<String> captureSuccessfulUpdates(JdbcTemplate jdbcTemplate) {
        List<String> updates = new ArrayList<>();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return 1;
        });
        return updates;
    }

    private JdbcAccountClosureAlertDeliveryRepositoryAdapter adapter(
            JdbcTemplate jdbcTemplate) {
        return new JdbcAccountClosureAlertDeliveryRepositoryAdapter(
                jdbcTemplate, protector());
    }

    private SensitiveDataProtector protector() {
        byte[] keyBytes = new byte[32];
        for (int index = 0; index < keyBytes.length; index++) {
            keyBytes[index] = (byte) (index + 1);
        }
        SecurityKeyMaterial keyMaterial = new SecurityKeyMaterial(
                new SecretKeySpec(keyBytes, "HmacSHA256"),
                new SecretKeySpec(keyBytes, "AES"),
                new SecretKeySpec(keyBytes, "HmacSHA256"));
        return new SensitiveDataProtector(keyMaterial);
    }

    private byte[] firstByteArrayUpdateArgument(JdbcTemplate jdbcTemplate) {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .findFirst()
                .orElseThrow();
    }

    private void assertInvocationContains(JdbcTemplate jdbcTemplate, String expected) {
        assertTrue(invocationsContain(jdbcTemplate, expected));
    }

    private boolean invocationsContain(JdbcTemplate jdbcTemplate, String expected) {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .map(argument -> argument == null ? "null" : argument.toString())
                .anyMatch(argument -> argument.contains(expected));
    }
}
