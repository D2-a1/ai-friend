package com.aifriend.voicecollection.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VoiceTrainingDatasetMigrationTest {

    @Test
    void shouldKeepDatasetManifestMinimalAndDeletionCompatible() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V30__voice_training_dataset.sql"));

        assertThat(migration)
                .contains("CREATE TABLE voice_training_dataset")
                .contains("CREATE TABLE voice_training_dataset_member")
                .contains("manifest_sha256 BINARY(32)")
                .contains("reviewed_transcript_sha256 BINARY(32)")
                .contains("REFERENCES voice_collection_sample (id) ON DELETE CASCADE")
                .doesNotContain("reviewed_transcript_cipher")
                .doesNotContain("object_key_cipher");
    }
}
