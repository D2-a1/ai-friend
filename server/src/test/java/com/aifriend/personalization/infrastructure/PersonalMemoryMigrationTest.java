package com.aifriend.personalization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PersonalMemoryMigrationTest {

    @Test
    void shouldStoreOnlyEncryptedConstrainedPreferencesAndDeletionMetadata()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V33__personal_assistant_memory.sql"));

        assertThat(migration)
                .contains("'PERSONAL_MEMORY'")
                .contains("CREATE TABLE personal_assistant_memory")
                .contains("preferences_cipher VARBINARY")
                .contains("preferences_digest BINARY(32)")
                .contains("status IN ('ACTIVE', 'DELETED')")
                .contains("status = 'DELETED' AND preferences_cipher IS NULL")
                .doesNotContain("message_text")
                .doesNotContain("contact_id")
                .doesNotContain("audio_object_id")
                .doesNotContain("transcript");
    }
}
