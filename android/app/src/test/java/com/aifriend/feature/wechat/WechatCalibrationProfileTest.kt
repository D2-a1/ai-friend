package com.aifriend.feature.wechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatCalibrationProfileTest {
    @Test
    fun `profile requires one complete capability instead of mixed partial targets`() {
        val incomplete = WechatCalibrationPurpose.CALL.targets
            .minus(WechatCalibrationTarget.CALL_CHOICE_VIDEO)
            .associateWith { WechatNormalizedCalibrationPoint(100_000, 200_000) }

        assertThrows(IllegalArgumentException::class.java) {
            profile(points = incomplete)
        }
    }

    @Test
    fun `legacy seven point call profile decodes without requiring message calibration`() {
        val legacy = profile(
            points = WechatCalibrationPurpose.CALL.targets.associateWith { target ->
                WechatNormalizedCalibrationPoint(
                    xMillionths = 100_000 + target.ordinal * 10_000,
                    yMillionths = 200_000 + target.ordinal * 10_000,
                )
            },
        )

        val decoded = WechatCalibrationProfileCodec.decode(
            WechatCalibrationProfileCodec.encode(listOf(legacy)),
        )?.profiles?.single()

        assertTrue(requireNotNull(decoded).supportsCall)
        assertEquals(false, decoded.supportsMessage)
        assertEquals(WechatCalibrationPurpose.CALL.targets.toSet(), decoded.points.keys)
    }

    @Test
    fun `profile set accepts many distinct devices without count limit`() {
        val profiles = (1..128).map { index ->
            profile(key = key(model = "model-$index"))
        }

        val decoded = WechatCalibrationProfileCodec.decode(
            WechatCalibrationProfileCodec.encode(profiles),
        )

        assertEquals(128, decoded?.profiles?.size)
        assertEquals(profiles.map { it.key }.toSet(), decoded?.profiles?.map { it.key }?.toSet())
    }

    @Test
    fun `duplicate exact keys and malformed json fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            WechatCalibrationProfileSet(profiles = listOf(profile(), profile()))
        }
        assertNull(WechatCalibrationProfileCodec.decode("{} trailing"))
        assertNull(WechatCalibrationProfileCodec.decode(""))
    }

    @Test
    fun `normalized points convert deterministically inside matching display`() {
        val topLeft = WechatNormalizedCalibrationPoint(0, 0).toPixels(1080, 2400)
        val bottomRight = WechatNormalizedCalibrationPoint(
            WECHAT_CALIBRATION_COORDINATE_SCALE,
            WECHAT_CALIBRATION_COORDINATE_SCALE,
        ).toPixels(1080, 2400)
        val center = WechatNormalizedCalibrationPoint(500_000, 500_000).toPixels(1080, 2400)

        assertEquals(WechatCalibrationPixelPoint(0, 0), topLeft)
        assertEquals(WechatCalibrationPixelPoint(1079, 2399), bottomRight)
        assertEquals(WechatCalibrationPixelPoint(540, 1200), center)
        assertEquals(
            WechatCalibrationPixelPoint(539, 1199),
            WechatNormalizedCalibrationPoint.fromPixels(539, 1199, 1080, 2400)
                ?.toPixels(1080, 2400),
        )
        assertNull(WechatNormalizedCalibrationPoint.fromPixels(1080, 100, 1080, 2400))
    }

    @Test
    fun `profile matching is exact across every device and display fact`() {
        val profile = profile()
        val registry = InMemoryCalibrationRegistry(listOf(profile))

        assertEquals(profile, registry.findExact(profile.key))
        assertNull(registry.findExact(profile.key.copy(densityDpi = 421)))
        assertNull(registry.findExact(profile.key.copy(fontScalePermille = 1_001)))
        assertNull(registry.findExact(profile.key.copy(wechatVersion = "8.0.77")))
    }

    @Test
    fun `unsafe device facts are rejected`() {
        assertNull(normalizeCalibrationDeviceFact("bad\nmodel", lowercase = false))
        assertEquals("vivo", normalizeCalibrationDeviceFact(" VIVO ", lowercase = true))
        assertTrue(key().stableSortKey().isNotEmpty())
    }

    private fun profile(
        key: WechatCalibrationProfileKey = key(),
        points: Map<WechatCalibrationTarget, WechatNormalizedCalibrationPoint> = allPoints(),
    ) = WechatCalibrationProfile(
        key = key,
        points = points,
        updatedAtEpochMillis = 1_788_192_000_000L,
    )

    private fun key(model: String = "V2536A") = WechatCalibrationProfileKey(
        manufacturer = "vivo",
        model = model,
        androidSdkInt = 36,
        displayWidthPixels = 1260,
        displayHeightPixels = 2800,
        densityDpi = 420,
        fontScalePermille = 1_000,
        orientation = WechatCalibrationOrientation.PORTRAIT,
        wechatVersion = "8.0.76",
    )

    private fun allPoints(): Map<WechatCalibrationTarget, WechatNormalizedCalibrationPoint> =
        WechatCalibrationTarget.entries.associateWith { target ->
            WechatNormalizedCalibrationPoint(
                xMillionths = 100_000 + target.ordinal * 10_000,
                yMillionths = 200_000 + target.ordinal * 10_000,
            )
        }
}

private class InMemoryCalibrationRegistry(
    profiles: List<WechatCalibrationProfile>,
) : WechatCalibrationProfileRegistry {
    private val values = profiles.associateBy { it.key }.toMutableMap()

    override fun list(): List<WechatCalibrationProfile> = values.values.toList()

    override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? = values[key]

    override fun upsert(profile: WechatCalibrationProfile): Boolean {
        values[profile.key] = profile
        return true
    }

    override fun remove(key: WechatCalibrationProfileKey): Boolean {
        values.remove(key)
        return true
    }
}
