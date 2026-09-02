package com.aifriend.feature.wechat

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.aifriend.BuildConfig
import com.aifriend.contract.model.WechatActionType
import com.google.crypto.tink.subtle.Ed25519Verify
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 提供当前安装环境唯一通过签名规则包和能力矩阵校验的微信能力快照。 */
fun interface WechatRulePackageRegistry {
    fun currentCapability(): WechatCapabilitySnapshot?
}

/** Ed25519 签名覆盖的微信规则包清单。 */
@Serializable
data class WechatRulePackageManifest(
    val packageVersion: String,
    val schemaVersion: String,
    val capabilityFile: String,
    val capabilitySha256: String,
    val signatureKeyId: String,
    val issuedAt: String,
)

/** 只含已验收版本组合和摘要的能力矩阵，不允许包含选择器、坐标或页面文字。 */
@Serializable
data class WechatCapabilityMatrix(
    val schemaVersion: String,
    val combinations: List<WechatCapabilityCombination>,
)

/** 单个已完成真机验收的 App、设备、微信与规则组合。 */
@Serializable
data class WechatCapabilityCombination(
    val applicationId: String,
    val appVersion: String,
    val appBuildSha256: String,
    val signingCertificateSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val minimumAndroidSdk: Int,
    val maximumAndroidSdk: Int,
    val packageName: String,
    val wechatVersion: String,
    val ruleVersion: String,
    val locatorVersion: String,
    val compatibleMinimumRuleVersions: List<String>,
    val allowedActions: List<String>,
    val allowedPageTypes: Map<String, List<String>>,
    val allowedPageSignatures: Map<String, List<String>>,
    val allowedPageSignaturesByType: Map<String, Map<String, List<String>>> = emptyMap(),
)

/** 当前安装包、签名证书、设备和微信客户端的最小版本事实。 */
data class WechatRuntimeFacts(
    val applicationId: String,
    val appVersion: String,
    val appBuildSha256: String,
    val signingCertificateSha256: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidSdkInt: Int,
    val packageName: String,
    val wechatVersion: String,
)

/** 从 APK 固定 assets 加载规则包；正式配置缺失或任一校验失败时返回空。 */
@Singleton
class AndroidSignedWechatRulePackageRegistry @Inject constructor(
    @ApplicationContext context: Context,
) : WechatRulePackageRegistry {

    private val verifiedCapability: WechatCapabilitySnapshot? = runCatching {
        if (!BuildConfig.WECHAT_RULE_PACKAGE_ENABLED) return@runCatching null
        val runtimeFacts = AndroidWechatRuntimeFacts.read(
            context,
            BuildConfig.WECHAT_APP_BUILD_SHA256,
        ) ?: return@runCatching null
        SignedWechatRulePackageVerifier.verify(
            source = AssetWechatRulePackageSource(context, BuildConfig.WECHAT_RULE_PACKAGE_ROOT),
            trustedPublicKeyBase64 = BuildConfig.WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64,
            trustedKeyId = BuildConfig.WECHAT_RULE_TRUSTED_KEY_ID,
            runtimeFacts = runtimeFacts,
        )
    }.getOrNull()

    override fun currentCapability(): WechatCapabilitySnapshot? = verifiedCapability
}

/** 有界读取微信规则包资源，测试使用内存源替代。 */
fun interface WechatRulePackageSource {
    fun read(fileName: String, maximumBytes: Int): ByteArray
}

private class AssetWechatRulePackageSource(
    private val context: Context,
    private val root: String,
) : WechatRulePackageSource {
    override fun read(fileName: String, maximumBytes: Int): ByteArray {
        require(fileName.matches(SAFE_FILE_NAME)) { "RULE_FILE_INVALID" }
        val path = "${root.trimEnd('/')}/$fileName"
        return context.assets.open(path).use { input ->
            val output = ByteArrayOutputStream(maximumBytes.coerceAtMost(8_192))
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maximumBytes) { "RULE_RESOURCE_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()) { "RULE_RESOURCE_EMPTY" } }
        }
    }

    private companion object {
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
    }
}

/** 读取系统包管理器的只读版本与签名事实；不访问无障碍节点或微信内容。 */
private object AndroidWechatRuntimeFacts {
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    fun read(context: Context, appBuildSha256: String): WechatRuntimeFacts? = runCatching {
        require(appBuildSha256.matches(SHA256_HEX))
        val appInfo = packageInfo(context.packageManager, context.packageName, withSigning = true)
        val signingInfo = requireNotNull(appInfo.signingInfo)
        val currentSigners = signingInfo.apkContentsSigners
        require(currentSigners.size == 1)
        val signingDigest = MessageDigest.getInstance("SHA-256")
            .digest(currentSigners.single().toByteArray())
            .toHex()
        val wechatInfo = packageInfo(context.packageManager, WECHAT_PACKAGE, withSigning = false)
        WechatRuntimeFacts(
            applicationId = context.packageName,
            appVersion = requireNotNull(appInfo.versionName),
            appBuildSha256 = appBuildSha256,
            signingCertificateSha256 = signingDigest,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidSdkInt = Build.VERSION.SDK_INT,
            packageName = WECHAT_PACKAGE,
            wechatVersion = requireNotNull(wechatInfo.versionName),
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(
        packageManager: PackageManager,
        packageName: String,
        withSigning: Boolean,
    ): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val flags = if (withSigning) PackageManager.GET_SIGNING_CERTIFICATES.toLong() else 0L
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags))
    } else {
        val flags = if (withSigning) PackageManager.GET_SIGNING_CERTIFICATES else 0
        packageManager.getPackageInfo(packageName, flags)
    }

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
}

/** 纯 JVM 签名规则包与能力矩阵验证器。 */
object SignedWechatRulePackageVerifier {
    private const val MANIFEST_FILE = "manifest.json"
    private const val SIGNATURE_FILE = "manifest.sig"
    private const val SUPPORTED_SCHEMA = "WECHAT_CAPABILITY_MATRIX_V1"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val MAXIMUM_COMBINATIONS = 64
    private val safeFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")
    private val token = Regex("[^\\s:]{1,100}")
    private val keyId = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}")
    private val sha256Hex = Regex("[0-9a-f]{64}")
    private val applicationId = Regex("[A-Za-z][A-Za-z0-9_.]{1,99}")
    private val strictJson = Json { ignoreUnknownKeys = false; explicitNulls = true }

    fun verify(
        source: WechatRulePackageSource,
        trustedPublicKeyBase64: String,
        trustedKeyId: String,
        runtimeFacts: WechatRuntimeFacts,
    ): WechatCapabilitySnapshot {
        require(trustedPublicKeyBase64.isNotBlank() && trustedKeyId.matches(keyId)) {
            "RULE_TRUST_CONFIGURATION_INVALID"
        }
        validateRuntimeFacts(runtimeFacts)
        val manifestBytes = source.read(MANIFEST_FILE, 65_536)
        val signatureBytes = Base64.getDecoder().decode(
            source.read(SIGNATURE_FILE, 512).toString(Charsets.US_ASCII).trim(),
        )
        require(signatureBytes.size == 64) { "RULE_SIGNATURE_INVALID" }
        val publicKey = extractRawEd25519PublicKey(
            Base64.getDecoder().decode(trustedPublicKeyBase64),
        )
        runCatching { Ed25519Verify(publicKey).verify(signatureBytes, manifestBytes) }
            .getOrElse { throw IllegalArgumentException("RULE_SIGNATURE_INVALID", it) }

        val manifest = strictJson.decodeFromString<WechatRulePackageManifest>(
            manifestBytes.toString(Charsets.UTF_8),
        )
        validateManifest(manifest, trustedKeyId)
        val capabilityBytes = source.read(manifest.capabilityFile, 262_144)
        val actualHash = MessageDigest.getInstance("SHA-256").digest(capabilityBytes).toHex()
        require(MessageDigest.isEqual(
            actualHash.encodeToByteArray(),
            manifest.capabilitySha256.encodeToByteArray(),
        )) { "CAPABILITY_HASH_INVALID" }
        val matrix = strictJson.decodeFromString<WechatCapabilityMatrix>(
            capabilityBytes.toString(Charsets.UTF_8),
        )
        require(matrix.schemaVersion == manifest.schemaVersion) { "CAPABILITY_SCHEMA_INVALID" }
        require(matrix.combinations.isNotEmpty() && matrix.combinations.size <= MAXIMUM_COMBINATIONS) {
            "CAPABILITY_COUNT_INVALID"
        }
        matrix.combinations.forEach(::validateCombination)
        val matches = matrix.combinations.filter { it.matches(runtimeFacts) }
        require(matches.size == 1) { "CAPABILITY_COMBINATION_NOT_UNIQUE" }
        return matches.single().toSnapshot(runtimeFacts)
    }

    private fun validateRuntimeFacts(runtimeFacts: WechatRuntimeFacts) {
        require(runtimeFacts.applicationId.matches(applicationId))
        require(runtimeFacts.appVersion.matches(token))
        require(runtimeFacts.appBuildSha256.matches(sha256Hex))
        require(runtimeFacts.signingCertificateSha256.matches(sha256Hex))
        require(runtimeFacts.deviceManufacturer.isSafeDeviceFact())
        require(runtimeFacts.deviceModel.isSafeDeviceFact())
        require(runtimeFacts.androidSdkInt >= 29)
        require(runtimeFacts.packageName == WECHAT_PACKAGE)
        require(runtimeFacts.wechatVersion.matches(token))
    }

    private fun validateManifest(manifest: WechatRulePackageManifest, trustedKeyId: String) {
        require(manifest.packageVersion.matches(token)) { "RULE_MANIFEST_TOKEN_INVALID" }
        require(manifest.schemaVersion == SUPPORTED_SCHEMA) { "RULE_SCHEMA_UNSUPPORTED" }
        require(manifest.signatureKeyId == trustedKeyId) { "RULE_KEY_MISMATCH" }
        require(
            manifest.capabilityFile.matches(safeFileName) &&
                manifest.capabilityFile !in setOf(MANIFEST_FILE, SIGNATURE_FILE) &&
                manifest.capabilitySha256.matches(sha256Hex),
        ) { "CAPABILITY_REFERENCE_INVALID" }
        Instant.parse(manifest.issuedAt)
    }

    private fun validateCombination(combination: WechatCapabilityCombination) {
        val actions = combination.allowedActions.map(::parseAction)
        require(actions.isNotEmpty() && actions.size == actions.distinct().size) {
            "CAPABILITY_ACTIONS_INVALID"
        }
        val actionNames = actions.map { it.value }.toSet()
        require(combination.allowedPageTypes.keys == actionNames)
        require(combination.allowedPageSignatures.keys == actionNames)
        require(
            combination.allowedPageSignaturesByType.isEmpty() ||
                combination.allowedPageSignaturesByType.keys == actionNames,
        )
        require(combination.applicationId.matches(applicationId))
        require(combination.appVersion.matches(token))
        require(combination.appBuildSha256.matches(sha256Hex))
        require(combination.signingCertificateSha256.matches(sha256Hex))
        require(combination.deviceManufacturer.isSafeDeviceFact())
        require(combination.deviceModel.isSafeDeviceFact())
        require(combination.minimumAndroidSdk >= 29)
        require(combination.maximumAndroidSdk in combination.minimumAndroidSdk..100)
        require(combination.packageName == WECHAT_PACKAGE)
        require(combination.wechatVersion.matches(token))
        require(combination.ruleVersion.matches(token))
        require(combination.locatorVersion.matches(token))
        require(
            combination.compatibleMinimumRuleVersions.isNotEmpty() &&
                combination.compatibleMinimumRuleVersions.size <= 16 &&
                combination.compatibleMinimumRuleVersions.distinct().size ==
                combination.compatibleMinimumRuleVersions.size &&
                combination.compatibleMinimumRuleVersions.all(token::matches),
        )
        actionNames.forEach { actionName ->
            val pageTypes = combination.allowedPageTypes.getValue(actionName)
            val signatures = combination.allowedPageSignatures.getValue(actionName)
            require(pageTypes.isNotEmpty() && pageTypes.size <= 8)
            require(pageTypes.distinct().size == pageTypes.size)
            require(pageTypes.all { parsePageType(it) != WechatPageType.UNSUPPORTED })
            require(signatures.isNotEmpty() && signatures.size <= 16)
            require(signatures.distinct().size == signatures.size)
            require(signatures.all(sha256Hex::matches))
            val pageSpecific = combination.allowedPageSignaturesByType[actionName]
            if (pageSpecific == null) {
                require(pageTypes.size == 1) { "CAPABILITY_PAGE_SIGNATURE_MAPPING_REQUIRED" }
            } else {
                require(pageSpecific.keys == pageTypes.toSet())
                require(pageSpecific.values.all { values ->
                    values.isNotEmpty() && values.size <= 16 &&
                        values.distinct().size == values.size &&
                        values.all(sha256Hex::matches)
                })
                require(pageSpecific.values.flatten().toSet() == signatures.toSet())
            }
        }
    }

    private fun WechatCapabilityCombination.matches(runtime: WechatRuntimeFacts): Boolean =
        applicationId == runtime.applicationId &&
            appVersion == runtime.appVersion &&
            appBuildSha256 == runtime.appBuildSha256 &&
            signingCertificateSha256 == runtime.signingCertificateSha256 &&
            deviceManufacturer == runtime.deviceManufacturer &&
            deviceModel == runtime.deviceModel &&
            runtime.androidSdkInt in minimumAndroidSdk..maximumAndroidSdk &&
            packageName == runtime.packageName &&
            wechatVersion == runtime.wechatVersion

    private fun WechatCapabilityCombination.toSnapshot(
        runtimeFacts: WechatRuntimeFacts,
    ): WechatCapabilitySnapshot {
        val actions = allowedActions.associateBy(::parseAction)
        return WechatCapabilitySnapshot(
            remotelyEnabled = true,
            signedRulesTrusted = true,
            combinationApproved = true,
            packageName = packageName,
            wechatVersion = wechatVersion,
            ruleVersion = ruleVersion,
            locatorVersion = locatorVersion,
            compatibleMinimumRuleVersions = compatibleMinimumRuleVersions.toSet(),
            allowedActions = actions.keys,
            allowedPageTypes = actions.keys.associateWith { action ->
                allowedPageTypes.getValue(action.value).map(::parsePageType).toSet()
            },
            allowedPageSignatures = actions.keys.associateWith { action ->
                allowedPageSignatures.getValue(action.value).toSet()
            },
            appBuildSha256 = appBuildSha256,
            signingCertificateSha256 = signingCertificateSha256,
            deviceManufacturer = deviceManufacturer,
            deviceModel = deviceModel,
            androidSdkInt = runtimeFacts.androidSdkInt,
            allowedPageSignaturesByType = actions.keys.associateWith { action ->
                val pageTypes = allowedPageTypes.getValue(action.value).map(::parsePageType)
                val pageSpecific = allowedPageSignaturesByType[action.value]
                if (pageSpecific == null) {
                    mapOf(
                        pageTypes.single() to
                            allowedPageSignatures.getValue(action.value).toSet(),
                    )
                } else {
                    pageSpecific.entries.associate { (pageType, signatures) ->
                        parsePageType(pageType) to signatures.toSet()
                    }
                }
            },
        )
    }

    private fun parseAction(value: String): WechatActionType =
        WechatActionType.entries.singleOrNull { it.value == value }
            ?: throw IllegalArgumentException("CAPABILITY_ACTION_INVALID")

    private fun String.isSafeDeviceFact(): Boolean =
        isNotBlank() && this == trim() && length <= 100 &&
            none { character ->
                Character.isISOControl(character) ||
                    Character.getType(character) == Character.FORMAT.toInt()
            }

    private fun parsePageType(value: String): WechatPageType = runCatching {
        WechatPageType.valueOf(value)
    }.getOrElse { throw IllegalArgumentException("CAPABILITY_PAGE_TYPE_INVALID", it) }

    private fun extractRawEd25519PublicKey(encoded: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        require(encoded.size == prefix.size + Ed25519Verify.PUBLIC_KEY_LEN)
        require(encoded.copyOfRange(0, prefix.size).contentEquals(prefix))
        return encoded.copyOfRange(prefix.size, encoded.size)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
