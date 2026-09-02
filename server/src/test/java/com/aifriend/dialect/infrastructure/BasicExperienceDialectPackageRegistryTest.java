package com.aifriend.dialect.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aifriend.dialect.application.DialectPackageState;

class BasicExperienceDialectPackageRegistryTest {

    @Test
    void shouldExposeExplicitlyLabeledBasicPackage() {
        BasicExperienceDialectPackageRegistry registry =
                new BasicExperienceDialectPackageRegistry();

        assertEquals(DialectPackageState.BASIC_EXPERIENCE, registry.state());
        assertTrue(registry.findActive().isPresent());
        assertEquals("basic-experience-v1",
                registry.findActive().orElseThrow().manifest().packageVersion());
        assertEquals("vosk-model-small-cn-0.22",
                registry.findActive().orElseThrow().manifest().mandarinAssistVersion());
    }
}
