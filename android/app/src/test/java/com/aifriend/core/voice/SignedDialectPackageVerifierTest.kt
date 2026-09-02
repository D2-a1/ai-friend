package com.aifriend.core.voice

import com.aifriend.core.audio.WavPcmCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 签名方言包与本机发音内容模板的失败关闭测试。 */
class SignedDialectPackageVerifierTest {

    @Test
    fun validTestPackageVerifiesAndProducesVersionedContentTemplate() {
        val fixture = signedFixture()
        val verified = SignedDialectPackageVerifier.verify(
            source = fixture.source,
            trustedPublicKeyBase64 = fixture.publicKey,
            trustedKeyId = "test-key-1",
            requiredDialectCode = "zh-Hans-CN-x-wugang",
            currentAndroidVersion = "0.0.1",
        )
        val engine = MfccDtwLocalVoiceTemplateEngine { verified }
        val candidate = engine.enroll(sineWav(1_200, 7_000), sineWav(1_250, 7_500))

        assertEquals("test-package-1", candidate.dialectPackageVersion)
        assertEquals(0x41494654, ByteBuffer.wrap(candidate.material).int)
        candidate.clear()
        assertTrue(candidate.material.all { it == 0.toByte() })
    }

    @Test
    fun changedCalibrationFailsSignatureBoundHashCheck() {
        val fixture = signedFixture()
        fixture.files["calibration.json"] = "{}".encodeToByteArray()

        val failure = runCatching {
            SignedDialectPackageVerifier.verify(
                source = fixture.source,
                trustedPublicKeyBase64 = fixture.publicKey,
                trustedKeyId = "test-key-1",
                requiredDialectCode = "zh-Hans-CN-x-wugang",
                currentAndroidVersion = "0.0.1",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("CALIBRATION_HASH_INVALID"))
    }

    @Test
    fun unavailableFormalPackageFailsBeforeTemplateGeneration() {
        val engine = MfccDtwLocalVoiceTemplateEngine { null }
        val failure = runCatching {
            engine.enroll(sineWav(1_200, 7_000), sineWav(1_200, 7_000))
        }.exceptionOrNull()

        assertTrue(failure is LocalVoiceTemplateException)
    }

    private fun signedFixture(): Fixture {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val calibration = DialectAcousticCalibration(
            acousticEngine = "MFCC_DTW_V1",
            sampleRateHz = 16_000,
            frameLengthMs = 25,
            frameShiftMs = 10,
            melFilterCount = 26,
            coefficientCount = 13,
            minimumDurationMs = 300,
            maximumDurationMs = 5_000,
            minimumPeakDbfs = -80.0,
            vadRelativeFloorDb = 80.0,
            minimumActiveFrameRatio = 0.01,
            maximumClippedSampleRatio = 0.1,
            dtwWindowRatio = 0.2,
            enrollmentConsistencyMaxDistance = 1_000.0,
            uniquenessConflictMaxDistance = 0.1,
            uniquenessDistinctMinDistance = 10.0,
            taskAliasUniqueMaxDistance = 0.5,
            taskAliasCandidateMaxDistance = 10.0,
            taskAliasMinimumMargin = 0.2,
        )
        val calibrationBytes = json.encodeToString(calibration).encodeToByteArray()
        val calibrationHash = MessageDigest.getInstance("SHA-256")
            .digest(calibrationBytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = DialectPackageManifest(
            dialectCode = "zh-Hans-CN-x-wugang",
            packageVersion = "test-package-1",
            acousticEngine = "MFCC_DTW_V1",
            acousticModelVersion = "test-model-1",
            thresholdVersion = "test-threshold-1",
            primaryAsrModelVersion = "reserved-disabled",
            primaryAsrModelSha256 = "1".repeat(64),
            mandarinAssistVersion = "reserved-disabled",
            mandarinAssistSha256 = "2".repeat(64),
            fusionRuleVersion = "reserved-disabled",
            alignmentVersion = "reserved-disabled",
            minimumServerVersion = "0.0.1",
            minimumAndroidAppVersion = "0.0.1",
            calibrationFile = "calibration.json",
            calibrationSha256 = calibrationHash,
            signatureKeyId = "test-key-1",
            issuedAt = Instant.parse("2026-08-13T00:00:00Z").toString(),
        )
        val manifestBytes = json.encodeToString(manifest).encodeToByteArray()
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(manifestBytes)
        val files = mutableMapOf(
            "manifest.json" to manifestBytes,
            "manifest.sig" to Base64.getEncoder().encode(signer.sign()),
            "calibration.json" to calibrationBytes,
        )
        val source = DialectPackageSource { name, maximumBytes ->
            requireNotNull(files[name]).also { require(it.size <= maximumBytes) }.copyOf()
        }
        return Fixture(
            files = files,
            source = source,
            publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
    }

    private fun sineWav(durationMs: Int, amplitude: Int): ByteArray {
        val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
            (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                .toInt()
                .toShort()
        }
        val pcm = ByteArray(samples.size * 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        samples.fill(0)
        return WavPcmCodec.encodeMono16(pcm).also { pcm.fill(0) }
    }

    private data class Fixture(
        val files: MutableMap<String, ByteArray>,
        val source: DialectPackageSource,
        val publicKey: String,
    )
}
