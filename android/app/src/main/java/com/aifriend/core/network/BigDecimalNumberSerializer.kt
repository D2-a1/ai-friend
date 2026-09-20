package com.aifriend.core.network

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * OpenAPI 任务置信度使用的 JSON number 序列化器。
 *
 * @author codex
 * @since 2026-09-03
 */
object BigDecimalNumberSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeDouble(value.toDouble())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal.valueOf(decoder.decodeDouble())
}