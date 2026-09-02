package com.aifriend.core.voice

import android.content.Context
import com.aifriend.BuildConfig
import com.google.crypto.tink.subtle.Ed25519Verify
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android 本机可用的方言声学参数注册表。
 *
 * 清单签名、校准文件摘要、固定方言、信任密钥或 App 版本任一不匹配时，
 * 正式能力保持关闭；个人 Debug 基础体验可单独提供非正式模板参数。
 *
 * @author codex
 * @since 2026-08-13
 */
fun interface DialectPackageRegistry {
    fun activePackage(): VerifiedDialectPackage?

    /** 只有真实验签包可声明正式方言能力。 */
    fun formalPackageAvailable(): Boolean = activePackage() != null
}

/** 已验签且与当前 Android 版本兼容的方言包。 */
data class VerifiedDialectPackage(
    val manifest: DialectPackageManifest,
    val calibration: DialectAcousticCalibration,
)

/** Ed25519 签名覆盖的方言包清单。 */
@Serializable
data class DialectPackageManifest(
    val dialectCode: String,
    val packageVersion: String,
    val acousticEngine: String,
    val acousticModelVersion: String,
    val thresholdVersion: String,
    val primaryAsrModelVersion: String,
    val primaryAsrModelSha256: String,
    val mandarinAssistVersion: String,
    val mandarinAssistSha256: String,
    val fusionRuleVersion: String,
    val alignmentVersion: String,
    val minimumServerVersion: String,
    val minimumAndroidAppVersion: String,
    val calibrationFile: String,
    val calibrationSha256: String,
    val signatureKeyId: String,
    val issuedAt: String,
)

/** 只含发音内容比对参数、不含任何说话人身份阈值的校准数据。 */
@Serializable
data class DialectAcousticCalibration(
    val acousticEngine: String,
    val sampleRateHz: Int,
    val frameLengthMs: Int,
    val frameShiftMs: Int,
    val melFilterCount: Int,
    val coefficientCount: Int,
    val minimumDurationMs: Int,
    val maximumDurationMs: Int,
    val minimumPeakDbfs: Double,
    val vadRelativeFloorDb: Double,
    val minimumActiveFrameRatio: Double,
    val maximumClippedSampleRatio: Double,
    val dtwWindowRatio: Double,
    val enrollmentConsistencyMaxDistance: Double,
    val uniquenessConflictMaxDistance: Double,
    val uniquenessDistinctMinDistance: Double,
    val taskAliasUniqueMaxDistance: Double,
    val taskAliasCandidateMaxDistance: Double,
    val taskAliasMinimumMargin: Double,
)

/**
 * 从随 APK 发布的固定 assets 目录加载方言包，并在构造时完成一次严格校验。
 *
 * 正式信任公钥和签名包未配置时保持关闭；本实现不联网下载、不热更新。
 */
@Singleton
class AndroidSignedDialectPackageRegistry @Inject constructor(
    @ApplicationContext context: Context,
) : DialectPackageRegistry {

    private val signedPackage: VerifiedDialectPackage? = runCatching {
        if (!BuildConfig.DIALECT_PACKAGE_ENABLED) return@runCatching null
        SignedDialectPackageVerifier.verify(
            source = AssetDialectPackageSource(context, BuildConfig.DIALECT_PACKAGE_ROOT),
            trustedPublicKeyBase64 = BuildConfig.DIALECT_TRUSTED_PUBLIC_KEY_BASE64,
            trustedKeyId = BuildConfig.DIALECT_TRUSTED_KEY_ID,
            requiredDialectCode = REQUIRED_DIALECT_CODE,
            currentAndroidVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
        )
    }.getOrNull()

    private val activePackage = signedPackage ?: BasicExperienceDialectPackage.takeIfEnabled()

    override fun activePackage(): VerifiedDialectPackage? = activePackage

    override fun formalPackageAvailable(): Boolean = signedPackage != null

    private companion object {
        const val REQUIRED_DIALECT_CODE = "zh-Hans-CN-x-wugang"
    }
}

/**
 * 个人测试阶段使用的固定本机声学参数。
 *
 * 该对象只支持称呼和安全指令的个人发音内容模板，不是签名武冈话模型，
 * 也不包含训练语料或说话人身份能力。
 */
object BasicExperienceDialectPackage {
    const val DIALECT_CODE = "zh-Hans-CN-x-wugang"
    const val PACKAGE_VERSION = "basic-experience-v1"
    const val ACOUSTIC_MODEL_VERSION = "mfcc-dtw-basic-v1"
    const val THRESHOLD_VERSION = "basic-personal-v2"
    const val ASR_MODEL_VERSION = "vosk-model-small-cn-0.22"
    const val ASR_ARCHIVE_SHA256 =
        "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    const val FUSION_RULE_VERSION = "basic-local-direct-v1"
    const val ALIGNMENT_VERSION = "vosk-word-timestamp-v1"

    fun create(): VerifiedDialectPackage = VerifiedDialectPackage(
        manifest = DialectPackageManifest(
            dialectCode = DIALECT_CODE,
            packageVersion = PACKAGE_VERSION,
            acousticEngine = "MFCC_DTW_V1",
            acousticModelVersion = ACOUSTIC_MODEL_VERSION,
            thresholdVersion = THRESHOLD_VERSION,
            primaryAsrModelVersion = ASR_MODEL_VERSION,
            primaryAsrModelSha256 = ASR_ARCHIVE_SHA256,
            mandarinAssistVersion = ASR_MODEL_VERSION,
            mandarinAssistSha256 = ASR_ARCHIVE_SHA256,
            fusionRuleVersion = FUSION_RULE_VERSION,
            alignmentVersion = ALIGNMENT_VERSION,
            minimumServerVersion = "1.0.0",
            minimumAndroidAppVersion = "0.0.1",
            calibrationFile = "built-in-basic-calibration",
            calibrationSha256 = "0".repeat(64),
            signatureKeyId = "basic-experience",
            issuedAt = "2026-08-28T00:00:00Z",
        ),
        calibration = DialectAcousticCalibration(
            acousticEngine = "MFCC_DTW_V1",
            sampleRateHz = 16_000,
            frameLengthMs = 25,
            frameShiftMs = 10,
            melFilterCount = 26,
            coefficientCount = 13,
            minimumDurationMs = 300,
            maximumDurationMs = 5_000,
            minimumPeakDbfs = -60.0,
            vadRelativeFloorDb = 35.0,
            minimumActiveFrameRatio = 0.20,
            maximumClippedSampleRatio = 0.01,
            dtwWindowRatio = 0.20,
            enrollmentConsistencyMaxDistance = 1.0,
            uniquenessConflictMaxDistance = 0.2,
            uniquenessDistinctMinDistance = 1.5,
            taskAliasUniqueMaxDistance = 0.5,
            taskAliasCandidateMaxDistance = 1.5,
            taskAliasMinimumMargin = 0.2,
        ),
    )

    internal fun takeIfEnabled(): VerifiedDialectPackage? =
        if (BuildConfig.BASIC_EXPERIENCE_ENABLED) create() else null
}

/** 有界读取方言包资源，测试可用内存实现替代 assets。 */
fun interface DialectPackageSource {
    fun read(fileName: String, maximumBytes: Int): ByteArray
}

private class AssetDialectPackageSource(
    private val context: Context,
    private val root: String,
) : DialectPackageSource {
    override fun read(fileName: String, maximumBytes: Int): ByteArray {
        require(fileName.matches(SAFE_FILE_NAME)) { "PACKAGE_FILE_INVALID" }
        val path = "${root.trimEnd('/')}/$fileName"
        return context.assets.open(path).use { input ->
            val output = ArrayList<Byte>(maximumBytes.coerceAtMost(8_192))
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size + count <= maximumBytes) { "PACKAGE_RESOURCE_TOO_LARGE" }
                repeat(count) { output += buffer[it] }
            }
            require(output.isNotEmpty()) { "PACKAGE_RESOURCE_EMPTY" }
            ByteArray(output.size) { output[it] }
        }
    }

    private companion object {
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
    }
}

/** 纯 JVM 方言包验签器，便于对签名、摘要和版本边界做单元测试。 */
object SignedDialectPackageVerifier {
    private const val MANIFEST_FILE = "manifest.json"
    private const val SIGNATURE_FILE = "manifest.sig"
    private const val SUPPORTED_ENGINE = "MFCC_DTW_V1"
    private val token = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,59}")
    private val safeFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
    private val sha256Hex = Regex("[0-9a-f]{64}")
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun verify(
        source: DialectPackageSource,
        trustedPublicKeyBase64: String,
        trustedKeyId: String,
        requiredDialectCode: String,
        currentAndroidVersion: String,
    ): VerifiedDialectPackage {
        require(trustedPublicKeyBase64.isNotBlank() && trustedKeyId.matches(token)) {
            "TRUST_CONFIGURATION_INVALID"
        }
        val manifestBytes = source.read(MANIFEST_FILE, 65_536)
        val signatureBytes = Base64.getDecoder().decode(
            source.read(SIGNATURE_FILE, 512).toString(Charsets.US_ASCII).trim(),
        )
        val publicKey = extractRawEd25519PublicKey(
            Base64.getDecoder().decode(trustedPublicKeyBase64),
        )
        runCatching {
            Ed25519Verify(publicKey).verify(signatureBytes, manifestBytes)
        }.getOrElse { throw IllegalArgumentException("MANIFEST_SIGNATURE_INVALID", it) }

        val manifest = strictJson.decodeFromString<DialectPackageManifest>(
            manifestBytes.toString(Charsets.UTF_8),
        )
        validateManifest(
            manifest,
            trustedKeyId,
            requiredDialectCode,
            currentAndroidVersion,
        )
        val calibrationBytes = source.read(manifest.calibrationFile, 65_536)
        val digest = MessageDigest.getInstance("SHA-256").digest(calibrationBytes).toHex()
        require(MessageDigest.isEqual(digest.encodeToByteArray(), manifest.calibrationSha256.encodeToByteArray())) {
            "CALIBRATION_HASH_INVALID"
        }
        val calibration = strictJson.decodeFromString<DialectAcousticCalibration>(
            calibrationBytes.toString(Charsets.UTF_8),
        )
        validateCalibration(calibration, manifest)
        return VerifiedDialectPackage(manifest, calibration)
    }

    private fun validateManifest(
        manifest: DialectPackageManifest,
        trustedKeyId: String,
        requiredDialectCode: String,
        currentAndroidVersion: String,
    ) {
        val requiredTokens = listOf(
            manifest.dialectCode,
            manifest.packageVersion,
            manifest.acousticEngine,
            manifest.acousticModelVersion,
            manifest.thresholdVersion,
            manifest.primaryAsrModelVersion,
            manifest.mandarinAssistVersion,
            manifest.fusionRuleVersion,
            manifest.alignmentVersion,
            manifest.signatureKeyId,
        )
        require(requiredTokens.all { it.matches(token) }) { "MANIFEST_TOKEN_INVALID" }
        require(
            manifest.dialectCode == requiredDialectCode &&
                manifest.signatureKeyId == trustedKeyId &&
                manifest.acousticEngine == SUPPORTED_ENGINE,
        ) { "MANIFEST_COMPATIBILITY_INVALID" }
        require(
            manifest.calibrationFile.matches(safeFileName) &&
                manifest.calibrationFile !in setOf(MANIFEST_FILE, SIGNATURE_FILE) &&
                manifest.calibrationSha256.matches(sha256Hex) &&
                manifest.primaryAsrModelSha256.matches(sha256Hex) &&
                manifest.mandarinAssistSha256.matches(sha256Hex),
        ) { "CALIBRATION_REFERENCE_INVALID" }
        require(compareVersion(currentAndroidVersion, manifest.minimumAndroidAppVersion) >= 0) {
            "ANDROID_VERSION_INCOMPATIBLE"
        }
        semanticVersion(manifest.minimumServerVersion)
        Instant.parse(manifest.issuedAt)
    }

    private fun validateCalibration(
        calibration: DialectAcousticCalibration,
        manifest: DialectPackageManifest,
    ) {
        require(
            calibration.acousticEngine == manifest.acousticEngine &&
                calibration.sampleRateHz == 16_000 &&
                calibration.frameLengthMs in 20..40 &&
                calibration.frameShiftMs in 5..20 &&
                calibration.frameShiftMs < calibration.frameLengthMs &&
                calibration.melFilterCount in 20..40 &&
                calibration.coefficientCount in 10..20 &&
                calibration.coefficientCount <= calibration.melFilterCount &&
                calibration.minimumDurationMs in 300..2_000 &&
                calibration.maximumDurationMs in calibration.minimumDurationMs..5_000 &&
                calibration.minimumPeakDbfs.finiteWithin(-80.0, -3.0) &&
                calibration.vadRelativeFloorDb.finiteWithin(5.0, 80.0) &&
                calibration.minimumActiveFrameRatio.finiteWithin(0.000_001, 0.999_999) &&
                calibration.maximumClippedSampleRatio.finiteWithin(0.0, 0.1) &&
                calibration.dtwWindowRatio.finiteWithin(0.05, 1.0) &&
                calibration.enrollmentConsistencyMaxDistance.finiteWithin(0.0, 1_000.0) &&
                calibration.uniquenessConflictMaxDistance.finiteWithin(0.0, 1_000.0) &&
                calibration.uniquenessDistinctMinDistance.finiteWithin(0.0, 1_000.0) &&
                calibration.taskAliasUniqueMaxDistance.finiteWithin(0.0, 1_000.0) &&
                calibration.taskAliasCandidateMaxDistance.finiteWithin(0.0, 1_000.0) &&
                calibration.taskAliasMinimumMargin.finiteWithin(0.000_001, 1_000.0) &&
                calibration.uniquenessConflictMaxDistance < calibration.uniquenessDistinctMinDistance &&
                calibration.taskAliasUniqueMaxDistance < calibration.taskAliasCandidateMaxDistance,
        ) { "CALIBRATION_VALUES_INVALID" }
    }

    private fun compareVersion(left: String, right: String): Int {
        val leftParts = semanticVersion(left)
        val rightParts = semanticVersion(right)
        for (index in leftParts.indices) {
            val compared = leftParts[index].compareTo(rightParts[index])
            if (compared != 0) return compared
        }
        return 0
    }

    private fun semanticVersion(value: String): List<Int> {
        require(value.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "VERSION_INVALID" }
        return value.split('.').map(String::toInt)
    }

    /**
     * 正式配置沿用标准 X.509 SubjectPublicKeyInfo；Tink 使用末尾 32 字节原始公钥，
     * 因此必须先精确核对 Ed25519 DER 前缀，不能截取任意输入。
     */
    private fun extractRawEd25519PublicKey(encoded: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        require(encoded.size == prefix.size + Ed25519Verify.PUBLIC_KEY_LEN) {
            "TRUSTED_KEY_ENCODING_INVALID"
        }
        require(encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "TRUSTED_KEY_ENCODING_INVALID"
        }
        return encoded.copyOfRange(prefix.size, encoded.size)
    }

    private fun Double.finiteWithin(minimum: Double, maximum: Double): Boolean =
        isFinite() && this in minimum..maximum

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
