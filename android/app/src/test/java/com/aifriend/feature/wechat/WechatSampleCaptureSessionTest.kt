package com.aifriend.feature.wechat

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug 微信页面三次只读采集状态机测试。 */
class WechatSampleCaptureSessionTest {
    private val baseTime = OffsetDateTime.parse("2026-08-29T02:00:00Z")

    @Test
    fun releaseDisabledStateNeverStartsCapture() {
        val session = WechatSampleCaptureSession(enabled = false)

        assertFalse(session.state.value.visible)
        assertFalse(session.begin("8.0.60", baseTime))
        assertNull(session.activeRequest("com.tencent.mm", baseTime))
    }

    @Test
    fun threeMatchingStructuresCompleteAsConsistent() {
        val session = WechatSampleCaptureSession(enabled = true)

        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin("8.0.60", openedAt))
            session.publish(
                sample = sample("a".repeat(64), openedAt.plusSeconds(1)),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        val state = session.state.value
        assertEquals(3, state.completedSamples)
        assertTrue(state.complete)
        assertEquals(true, state.consistent)
        assertFalse(state.awaiting)
        assertEquals("a".repeat(64), state.pageSignatureSha256)
        assertEquals(20, state.nodeCount)
        assertEquals("c".repeat(64), state.locatorSourceSha256)
        assertEquals(1, state.locatorSourceCount)
        assertEquals(true, state.locatorSourceConsistent)
    }

    @Test
    fun changedStructureRequiresACompleteNewRound() {
        val session = WechatSampleCaptureSession(enabled = true)
        val signatures = listOf("a".repeat(64), "a".repeat(64), "b".repeat(64))

        signatures.forEachIndexed { index, signature ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin("8.0.60", openedAt))
            session.publish(
                sample = sample(signature, openedAt.plusSeconds(1)),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        assertEquals(false, session.state.value.consistent)
        assertTrue(session.state.value.message.contains("不一致"))
        assertNull(session.state.value.pageSignatureSha256)
        assertNull(session.state.value.nodeCount)

        assertTrue(session.begin("8.0.60", baseTime.plusSeconds(40)))
        assertEquals(0, session.state.value.completedSamples)
        assertTrue(session.state.value.awaiting)
        assertNull(session.state.value.pageSignatureSha256)
        assertNull(session.state.value.nodeCount)
    }

    @Test
    fun changedNodeCountCannotProduceARulePreparationIdentifier() {
        val session = WechatSampleCaptureSession(enabled = true)

        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin("8.0.60", openedAt))
            session.publish(
                sample = sample(
                    signature = "a".repeat(64),
                    capturedAt = openedAt.plusSeconds(1),
                    nodeCount = 20 + index,
                ),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        assertEquals(false, session.state.value.consistent)
        assertNull(session.state.value.pageSignatureSha256)
        assertNull(session.state.value.nodeCount)
    }

    @Test
    fun expiredWindowDoesNotCreateASample() {
        val session = WechatSampleCaptureSession(enabled = true)
        assertTrue(session.begin("8.0.60", baseTime))

        session.refresh(baseTime.plusSeconds(6))

        val state = session.state.value
        assertEquals(0, state.completedSamples)
        assertFalse(state.awaiting)
        assertTrue(state.message.contains("五秒内没有取得"))
        assertNull(session.activeRequest("com.tencent.mm", baseTime.plusSeconds(6)))
    }

    @Test
    fun onlyWechatPackageCanUseTheActiveWindow() {
        val session = WechatSampleCaptureSession(enabled = true)
        assertTrue(session.begin("8.0.60", baseTime))

        assertNull(session.activeRequest("com.example.other", baseTime.plusSeconds(1)))
        assertTrue(
            session.activeRequest("com.tencent.mm", baseTime.plusSeconds(1)) != null,
        )
    }

    @Test
    fun changedLocatorSourceNeverProducesPreparationIdentifier() {
        val session = WechatSampleCaptureSession(enabled = true)

        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin("8.0.60", openedAt))
            session.publish(
                sample = sample(
                    signature = "a".repeat(64),
                    capturedAt = openedAt.plusSeconds(1),
                    locatorSources = setOf(('c' + index).toString().repeat(64)),
                ),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        assertEquals(true, session.state.value.consistent)
        assertEquals(false, session.state.value.locatorSourceConsistent)
        assertNull(session.state.value.locatorSourceSha256)
        assertFalse(
            WechatSampleCaptureTarget.CONTACT_PROFILE in
                session.state.value.completedTargets,
        )
    }

    @Test
    fun multipleLocatorSourcesRemainUnresolved() {
        val session = WechatSampleCaptureSession(enabled = true)

        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin("8.0.60", openedAt))
            session.publish(
                sample = sample(
                    signature = "a".repeat(64),
                    capturedAt = openedAt.plusSeconds(1),
                    locatorSources = setOf("c".repeat(64), "d".repeat(64)),
                ),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        assertEquals(false, session.state.value.locatorSourceConsistent)
        assertEquals(2, session.state.value.locatorSourceCount)
        assertNull(session.state.value.locatorSourceSha256)
        assertTrue(session.state.value.message.contains("唯一"))
    }

    @Test
    fun callPageRoundsAreIsolatedAndCompletedResultRemainsAvailable() {
        val session = WechatSampleCaptureSession(enabled = true)
        val target = WechatSampleCaptureTarget.VOICE_CALL_CONFIRMATION

        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin(target, "8.0.60", openedAt))
            session.publish(
                sample = sample(
                    signature = "d".repeat(64),
                    capturedAt = openedAt.plusSeconds(1),
                    locatorSources = emptySet(),
                ),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        val completed = session.state.value.completedTargets.getValue(target)
        assertEquals(WechatPageType.VOICE_CALL_CONFIRMATION, completed.target.pageType)
        assertEquals("d".repeat(64), completed.pageSignatureSha256)
        assertNull(completed.locatorSourceSha256)
        assertTrue(
            session.begin(
                WechatSampleCaptureTarget.VOICE_CALL_ACTIVE,
                "8.0.60",
                baseTime.plusSeconds(40),
            ),
        )
        assertEquals(0, session.state.value.completedSamples)
        assertTrue(target in session.state.value.completedTargets)
        assertEquals(
            WechatSampleCaptureTarget.VOICE_CALL_ACTIVE,
            session.state.value.selectedTarget,
        )
    }

    @Test
    fun restartingACompletedTargetInvalidatesItsPreviousResultImmediately() {
        val session = WechatSampleCaptureSession(enabled = true)
        val target = WechatSampleCaptureTarget.VOICE_CALL_CONFIRMATION
        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(session.begin(target, "8.0.60", openedAt))
            session.publish(
                sample = sample(
                    signature = "d".repeat(64),
                    capturedAt = openedAt.plusSeconds(1),
                    locatorSources = emptySet(),
                ),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }
        assertTrue(target in session.state.value.completedTargets)

        assertTrue(session.begin(target, "8.0.60", baseTime.plusSeconds(40)))

        assertFalse(target in session.state.value.completedTargets)
        assertEquals(0, session.state.value.completedSamples)
        assertTrue(session.state.value.awaiting)
    }

    @Test
    fun wechatVersionChangeClearsAllPageResults() {
        val session = WechatSampleCaptureSession(enabled = true)
        repeat(3) { index ->
            val openedAt = baseTime.plusSeconds(index.toLong() * 10)
            assertTrue(
                session.begin(
                    WechatSampleCaptureTarget.VIDEO_CALL_CONFIRMATION,
                    "8.0.60",
                    openedAt,
                ),
            )
            session.publish(
                sample = sample("e".repeat(64), openedAt.plusSeconds(1), locatorSources = emptySet()),
                wechatVersion = "8.0.60",
                now = openedAt.plusSeconds(1),
            )
        }

        assertFalse(
            session.begin(
                WechatSampleCaptureTarget.VIDEO_CALL_ACTIVE,
                "8.0.61",
                baseTime.plusSeconds(40),
            ),
        )
        assertTrue(session.state.value.completedTargets.isEmpty())
        assertTrue(session.state.value.message.contains("版本发生变化"))
    }

    @Test
    fun completeFiveTargetsBuildOneEscapedDynamicPropertiesBlock() {
        val hashes = listOf('a', 'b', 'c', 'd', 'e')
        val results = WechatSampleCaptureTarget.entries.mapIndexed { index, target ->
            target to WechatSampleCaptureResult(
                target = target,
                wechatVersion = "8.0.76",
                pageSignatureSha256 = hashes[index].toString().repeat(64),
                nodeCount = index + 2,
                locatorSourceSha256 = "f".repeat(64)
                    .takeIf { target.requiresLocatorSource },
            )
        }.toMap()
        val state = WechatSampleCaptureUiState(
            visible = true,
            deviceInfo = WechatSampleCaptureDeviceInfo(
                manufacturer = "Example Manufacturer",
                model = "Pixel 8 Pro",
                androidSdk = 35,
            ),
            wechatVersion = "8.0.76",
            completedTargets = results,
        )

        assertEquals(
            """deviceManufacturer=Example\ Manufacturer
deviceModel=Pixel\ 8\ Pro
androidSdk=35
wechatVersion=8.0.76
contactProfileSha256=${"a".repeat(64)}
voiceCallConfirmationSha256=${"b".repeat(64)}
voiceCallActiveSha256=${"c".repeat(64)}
videoCallConfirmationSha256=${"d".repeat(64)}
videoCallActiveSha256=${"e".repeat(64)}""",
            state.buildWechatRuleInputPropertiesOrNull(),
        )
    }

    @Test
    fun dynamicPropertiesRemainClosedWithoutEveryPageAndUniqueLocator() {
        val deviceInfo = WechatSampleCaptureDeviceInfo("test", "test model", 35)
        val contactTarget = WechatSampleCaptureTarget.CONTACT_PROFILE
        val incomplete = WechatSampleCaptureUiState(
            visible = true,
            deviceInfo = deviceInfo,
            completedTargets = mapOf(
                contactTarget to WechatSampleCaptureResult(
                    target = contactTarget,
                    wechatVersion = "8.0.76",
                    pageSignatureSha256 = "a".repeat(64),
                    nodeCount = 2,
                    locatorSourceSha256 = null,
                ),
            ),
        )

        assertNull(incomplete.buildWechatRuleInputPropertiesOrNull())
    }

    private fun sample(
        signature: String,
        capturedAt: OffsetDateTime,
        nodeCount: Int = 20,
        locatorSources: Set<String> = setOf("c".repeat(64)),
    ) =
        WechatPageStructureSample(
            signatureSha256 = signature,
            nodeCount = nodeCount,
            capturedAt = capturedAt,
            locatorSourceSha256s = locatorSources,
        )
}
