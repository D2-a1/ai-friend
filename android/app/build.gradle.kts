import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.KeyStore
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.openapi.generator)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

val releaseSigningEnvironmentNames = listOf(
    "AI_FRIEND_ANDROID_RELEASE_STORE_FILE",
    "AI_FRIEND_ANDROID_RELEASE_STORE_TYPE",
    "AI_FRIEND_ANDROID_RELEASE_STORE_PASSWORD",
    "AI_FRIEND_ANDROID_RELEASE_KEY_ALIAS",
    "AI_FRIEND_ANDROID_RELEASE_KEY_PASSWORD",
)
val releaseSigningEnvironment = releaseSigningEnvironmentNames.associateWith { name ->
    providers.environmentVariable(name).orNull?.takeIf { value -> value.isNotBlank() }
}
val missingReleaseSigningEnvironmentNames = releaseSigningEnvironment
    .filterValues { value -> value == null }
    .keys
val releaseSigningConfigured = missingReleaseSigningEnvironmentNames.isEmpty()
val releaseStoreFilePath = releaseSigningEnvironment["AI_FRIEND_ANDROID_RELEASE_STORE_FILE"]
val releaseStoreType = releaseSigningEnvironment["AI_FRIEND_ANDROID_RELEASE_STORE_TYPE"]
val releaseApiBaseUrlEnvironmentName = "AI_FRIEND_ANDROID_RELEASE_API_BASE_URL"
val releaseApiBaseUrl = providers.environmentVariable(releaseApiBaseUrlEnvironmentName)
    .orNull
    ?.takeIf { value -> value.isNotBlank() }
val releaseApiBaseUrlBuildConfigLiteral = "\"" +
    (releaseApiBaseUrl
        ?.takeIf { value -> value.all { character -> character.code in 0x21..0x7E } }
        ?: "https://invalid.invalid/api/v1/")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") +
    "\""
val releaseWechatAppIdEnvironmentName = "AI_FRIEND_ANDROID_WECHAT_APP_ID"
val releaseWechatAppId = providers.environmentVariable(releaseWechatAppIdEnvironmentName)
    .orNull
    ?.takeIf { value -> value.isNotBlank() }
val releaseWechatAppIdValid = releaseWechatAppId?.matches(
    Regex("^wx[0-9a-f]{16}$"),
) == true
val releaseWechatAppIdBuildConfigLiteral = "\"" +
    (releaseWechatAppId?.takeIf { releaseWechatAppIdValid } ?: "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") +
    "\""
// 当前自用 MVP 尚无正式签名武冈话包，Release 必须启用既有固定基础体验参数，
// 才能完成个人称呼、安全指令和普通话参考识别；它不代表正式方言模型。
val releaseBasicExperienceEnabled = true

val releaseWechatRulePackageEnvironmentNames = listOf(
    "AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR",
    "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID",
    "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64",
    "AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256",
)
val releaseWechatRulePackageEnvironment = releaseWechatRulePackageEnvironmentNames
    .associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf { value -> value.isNotBlank() }
    }
val configuredReleaseWechatRulePackageValues = releaseWechatRulePackageEnvironment
    .filterValues { value -> value != null }
val releaseWechatRulePackageConfigured =
    configuredReleaseWechatRulePackageValues.size == releaseWechatRulePackageEnvironmentNames.size
val releaseWechatRulePackagePartiallyConfigured =
    configuredReleaseWechatRulePackageValues.isNotEmpty() && !releaseWechatRulePackageConfigured
val releaseWechatRulePackageDirectory =
    releaseWechatRulePackageEnvironment["AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR"]
val releaseWechatRuleTrustedKeyId =
    releaseWechatRulePackageEnvironment["AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID"]
val releaseWechatRuleTrustedPublicKeyBase64 =
    releaseWechatRulePackageEnvironment[
        "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64"
    ]
val releaseWechatAppBuildIdentitySha256 =
    releaseWechatRulePackageEnvironment[
        "AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256"
    ]

val releaseWechatActionPlanTrustEnvironmentNames = listOf(
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID",
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64",
)
val releaseWechatActionPlanTrustEnvironment = releaseWechatActionPlanTrustEnvironmentNames
    .associateWith { name ->
        providers.environmentVariable(name).orNull?.takeIf { value -> value.isNotBlank() }
    }
val configuredReleaseWechatActionPlanTrustValues = releaseWechatActionPlanTrustEnvironment
    .filterValues { value -> value != null }
val releaseWechatActionPlanTrustConfigured =
    configuredReleaseWechatActionPlanTrustValues.size ==
        releaseWechatActionPlanTrustEnvironmentNames.size
val releaseWechatActionPlanTrustPartiallyConfigured =
    configuredReleaseWechatActionPlanTrustValues.isNotEmpty() &&
        !releaseWechatActionPlanTrustConfigured
val releaseWechatActionPlanTrustedKeyId = releaseWechatActionPlanTrustEnvironment[
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID"
]
val releaseWechatActionPlanTrustedPublicKeyBase64 = releaseWechatActionPlanTrustEnvironment[
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64"
]

fun String.asBuildConfigStringLiteral(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") +
    "\""

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "验证 AI好友 Release 签名材料已通过外部环境完整注入"
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "[AI_FRIEND_SIGNING_MISSING] AI好友 Release 签名未配置，缺少外部环境变量：" +
                    missingReleaseSigningEnvironmentNames.sorted().joinToString(", "),
            )
        }
        if (releaseStoreType !in setOf("JKS", "PKCS12")) {
            throw GradleException(
                "[AI_FRIEND_SIGNING_STORE_TYPE_INVALID] " +
                    "AI好友 Release keystore 类型必须是 JKS 或 PKCS12",
            )
        }
        val configuredStoreFile = File(requireNotNull(releaseStoreFilePath))
        if (!configuredStoreFile.isAbsolute) {
            throw GradleException(
                "[AI_FRIEND_SIGNING_STORE_PATH_NOT_ABSOLUTE] " +
                    "AI好友 Release keystore 必须使用仓库外绝对路径",
            )
        }
        val repositoryRoot = rootProject.projectDir.parentFile.canonicalFile
        val canonicalStoreFile = configuredStoreFile.canonicalFile
        val repositoryPrefix = repositoryRoot.path.trimEnd(File.separatorChar) + File.separator
        val storeInsideRepository =
            canonicalStoreFile.path.equals(repositoryRoot.path, ignoreCase = true) ||
                canonicalStoreFile.path.startsWith(repositoryPrefix, ignoreCase = true)
        if (storeInsideRepository) {
            throw GradleException(
                "[AI_FRIEND_SIGNING_STORE_INSIDE_REPOSITORY] " +
                    "AI好友 Release keystore 不得位于项目仓库内",
            )
        }
        if (!canonicalStoreFile.isFile) {
            throw GradleException(
                "[AI_FRIEND_SIGNING_STORE_FILE_UNAVAILABLE] " +
                    "AI好友 Release keystore 文件不可用",
            )
        }

        val storePasswordChars = requireNotNull(
            releaseSigningEnvironment["AI_FRIEND_ANDROID_RELEASE_STORE_PASSWORD"],
        ).toCharArray()
        val keyPasswordChars = requireNotNull(
            releaseSigningEnvironment["AI_FRIEND_ANDROID_RELEASE_KEY_PASSWORD"],
        ).toCharArray()
        val configuredKeyAlias = requireNotNull(
            releaseSigningEnvironment["AI_FRIEND_ANDROID_RELEASE_KEY_ALIAS"],
        )
        try {
            val keyStore = try {
                KeyStore.getInstance(requireNotNull(releaseStoreType)).apply {
                    canonicalStoreFile.inputStream().use { inputStream ->
                        load(inputStream, storePasswordChars)
                    }
                }
            } catch (_: Exception) {
                throw GradleException(
                    "[AI_FRIEND_SIGNING_STORE_OR_PASSWORD_MISMATCH] " +
                        "AI好友 Release keystore 类型或口令不匹配",
                )
            }
            if (!keyStore.containsAlias(configuredKeyAlias)) {
                throw GradleException(
                    "[AI_FRIEND_SIGNING_ALIAS_NOT_FOUND] " +
                        "AI好友 Release keystore 不包含指定别名",
                )
            }
            val keyEntry = try {
                keyStore.getEntry(
                    configuredKeyAlias,
                    KeyStore.PasswordProtection(keyPasswordChars),
                )
            } catch (_: Exception) {
                throw GradleException(
                    "[AI_FRIEND_SIGNING_KEY_PASSWORD_MISMATCH] " +
                        "AI好友 Release key 口令不匹配",
                )
            }
            if (keyEntry !is KeyStore.PrivateKeyEntry) {
                throw GradleException(
                    "[AI_FRIEND_SIGNING_ALIAS_NOT_PRIVATE_KEY] " +
                        "AI好友 Release 别名不是私钥条目",
                )
            }
        } finally {
            storePasswordChars.fill('\u0000')
            keyPasswordChars.fill('\u0000')
        }
    }
}

val verifyReleaseBasicExperience by tasks.registering {
    group = "verification"
    description = "验证自用 MVP Release 已启用固定基础体验语音参数"
    doLast {
        if (!releaseBasicExperienceEnabled) {
            throw GradleException(
                "AI好友 Release 基础体验语音参数未启用，录音模板将不可用",
            )
        }
    }
}

val verifyReleaseApiBaseUrl by tasks.registering {
    group = "verification"
    description = "验证 AI好友 Release API 地址已通过外部环境安全注入"
    doLast {
        val configuredUrl = releaseApiBaseUrl
            ?: throw GradleException(
                "AI好友 Release API 地址未配置，缺少外部环境变量：" +
                    releaseApiBaseUrlEnvironmentName,
            )
        if (configuredUrl != configuredUrl.trim() ||
            configuredUrl.any { character -> character.code !in 0x21..0x7E }
        ) {
            throw GradleException("AI好友 Release API 地址格式无效")
        }
        val parsedUrl = try {
            URI(configuredUrl)
        } catch (_: URISyntaxException) {
            throw GradleException("AI好友 Release API 地址格式无效")
        }
        val normalizedHost = parsedUrl.host?.trimEnd('.')?.lowercase()
        val rejectedHost = normalizedHost == null ||
            !normalizedHost.contains('.') ||
            normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost == "example.com" ||
            normalizedHost.endsWith(".example.com") ||
            normalizedHost.endsWith(".example") ||
            normalizedHost.endsWith(".invalid") ||
            normalizedHost.endsWith(".test") ||
            Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(normalizedHost) ||
            normalizedHost.contains(':')
        if (!parsedUrl.scheme.equals("https", ignoreCase = true) ||
            parsedUrl.userInfo != null ||
            parsedUrl.rawQuery != null ||
            parsedUrl.rawFragment != null ||
            parsedUrl.rawPath != "/api/v1/" ||
            rejectedHost
        ) {
            throw GradleException("AI好友 Release API 地址必须是非占位域名的 HTTPS /api/v1/ 根地址")
        }
    }
}

val verifyReleaseWechatLogin by tasks.registering {
    group = "verification"
    description = "验证 AI好友 Release 微信登录 AppID 配置"
    doLast {
        if (releaseWechatAppId != null && !releaseWechatAppIdValid) {
            throw GradleException(
                "微信登录 AppID 格式无效，请检查环境变量：" +
                    releaseWechatAppIdEnvironmentName,
            )
        }
    }
}

val verifyReleaseWechatRulePackage by tasks.registering {
    group = "verification"
    description = "验证 AI好友 Release 微信规则包已通过仓库外资源完整注入"
    doLast {
        if (releaseWechatRulePackagePartiallyConfigured) {
            val missing = releaseWechatRulePackageEnvironment
                .filterValues { value -> value == null }
                .keys
                .sorted()
            throw GradleException(
                "AI好友 Release 微信规则包配置不完整，缺少外部环境变量：" +
                    missing.joinToString(", "),
            )
        }
        if (!releaseWechatRulePackageConfigured) return@doLast

        val configuredDirectory = requireNotNull(releaseWechatRulePackageDirectory)
        if (configuredDirectory != configuredDirectory.trim() ||
            configuredDirectory.any { character -> character.code < 0x20 }
        ) {
            throw GradleException("AI好友 Release 微信规则包目录格式无效")
        }
        val rulePackageDirectory = File(configuredDirectory)
        if (!rulePackageDirectory.isAbsolute) {
            throw GradleException("AI好友 Release 微信规则包必须使用仓库外绝对目录")
        }
        val repositoryRoot = rootProject.projectDir.parentFile.canonicalFile
        val canonicalRulePackageDirectory = rulePackageDirectory.canonicalFile
        val repositoryPrefix = repositoryRoot.path.trimEnd(File.separatorChar) + File.separator
        val directoryInsideRepository =
            canonicalRulePackageDirectory.path.equals(repositoryRoot.path, ignoreCase = true) ||
                canonicalRulePackageDirectory.path.startsWith(
                    repositoryPrefix,
                    ignoreCase = true,
                )
        if (directoryInsideRepository) {
            throw GradleException("AI好友 Release 微信规则包目录不得位于项目仓库内")
        }
        if (!canonicalRulePackageDirectory.isDirectory) {
            throw GradleException("AI好友 Release 微信规则包目录不可用")
        }
        val ruleRoot = canonicalRulePackageDirectory.resolve("wechat/rules")
        val requiredFiles = mapOf(
            "manifest.json" to 65_536L,
            "manifest.sig" to 512L,
            "capabilities.json" to 262_144L,
        )
        requiredFiles.forEach { (name, maximumBytes) ->
            val resource = ruleRoot.resolve(name)
            if (!resource.isFile || resource.length() !in 1..maximumBytes) {
                throw GradleException("AI好友 Release 微信规则包资源缺失或大小无效：$name")
            }
        }

        val trustedKeyId = requireNotNull(releaseWechatRuleTrustedKeyId)
        if (!trustedKeyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}"))) {
            throw GradleException("AI好友 Release 微信规则包密钥编号格式无效")
        }
        val publicKeyBytes = try {
            Base64.getDecoder().decode(requireNotNull(releaseWechatRuleTrustedPublicKeyBase64))
        } catch (_: IllegalArgumentException) {
            throw GradleException("AI好友 Release 微信规则包信任公钥格式无效")
        }
        val ed25519X509Prefix = intArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        if (publicKeyBytes.size != 44 ||
            !publicKeyBytes.take(ed25519X509Prefix.size)
                .map { value -> value.toInt() and 0xff }
                .toIntArray()
                .contentEquals(ed25519X509Prefix)
        ) {
            throw GradleException("AI好友 Release 微信规则包信任公钥不是 Ed25519 X.509 公钥")
        }
        if (!requireNotNull(releaseWechatAppBuildIdentitySha256)
                .matches(Regex("[0-9a-f]{64}"))
        ) {
            throw GradleException("AI好友 Release 微信规则包构建身份摘要格式无效")
        }
    }
}

val verifyReleaseWechatActionPlanTrust by tasks.registering {
    group = "verification"
    description = "验证 AI好友 Release 动作计划定位证明信任公钥已完整注入"
    doLast {
        if (releaseWechatActionPlanTrustPartiallyConfigured) {
            val missing = releaseWechatActionPlanTrustEnvironment
                .filterValues { value -> value == null }
                .keys
                .sorted()
            throw GradleException(
                "AI好友 Release 动作计划信任配置不完整，缺少外部环境变量：" +
                    missing.joinToString(", "),
            )
        }
        if (!releaseWechatActionPlanTrustConfigured) return@doLast

        val trustedKeyId = requireNotNull(releaseWechatActionPlanTrustedKeyId)
        if (!trustedKeyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}"))) {
            throw GradleException("AI好友 Release 动作计划密钥编号格式无效")
        }
        val publicKeyBytes = try {
            Base64.getDecoder().decode(
                requireNotNull(releaseWechatActionPlanTrustedPublicKeyBase64),
            )
        } catch (_: IllegalArgumentException) {
            throw GradleException("AI好友 Release 动作计划信任公钥格式无效")
        }
        val ed25519X509Prefix = intArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        if (publicKeyBytes.size != 44 ||
            !publicKeyBytes.take(ed25519X509Prefix.size)
                .map { value -> value.toInt() and 0xff }
                .toIntArray()
                .contentEquals(ed25519X509Prefix)
        ) {
            throw GradleException("AI好友 Release 动作计划信任公钥不是 Ed25519 X.509 公钥")
        }
    }
}

android {
    namespace = "com.aifriend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aifriend"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // 正式签名武冈话包和信任公钥尚未签发，正式方言能力默认失败关闭。
        buildConfigField("boolean", "DIALECT_PACKAGE_ENABLED", "false")
        buildConfigField("boolean", "BASIC_EXPERIENCE_ENABLED", "false")
        // 体验联系人和模拟完成只允许 Debug 构建显示，Release 固定隐藏。
        buildConfigField("boolean", "MVP_DEMO_ENABLED", "false")
        buildConfigField("String", "DIALECT_PACKAGE_ROOT", "\"dialect/wugang\"")
        buildConfigField("String", "DIALECT_TRUSTED_KEY_ID", "\"\"")
        buildConfigField("String", "DIALECT_TRUSTED_PUBLIC_KEY_BASE64", "\"\"")
        // 正式动作计划定位证明公钥尚未签发，空配置固定验签失败。
        buildConfigField("String", "WECHAT_ACTION_PLAN_TRUSTED_KEY_ID", "\"\"")
        buildConfigField("String", "WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64", "\"\"")
        // 正式签名微信规则包、信任公钥和发布 build 摘要尚未签发，默认失败关闭。
        buildConfigField("boolean", "WECHAT_RULE_PACKAGE_ENABLED", "false")
        buildConfigField("String", "WECHAT_RULE_PACKAGE_ROOT", "\"wechat/rules\"")
        buildConfigField("String", "WECHAT_RULE_TRUSTED_KEY_ID", "\"\"")
        buildConfigField("String", "WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64", "\"\"")
        buildConfigField("String", "WECHAT_APP_BUILD_SHA256", "\"\"")
        // 微信 AppID 只属于公开客户端标识，仍只从 Release 构建环境注入；Debug 固定关闭。
        buildConfigField("boolean", "WECHAT_LOGIN_ENABLED", "false")
        buildConfigField("String", "WECHAT_APP_ID", "\"\"")
        // 微信页面样本采集只允许 Debug 构建显式开启，Release 固定隐藏。
        buildConfigField("boolean", "WECHAT_SAMPLE_CAPTURE_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = File(requireNotNull(releaseStoreFilePath))
                storeType = requireNotNull(releaseStoreType)
                storePassword = releaseSigningEnvironment.getValue(
                    "AI_FRIEND_ANDROID_RELEASE_STORE_PASSWORD",
                )
                keyAlias = releaseSigningEnvironment.getValue(
                    "AI_FRIEND_ANDROID_RELEASE_KEY_ALIAS",
                )
                keyPassword = releaseSigningEnvironment.getValue(
                    "AI_FRIEND_ANDROID_RELEASE_KEY_PASSWORD",
                )
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "API_BASE_URL", "\"https://api.ai-friend.asia/api/v1/\"")
            buildConfigField("boolean", "BASIC_EXPERIENCE_ENABLED", "true")
            buildConfigField("boolean", "MVP_DEMO_ENABLED", "true")
            // 自用 MVP 改用运行时严格语义校验；开发页面采样入口固定关闭。
            buildConfigField("boolean", "WECHAT_SAMPLE_CAPTURE_ENABLED", "false")
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "API_BASE_URL", releaseApiBaseUrlBuildConfigLiteral)
            buildConfigField(
                "boolean",
                "BASIC_EXPERIENCE_ENABLED",
                releaseBasicExperienceEnabled.toString(),
            )
            buildConfigField(
                "boolean",
                "WECHAT_LOGIN_ENABLED",
                releaseWechatAppIdValid.toString(),
            )
            buildConfigField("String", "WECHAT_APP_ID", releaseWechatAppIdBuildConfigLiteral)
            buildConfigField(
                "String",
                "WECHAT_ACTION_PLAN_TRUSTED_KEY_ID",
                (releaseWechatActionPlanTrustedKeyId ?: "").asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64",
                (releaseWechatActionPlanTrustedPublicKeyBase64 ?: "")
                    .asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "boolean",
                "WECHAT_RULE_PACKAGE_ENABLED",
                releaseWechatRulePackageConfigured.toString(),
            )
            buildConfigField(
                "String",
                "WECHAT_RULE_TRUSTED_KEY_ID",
                (releaseWechatRuleTrustedKeyId ?: "").asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64",
                (releaseWechatRuleTrustedPublicKeyBase64 ?: "").asBuildConfigStringLiteral(),
            )
            // 这是规则签发时固定的发布构建身份，不是最终 APK 文件摘要，避免自引用。
            buildConfigField(
                "String",
                "WECHAT_APP_BUILD_SHA256",
                (releaseWechatAppBuildIdentitySha256 ?: "").asBuildConfigStringLiteral(),
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main").kotlin.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/kotlin"))
        if (releaseWechatRulePackageConfigured) {
            getByName("release").assets.srcDir(File(requireNotNull(releaseWechatRulePackageDirectory)))
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(
            verifyReleaseSigning,
            verifyReleaseBasicExperience,
            verifyReleaseApiBaseUrl,
            verifyReleaseWechatLogin,
            verifyReleaseWechatActionPlanTrust,
            verifyReleaseWechatRulePackage,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

kapt {
    correctErrorTypes = true
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(rootProject.file("../docs/design/AI好友-OpenAPI-v1.0.yaml").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("com.aifriend.contract.api")
    modelPackage.set("com.aifriend.contract.model")
    invokerPackage.set("com.aifriend.contract.invoker")
    library.set("jvm-retrofit2")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    openapiNormalizer.set(
        mapOf(
            "SIMPLIFY_BOOLEAN_ENUM" to "true",
        ),
    )
    typeMappings.set(
        mapOf(
            "AnyType" to "JsonElement",
        ),
    )
    importMappings.set(
        mapOf(
            "JsonElement" to "kotlinx.serialization.json.JsonElement",
        ),
    )
    configOptions.set(
        mapOf(
            "dateLibrary" to "java8",
            "enumPropertyNaming" to "UPPERCASE",
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
        ),
    )
}

tasks.named<GenerateTask>("openApiGenerate").configure {
    doLast {
        val generatedApiDirectory = layout.buildDirectory
            .dir("generated/openapi/src/main/kotlin/com/aifriend/contract/api")
            .get()
            .asFile
        val deleteWithBodyPattern = Regex(
            """(?m)^(\s*)@DELETE\("([^"]+)"\)\r?\n(\s*suspend fun[^\r\n]*@Body[^\r\n]*)$""",
        )
        generatedApiDirectory.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .forEach { file ->
                val generatedSource = file.readText()
                val correctedSource = deleteWithBodyPattern.replace(generatedSource) { match ->
                    val indentation = match.groupValues[1]
                    val path = match.groupValues[2]
                    val declaration = match.groupValues[3]
                    "$indentation@HTTP(method = \"DELETE\", path = \"$path\", hasBody = true)\n" +
                        declaration
                }
                if (correctedSource != generatedSource) {
                    file.writeText(correctedSource)
                }
                check(!deleteWithBodyPattern.containsMatchIn(correctedSource)) {
                    "生成的 Retrofit DELETE 请求仍错误地拒绝请求体：${file.name}"
                }
            }
    }
}

tasks.named("preBuild").configure {
    dependsOn(tasks.named<GenerateTask>("openApiGenerate"))
}

val mappedRoot = providers.gradleProperty("asciiProjectRoot").orNull
val projectRoot = rootProject.projectDir.absolutePath

if (mappedRoot != null) {
    tasks.withType<Test>().configureEach {
        notCompatibleWithConfigurationCache("Windows Unicode 项目路径需要在执行前映射测试 classpath")
        maxParallelForks = 1
        val mappedClasspath = objects.fileCollection()
        doFirst {
            mappedClasspath.setFrom(classpath.files.map { entry ->
                val entryPath = entry.absolutePath
                if (entryPath.startsWith(projectRoot, ignoreCase = true)) {
                    File(mappedRoot + entryPath.substring(projectRoot.length))
                } else {
                    entry
                }
            })
            classpath = mappedClasspath
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.tink)
    implementation(libs.vosk.android)
    implementation(libs.wechat.open.sdk)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
