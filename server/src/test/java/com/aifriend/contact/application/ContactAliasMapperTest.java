package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.shared.security.SensitiveDataProtector;

class ContactAliasMapperTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Test
    void shouldReportMissingServerMaterialAsIncompatibleEvenWhenVersionsMatch() {
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        AcousticTemplatePort acousticTemplatePort = mock(AcousticTemplatePort.class);
        when(protector.decrypt(any())).thenReturn("老大");
        when(acousticTemplatePort.isCompatible(
                "zh-Hans-CN-x-wugang", "basic-experience-v1",
                "mfcc-dtw-basic-v1", "basic-personal-v2")).thenReturn(true);
        ContactAliasMapper mapper = new ContactAliasMapper(protector, acousticTemplatePort);

        ContactAliasSummary summary = mapper.toSummary(alias(null, null));

        assertEquals("INCOMPATIBLE", summary.compatibility());
    }

    @Test
    void shouldReportCompleteCurrentAliasAsCompatible() {
        SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
        AcousticTemplatePort acousticTemplatePort = mock(AcousticTemplatePort.class);
        when(protector.decrypt(any())).thenReturn("老大");
        when(acousticTemplatePort.isCompatible(
                "zh-Hans-CN-x-wugang", "basic-experience-v1",
                "mfcc-dtw-basic-v1", "basic-personal-v2")).thenReturn(true);
        ContactAliasMapper mapper = new ContactAliasMapper(protector, acousticTemplatePort);

        ContactAliasSummary summary = mapper.toSummary(alias(new byte[] {2}, new byte[32]));

        assertEquals("COMPATIBLE", summary.compatibility());
    }

    private ContactAlias alias(byte[] templateCipher, byte[] templateDigest) {
        return new ContactAlias(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new byte[] {1}, null, "zh-Hans-CN-x-wugang",
                "basic-experience-v1", "mfcc-dtw-basic-v1", "basic-personal-v2",
                templateCipher, templateDigest, ContactAliasStatus.ACTIVE,
                new byte[] {3}, new byte[] {4}, null, null,
                0, NOW, NOW, null);
    }
}