package com.aifriend.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.aifriend.retrieval.domain.EmbeddingProfile;

class KnowledgeVectorCodecTest {
    private final KnowledgeVectorCodec codec = new KnowledgeVectorCodec();
    private final EmbeddingProfile profile = new EmbeddingProfile("test-space", 2);
    private final UUID generation = new UUID(0, 1);
    private final UUID chunk = new UUID(0, 2);

    @Test void roundTripIsFixedBigEndianAndPreservesValues() {
        var stored = codec.encode(profile, generation, chunk, new float[] {1f, -2f});
        assertThat(stored.bytes()).containsExactly(0x3f, (byte) 0x80, 0, 0, (byte) 0xc0, 0, 0, 0);
        assertThat(codec.decode(profile, generation, chunk, stored)).containsExactly(1f, -2f);
    }

    @Test void mismatchedGenerationChunkOrSameDimensionProfileFails() {
        var stored = codec.encode(profile, generation, chunk, new float[] {1, 2});
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(profile, chunk, chunk, stored));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(profile, generation, generation, stored));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(new EmbeddingProfile("other", 2), generation, chunk, stored));
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(new EmbeddingProfile("test-space", 1), generation, chunk, stored));
    }

    @Test void corruptionCannotBeIgnoredEvenWithCorrectByteLength() {
        var stored = codec.encode(profile, generation, chunk, new float[] {1, 2});
        byte[] corrupted = stored.bytes();
        corrupted[0] ^= 1;
        assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(profile, generation, chunk,
                new KnowledgeVectorCodec.Stored(corrupted, stored.digest())));
    }

    @Test void rejectsZeroNonFiniteAndWrongDimension() {
        for (float[] vector : new float[][] {{0, 0}, {Float.NaN, 1}, {Float.NEGATIVE_INFINITY, 1}, {1}, {1, 2, 3}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> codec.encode(profile, generation, chunk, vector));
        }
        for (int length : new int[] {0, 1, 3, 5, 16385}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeVectorCodec.Stored(new byte[length], new byte[32]));
        }
    }

    @Test void supportsOneAndMaximumDimensionAndTinyFiniteValues() {
        for (int dimension : new int[] {1, 4096}) {
            var space = new EmbeddingProfile("boundary", dimension);
            float[] vector = new float[dimension];
            vector[0] = Float.MIN_VALUE;
            vector[dimension - 1] = Float.MAX_VALUE;
            assertThat(codec.decode(space, generation, chunk, codec.encode(space, generation, chunk, vector)))
                    .containsExactly(vector);
        }
    }

    @Test void mutableInputsAndAccessorsCannotChangeValidatedStorage() {
        var stored = codec.encode(profile, generation, chunk, new float[] {1, 2});
        byte[] bytes = stored.bytes();
        byte[] digest = stored.digest();
        var copy = new KnowledgeVectorCodec.Stored(bytes, digest);
        bytes[0] = 0;
        digest[0] ^= 1;
        copy.bytes()[0] = 0;
        copy.digest()[0] ^= 1;
        assertThat(codec.decode(profile, generation, chunk, copy)).containsExactly(1, 2);
        assertThat(copy.toString()).isEqualTo("Stored[redacted]");
    }
}
