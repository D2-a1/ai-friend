package com.aifriend.contact.infrastructure;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 版本化双录 MFCC 模板的有界二进制编解码器。
 */
final class AcousticTemplateCodec {

    private static final int MAGIC = 0x41494654;
    private static final short SCHEMA_VERSION = 1;
    private static final int ENROLLMENT_SEQUENCE_COUNT = 2;
    private static final int MAX_TEMPLATE_BYTES = 262_144;
    private static final int MAX_FRAME_COUNT = 1_200;
    private static final int MAX_DIMENSION_COUNT = 20;

    byte[] encode(List<float[][]> sequences) {
        if (sequences == null || sequences.size() != ENROLLMENT_SEQUENCE_COUNT) {
            throw new IllegalArgumentException("TEMPLATE_SEQUENCE_COUNT_INVALID");
        }
        int dimensions = validateSequence(sequences.get(0), -1);
        validateSequence(sequences.get(1), dimensions);
        long byteCount = Integer.BYTES + Short.BYTES + Short.BYTES + 1L;
        for (float[][] sequence : sequences) {
            byteCount += Integer.BYTES
                    + (long) sequence.length * dimensions * Float.BYTES;
        }
        if (byteCount > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("TEMPLATE_SIZE_INVALID");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) byteCount);
        buffer.putInt(MAGIC);
        buffer.putShort(SCHEMA_VERSION);
        buffer.putShort((short) dimensions);
        buffer.put((byte) ENROLLMENT_SEQUENCE_COUNT);
        for (float[][] sequence : sequences) {
            buffer.putInt(sequence.length);
            for (float[] frame : sequence) {
                for (float coefficient : frame) {
                    buffer.putFloat(coefficient);
                }
            }
        }
        return buffer.array();
    }

    DecodedAcousticTemplate decode(byte[] template) {
        if (template == null || template.length < 16
                || template.length > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("TEMPLATE_SIZE_INVALID");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(template);
            int magic = buffer.getInt();
            short version = buffer.getShort();
            int dimensions = Short.toUnsignedInt(buffer.getShort());
            int sequenceCount = Byte.toUnsignedInt(buffer.get());
            if (magic != MAGIC
                    || version != SCHEMA_VERSION
                    || dimensions < 1
                    || dimensions > MAX_DIMENSION_COUNT
                    || sequenceCount != ENROLLMENT_SEQUENCE_COUNT) {
                throw new IllegalArgumentException("TEMPLATE_HEADER_INVALID");
            }
            float[][] first = readSequence(buffer, dimensions);
            float[][] second = readSequence(buffer, dimensions);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("TEMPLATE_TRAILING_BYTES");
            }
            return new DecodedAcousticTemplate(first, second);
        } catch (BufferUnderflowException exception) {
            throw new IllegalArgumentException("TEMPLATE_TRUNCATED", exception);
        }
    }

    private int validateSequence(float[][] sequence, int expectedDimensions) {
        if (sequence == null
                || sequence.length < 1
                || sequence.length > MAX_FRAME_COUNT
                || sequence[0] == null
                || sequence[0].length < 1
                || sequence[0].length > MAX_DIMENSION_COUNT
                || (expectedDimensions > 0
                && sequence[0].length != expectedDimensions)) {
            throw new IllegalArgumentException("TEMPLATE_FEATURE_SHAPE_INVALID");
        }
        int dimensions = sequence[0].length;
        for (float[] frame : sequence) {
            if (frame == null || frame.length != dimensions) {
                throw new IllegalArgumentException("TEMPLATE_FEATURE_SHAPE_INVALID");
            }
            for (float coefficient : frame) {
                if (!Float.isFinite(coefficient)) {
                    throw new IllegalArgumentException("TEMPLATE_FEATURE_VALUE_INVALID");
                }
            }
        }
        return dimensions;
    }

    private float[][] readSequence(ByteBuffer buffer, int dimensions) {
        int frameCount = buffer.getInt();
        long requiredBytes = (long) frameCount * dimensions * Float.BYTES;
        if (frameCount < 1
                || frameCount > MAX_FRAME_COUNT
                || requiredBytes > buffer.remaining()) {
            throw new IllegalArgumentException("TEMPLATE_FRAME_COUNT_INVALID");
        }
        float[][] sequence = new float[frameCount][dimensions];
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                float coefficient = buffer.getFloat();
                if (!Float.isFinite(coefficient)) {
                    throw new IllegalArgumentException("TEMPLATE_FEATURE_VALUE_INVALID");
                }
                sequence[frameIndex][dimension] = coefficient;
            }
        }
        return sequence;
    }

    record DecodedAcousticTemplate(float[][] first, float[][] second) {
    }
}
