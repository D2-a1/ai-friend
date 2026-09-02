package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VoiceTrainingInputExportPropertiesTest {

    @Test
    void shouldAllowRelativePlaceholderOnlyWhileDisabled() {
        assertDoesNotThrow(() -> new VoiceTrainingInputExportProperties(
                false, "target/voice-training-exports", 1_073_741_824L));

        assertThrows(
                IllegalArgumentException.class,
                () -> new VoiceTrainingInputExportProperties(
                        true, "target/voice-training-exports", 1_073_741_824L));
    }

    @Test
    void shouldRejectInvalidCapacityBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoiceTrainingInputExportProperties(false, "unused", 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VoiceTrainingInputExportProperties(
                        false, "unused", 10_737_418_241L));
    }
}
