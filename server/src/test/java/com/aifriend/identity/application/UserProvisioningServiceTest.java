package com.aifriend.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class UserProvisioningServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final byte[] SUBJECT_HASH = new byte[32];
    private static final ProtectedWechatSubject PROTECTED_SUBJECT =
            new ProtectedWechatSubject(new byte[]{1, 2, 3}, SUBJECT_HASH);

    private UserAccountPort userAccountPort;
    private AccountGenerationGatePort generationGatePort;
    private UserProvisioningService service;

    @BeforeEach
    void setUp() {
        userAccountPort = mock(UserAccountPort.class);
        SubjectProtectionPort protectionPort = mock(SubjectProtectionPort.class);
        generationGatePort = mock(AccountGenerationGatePort.class);
        when(protectionPort.protect("subject")).thenReturn(PROTECTED_SUBJECT);
        service = new UserProvisioningService(
                userAccountPort,
                protectionPort,
                generationGatePort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReturnExistingActiveAccountWithoutReadingTombstone() {
        UserAccount existing = account(UserStatus.ACTIVE, 1L);
        when(userAccountPort.findByWechatSubjectHash(SUBJECT_HASH))
                .thenReturn(Optional.of(existing));

        UserAccount actual = service.findOrCreate(new WechatIdentity("subject"));

        assertSame(existing, actual);
        verify(generationGatePort, never()).nextGeneration(any(), any());
        verify(userAccountPort, never()).create(any(), anyLong(), any());
    }

    @Test
    void shouldRejectDeletingAccountWithoutCreatingReplacement() {
        when(userAccountPort.findByWechatSubjectHash(SUBJECT_HASH))
                .thenReturn(Optional.of(account(UserStatus.DELETING, 1L)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.findOrCreate(new WechatIdentity("subject")));

        assertEquals(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED, exception.errorCode());
        verify(generationGatePort, never()).nextGeneration(any(), any());
        verify(userAccountPort, never()).create(any(), anyLong(), any());
    }

    @Test
    void shouldCreateFirstGenerationAccountWhenNoTombstoneExists() {
        UserAccount created = account(UserStatus.ACTIVE, 1L);
        when(userAccountPort.findByWechatSubjectHash(SUBJECT_HASH))
                .thenReturn(Optional.empty());
        when(generationGatePort.nextGeneration(SUBJECT_HASH, NOW)).thenReturn(1L);
        when(userAccountPort.create(PROTECTED_SUBJECT, 1L, NOW)).thenReturn(created);

        UserAccount actual = service.findOrCreate(new WechatIdentity("subject"));

        assertSame(created, actual);
    }

    @Test
    void shouldCreateNewUuidWithNextGenerationAfterGatePasses() {
        UserAccount created = account(UserStatus.ACTIVE, 4L);
        when(userAccountPort.findByWechatSubjectHash(SUBJECT_HASH))
                .thenReturn(Optional.empty());
        when(generationGatePort.nextGeneration(SUBJECT_HASH, NOW)).thenReturn(4L);
        when(userAccountPort.create(PROTECTED_SUBJECT, 4L, NOW)).thenReturn(created);

        UserAccount actual = service.findOrCreate(new WechatIdentity("subject"));

        assertEquals(4L, actual.accountGeneration());
        verify(userAccountPort).create(PROTECTED_SUBJECT, 4L, NOW);
    }

    @Test
    void shouldNotCreateAccountWhenReRegistrationGateRejects() {
        when(userAccountPort.findByWechatSubjectHash(SUBJECT_HASH))
                .thenReturn(Optional.empty());
        when(generationGatePort.nextGeneration(SUBJECT_HASH, NOW))
                .thenThrow(new BusinessException(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.findOrCreate(new WechatIdentity("subject")));

        assertEquals(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED, exception.errorCode());
        verify(userAccountPort, never()).create(any(), anyLong(), any());
    }

    private UserAccount account(UserStatus status, long generation) {
        return new UserAccount(UUID.randomUUID(), status, generation, NOW);
    }
}
