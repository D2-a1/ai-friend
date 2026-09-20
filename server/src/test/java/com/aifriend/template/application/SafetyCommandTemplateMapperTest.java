package com.aifriend.template.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;
import com.aifriend.template.domain.SafetyCommandType;

class SafetyCommandTemplateMapperTest {

    private static final String DIALECT = "zh-Hans-CN-x-wugang";
    private static final String PACKAGE_VERSION = "basic-experience-v1";
    private static final String MODEL_VERSION = "mfcc-dtw-basic-v1";
    private static final String THRESHOLD_VERSION = "basic-personal-v2";
    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void shouldNotReportVersionCompatibleTemplateWithoutPersistedMaterialAsUsable() {
        AcousticTemplatePort acousticTemplatePort = mock(AcousticTemplatePort.class);
        when(acousticTemplatePort.isCompatible(
                DIALECT, PACKAGE_VERSION, MODEL_VERSION, THRESHOLD_VERSION))
                .thenReturn(true);
        SafetyCommandTemplateMapper mapper = new SafetyCommandTemplateMapper(acousticTemplatePort);

        VoiceTemplateSummary summary = mapper.toSummary(template(null, null));

        assertEquals("INCOMPATIBLE", summary.compatibility());
    }

    @Test
    void shouldReportTemplateWithMaterialAndCurrentVersionsAsCompatible() {
        AcousticTemplatePort acousticTemplatePort = mock(AcousticTemplatePort.class);
        when(acousticTemplatePort.isCompatible(
                DIALECT, PACKAGE_VERSION, MODEL_VERSION, THRESHOLD_VERSION))
                .thenReturn(true);
        SafetyCommandTemplateMapper mapper = new SafetyCommandTemplateMapper(acousticTemplatePort);

        VoiceTemplateSummary summary = mapper.toSummary(template(
                new byte[] {1, 2, 3}, new byte[32]));

        assertEquals("COMPATIBLE", summary.compatibility());
    }

    private SafetyCommandTemplate template(byte[] cipher, byte[] digest) {
        return new SafetyCommandTemplate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SafetyCommandType.CONFIRM_SEND, DIALECT, PACKAGE_VERSION,
                MODEL_VERSION, THRESHOLD_VERSION, cipher, digest,
                SafetyCommandTemplateStatus.ACTIVE, 0, NOW, NOW, null);
    }
}