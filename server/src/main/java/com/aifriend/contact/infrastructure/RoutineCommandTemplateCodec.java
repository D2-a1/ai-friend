package com.aifriend.contact.infrastructure;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * 版本化单段日常动作 MFCC 模板有界编解码器。
 *
 * @author Codex
 * @since 1.0.0
 */
final class RoutineCommandTemplateCodec {

    private static final int MAGIC = 0x41495243;
    private static final short SCHEMA_VERSION = 1;
    private static final int MAXIMUM_TEMPLATE_BYTES = 262_144;
    private static final int MAXIMUM_FRAME_COUNT = 1_200;
    private static final int MAXIMUM_DIMENSION_COUNT = 20;

    byte[] encode(float[][] sequence) {
        int dimensions = validate(sequence);
        long byteCount = Integer.BYTES + Short.BYTES + Short.BYTES
                + Integer.BYTES + (long) sequence.length * dimensions * Float.BYTES;
        if (byteCount > MAXIMUM_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("ROUTINE_TEMPLATE_SIZE_INVALID");
        }
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(byteCount));
        buffer.putInt(MAGIC);
        buffer.putShort(SCHEMA_VERSION);
        buffer.putShort((short) dimensions);
        buffer.putInt(sequence.length);
        for (float[] frame : sequence) {
            for (float coefficient : frame) {
                buffer.putFloat(coefficient);
            }
        }
        return buffer.array();
    }

    float[][] decode(byte[] material) {
        if (material == null || material.length < 16
                || material.length > MAXIMUM_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("ROUTINE_TEMPLATE_SIZE_INVALID");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(material);
            int magic = buffer.getInt();
            short schemaVersion = buffer.getShort();
            int dimensions = Short.toUnsignedInt(buffer.getShort());
            int frameCount = buffer.getInt();
            long requiredBytes = (long) frameCount * dimensions * Float.BYTES;
            if (magic != MAGIC || schemaVersion != SCHEMA_VERSION
                    || dimensions < 1 || dimensions > MAXIMUM_DIMENSION_COUNT
                    || frameCount < 1 || frameCount > MAXIMUM_FRAME_COUNT
                    || requiredBytes != buffer.remaining()) {
                throw new IllegalArgumentException("ROUTINE_TEMPLATE_HEADER_INVALID");
            }
            float[][] sequence = new float[frameCount][dimensions];
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    float coefficient = buffer.getFloat();
                    if (!Float.isFinite(coefficient)) {
                        throw new IllegalArgumentException(
                                "ROUTINE_TEMPLATE_VALUE_INVALID");
                    }
                    sequence[frameIndex][dimension] = coefficient;
                }
            }
            return sequence;
        } catch (BufferUnderflowException exception) {
            throw new IllegalArgumentException(
                    "ROUTINE_TEMPLATE_TRUNCATED", exception);
        }
    }

    private int validate(float[][] sequence) {
        if (sequence == null || sequence.length < 1
                || sequence.length > MAXIMUM_FRAME_COUNT
                || sequence[0] == null || sequence[0].length < 1
                || sequence[0].length > MAXIMUM_DIMENSION_COUNT) {
            throw new IllegalArgumentException("ROUTINE_TEMPLATE_SHAPE_INVALID");
        }
        int dimensions = sequence[0].length;
        for (float[] frame : sequence) {
            if (frame == null || frame.length != dimensions) {
                throw new IllegalArgumentException("ROUTINE_TEMPLATE_SHAPE_INVALID");
            }
            for (float coefficient : frame) {
                if (!Float.isFinite(coefficient)) {
                    throw new IllegalArgumentException(
                            "ROUTINE_TEMPLATE_VALUE_INVALID");
                }
            }
        }
        return dimensions;
    }
}
