package com.aifriend.feature.wechat

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatCalibrationCaptureTest {
    @Test
    fun `seven explicit points save one complete profile and no partial profile`() {
        val key = key()
        val registry = MutableRegistry()
        val coordinator = coordinator(key, registry)
        coordinator.updateAccessibilityReady(true)

        assertTrue(coordinator.start())
        WechatCalibrationPurpose.CALL.targets.forEachIndexed { index, target ->
            val request = coordinator.activeRequest(
                WechatSemanticCallContract.WECHAT_PACKAGE,
                OffsetDateTime.now(),
            )
            assertEquals(target, request?.target)
            val result = coordinator.record(
                request = requireNotNull(request),
                rawX = 100 + index,
                rawY = 200 + index,
                now = OffsetDateTime.now(),
            )
            if (index < WechatCalibrationPurpose.CALL.targets.lastIndex) {
                assertEquals(WechatCalibrationRecordResult.SAVED_NEXT, result)
                assertTrue(registry.list().isEmpty())
            } else {
                assertEquals(WechatCalibrationRecordResult.COMPLETED, result)
            }
        }

        assertFalse(coordinator.state.value.active)
        assertEquals(1, registry.list().size)
        assertEquals(
            WechatCalibrationPurpose.CALL.targets.toSet(),
            registry.list().single().points.keys,
        )
        assertNull(
            coordinator.activeRequest(
                WechatSemanticCallContract.WECHAT_PACKAGE,
                OffsetDateTime.now(),
            ),
        )
    }

    @Test
    fun `message calibration adds five points without replacing existing call points`() {
        val key = key()
        val registry = MutableRegistry()
        val coordinator = coordinator(key, registry)
        coordinator.updateAccessibilityReady(true)
        recordPurpose(coordinator, WechatCalibrationPurpose.CALL)

        assertTrue(coordinator.start(WechatCalibrationPurpose.MESSAGE))
        recordActivePurpose(coordinator, WechatCalibrationPurpose.MESSAGE)

        val profile = registry.list().single()
        assertTrue(profile.supportsCall)
        assertTrue(profile.supportsMessage)
        assertEquals(WechatCalibrationTarget.entries.toSet(), profile.points.keys)
    }

    @Test
    fun `display fingerprint change discards the whole session`() {
        val original = key()
        var current = original
        val registry = MutableRegistry()
        val coordinator = AndroidWechatCalibrationCaptureCoordinator(
            runtimeVersionProvider = WechatRuntimeVersionProvider { VERSION },
            fingerprintProvider = WechatCalibrationFingerprintProvider { current },
            profileRegistry = registry,
        )
        coordinator.updateAccessibilityReady(true)
        assertTrue(coordinator.start())
        val request = requireNotNull(
            coordinator.activeRequest(
                WechatSemanticCallContract.WECHAT_PACKAGE,
                OffsetDateTime.now(),
            ),
        )
        current = original.copy(fontScalePermille = 1_150)

        val result = coordinator.record(request, 100, 200, OffsetDateTime.now())

        assertEquals(WechatCalibrationRecordResult.FAILED, result)
        assertFalse(coordinator.state.value.active)
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `accessibility must already be connected before starting`() {
        val registry = MutableRegistry()
        val coordinator = coordinator(key(), registry)

        assertFalse(coordinator.start())
        assertFalse(coordinator.state.value.active)
        assertTrue(registry.list().isEmpty())
    }

    private fun coordinator(
        key: WechatCalibrationProfileKey,
        registry: WechatCalibrationProfileRegistry,
    ) = AndroidWechatCalibrationCaptureCoordinator(
        runtimeVersionProvider = WechatRuntimeVersionProvider { VERSION },
        fingerprintProvider = WechatCalibrationFingerprintProvider { key },
        profileRegistry = registry,
    )

    private fun recordPurpose(
        coordinator: AndroidWechatCalibrationCaptureCoordinator,
        purpose: WechatCalibrationPurpose,
    ) {
        assertTrue(coordinator.start(purpose))
        recordActivePurpose(coordinator, purpose)
    }

    private fun recordActivePurpose(
        coordinator: AndroidWechatCalibrationCaptureCoordinator,
        purpose: WechatCalibrationPurpose,
    ) {
        purpose.targets.forEachIndexed { index, target ->
            val request = requireNotNull(
                coordinator.activeRequest(
                    WechatSemanticCallContract.WECHAT_PACKAGE,
                    OffsetDateTime.now(),
                ),
            )
            assertEquals(target, request.target)
            val result = coordinator.record(
                request = request,
                rawX = 100 + index,
                rawY = 200 + index,
                now = OffsetDateTime.now(),
            )
            assertEquals(
                if (index == purpose.targets.lastIndex) {
                    WechatCalibrationRecordResult.COMPLETED
                } else {
                    WechatCalibrationRecordResult.SAVED_NEXT
                },
                result,
            )
        }
    }

    private fun key() = WechatCalibrationProfileKey(
        manufacturer = "vivo",
        model = "V2536A",
        androidSdkInt = 36,
        displayWidthPixels = 1260,
        displayHeightPixels = 2800,
        densityDpi = 480,
        fontScalePermille = 1_000,
        orientation = WechatCalibrationOrientation.PORTRAIT,
        wechatVersion = VERSION,
    )

    private class MutableRegistry : WechatCalibrationProfileRegistry {
        private val profiles = linkedMapOf<WechatCalibrationProfileKey, WechatCalibrationProfile>()

        override fun list(): List<WechatCalibrationProfile> = profiles.values.toList()

        override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? =
            profiles[key]

        override fun upsert(profile: WechatCalibrationProfile): Boolean {
            profiles[profile.key] = profile
            return true
        }

        override fun remove(key: WechatCalibrationProfileKey): Boolean {
            profiles.remove(key)
            return true
        }
    }

    private companion object {
        const val VERSION = "8.0.76"
    }
}
