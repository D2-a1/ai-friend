[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$GradleUserHome,

    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRoot,

    [string]$WechatRulePackageConfigFile,

    [string]$WechatActionPlanTrustConfigFile,

    [switch]$DiagnosticsSelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

function Read-HiddenText {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt
    )

    $secureValue = Read-Host -Prompt $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        $secureValue.Dispose()
    }
}

function Test-PathInsideRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CandidatePath,

        [Parameter(Mandatory = $true)]
        [string]$RootPath
    )

    $normalizedRoot = [IO.Path]::GetFullPath($RootPath).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $normalizedCandidate = [IO.Path]::GetFullPath($CandidatePath)
    return $normalizedCandidate.Equals($normalizedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $normalizedCandidate.StartsWith(
            $normalizedRoot + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase
        )
}

function Get-ReleaseFailureCategory {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$GradleOutput
    )

    $joinedOutput = $GradleOutput -join "`n"
    $failedTaskMatch = [regex]::Match(
        $joinedOutput,
        "Execution failed for task '([^']+)'"
    )
    $failedTask = if ($failedTaskMatch.Success) {
        $failedTaskMatch.Groups[1].Value
    } else {
        $null
    }
    $signingDiagnosticCategories = [ordered]@{
        "AI_FRIEND_SIGNING_MISSING" = "Release 签名输入缺少必填项"
        "AI_FRIEND_SIGNING_STORE_TYPE_INVALID" = "keystore 类型必须是 JKS 或 PKCS12"
        "AI_FRIEND_SIGNING_STORE_PATH_NOT_ABSOLUTE" = "keystore 必须使用仓库外绝对路径"
        "AI_FRIEND_SIGNING_STORE_INSIDE_REPOSITORY" = "keystore 不得位于项目仓库内"
        "AI_FRIEND_SIGNING_STORE_FILE_UNAVAILABLE" = "keystore 文件不可用"
        "AI_FRIEND_SIGNING_STORE_OR_PASSWORD_MISMATCH" = "keystore 类型或口令不匹配"
        "AI_FRIEND_SIGNING_ALIAS_NOT_FOUND" = "密钥别名不存在"
        "AI_FRIEND_SIGNING_KEY_PASSWORD_MISMATCH" = "key 口令不匹配"
        "AI_FRIEND_SIGNING_ALIAS_NOT_PRIVATE_KEY" = "密钥别名不是私钥条目"
    }
    foreach ($diagnosticCode in $signingDiagnosticCategories.Keys) {
        if ($joinedOutput.Contains("[$diagnosticCode]")) {
            return $signingDiagnosticCategories[$diagnosticCode]
        }
    }
    if ($joinedOutput -match "(?i)insufficient memory for the Java Runtime Environment|Out of Memory Error|Native memory allocation .* failed") {
        return "Release 构建失败：系统可用内存或虚拟内存不足；签名输入没有问题"
    }
    if ($joinedOutput -match "(?i)JVM crash log found|Gradle build daemon disappeared unexpectedly|daemon has disappeared") {
        return "Release 构建 JVM 异常退出；请先释放系统内存和虚拟内存，签名输入没有问题"
    }
    if ($joinedOutput -match "AI好友 Release keystore 类型或口令不匹配" -or
        $joinedOutput -match "(?i)Keystore was tampered with, or password was incorrect|password verification failed") {
        return "keystore 类型或口令不匹配"
    }
    if ($joinedOutput -match "AI好友 Release key 口令不匹配" -or
        $joinedOutput -match "(?i)Cannot recover key|Given final block not properly padded") {
        return "key 口令不匹配"
    }
    if ($joinedOutput -match "AI好友 Release keystore 不包含指定别名" -or
        $joinedOutput -match "(?i)No key with alias") {
        return "密钥别名不存在"
    }
    if ($joinedOutput -match "AI好友 Release 别名不是私钥条目") {
        return "密钥别名不是私钥条目"
    }
    if ($failedTask -eq ":app:verifyReleaseApiBaseUrl") {
        return "Release API 地址未通过安全校验"
    }
    if ($failedTask -eq ":app:verifyReleaseSigning") {
        return "Release 签名输入未通过安全校验"
    }
    if ($failedTask -eq ":app:verifyReleaseWechatLogin") {
        return "微信登录 AppID 未通过格式校验"
    }
    if ($failedTask -eq ":app:verifyReleaseWechatRulePackage") {
        return "微信规则包未通过完整性校验"
    }
    if ($failedTask -eq ":app:verifyReleaseWechatActionPlanTrust") {
        return "微信动作计划信任公钥未通过完整性校验"
    }
    if ($failedTask -eq ":app:minifyReleaseWithR8" -or
        $joinedOutput -match "(?i)Missing classes detected while running R8") {
        return "Release 代码压缩失败"
    }
    if ($joinedOutput -match "(?i)does not contain a Gradle build|not a Gradle build") {
        return "Release 构建工作目录无效"
    }
    if ($null -ne $failedTask) {
        return "Release 构建任务失败：$failedTask"
    }
    return "Release 构建失败，未能识别任务阶段；请停止重试并执行无密钥预检"
}

function Get-AvailableVirtualMemoryMegabytes {
    try {
        $operatingSystem = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        return [math]::Floor([double]$operatingSystem.FreeVirtualMemory / 1024)
    } catch {
        return $null
    }
}

function ConvertTo-NormalizedPathInput {
    param(
        [AllowNull()]
        [string]$Value
    )

    if ($null -eq $Value) {
        return $null
    }

    $invisibleCharacters = [char[]]@(
        [char]0xFEFF,
        [char]0x200B,
        [char]0x2060
    )
    $normalizedValue = $Value.Trim().Trim($invisibleCharacters).Trim()
    if ($normalizedValue.Length -ge 2) {
        $firstCharacter = $normalizedValue[0]
        $lastCharacter = $normalizedValue[$normalizedValue.Length - 1]
        $wrappedInQuotes =
            ($firstCharacter -eq [char]0x22 -and $lastCharacter -eq [char]0x22) -or
            ($firstCharacter -eq [char]0x27 -and $lastCharacter -eq [char]0x27)
        if ($wrappedInQuotes) {
            $normalizedValue = $normalizedValue.Substring(1, $normalizedValue.Length - 2).Trim().Trim($invisibleCharacters).Trim()
        }
    }
    if ($normalizedValue.IndexOfAny([IO.Path]::GetInvalidPathChars()) -ge 0) {
        throw "keystore 路径格式无效"
    }
    return $normalizedValue
}

function Read-WechatRulePackageConfiguration {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigFile,

        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    try {
        $normalizedConfigFile = ConvertTo-NormalizedPathInput -Value $ConfigFile
        if (-not [IO.Path]::IsPathRooted($normalizedConfigFile)) {
            throw "not rooted"
        }
        $configItem = Get-Item -LiteralPath $normalizedConfigFile -ErrorAction Stop
        if ($configItem.PSIsContainer) {
            throw "directory"
        }
        if (Test-PathInsideRoot -CandidatePath $configItem.FullName -RootPath $RepositoryRoot) {
            throw "inside repository"
        }
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $jsonText = [IO.File]::ReadAllText($configItem.FullName, $strictUtf8)
        $configuration = $jsonText | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "微信规则包 Release 配置文件不可用"
    }

    $expectedNames = @(
        "AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR",
        "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID",
        "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64",
        "AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256"
    )
    $actualNames = @($configuration.PSObject.Properties.Name)
    $difference = @(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualNames)
    if ($difference.Count -ne 0) {
        throw "微信规则包 Release 配置键不完整或包含未知项"
    }
    $values = @{}
    foreach ($name in $expectedNames) {
        $value = $configuration.$name
        if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value) -or
            $value -ne $value.Trim()) {
            throw "微信规则包 Release 配置值格式无效"
        }
        $values[$name] = $value
    }
    if ($values["AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID"] -notmatch
        "^[A-Za-z0-9][A-Za-z0-9._+\-]{0,59}$") {
        throw "微信规则包 Release 密钥编号格式无效"
    }
    if ($values["AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256"] -notmatch
        "^[0-9a-f]{64}$") {
        throw "微信规则包 Release 构建身份摘要格式无效"
    }
    try {
        $publicKey = [Convert]::FromBase64String(
            $values["AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64"]
        )
    } catch {
        throw "微信规则包 Release 信任公钥格式无效"
    }
    $ed25519Prefix = [byte[]]@(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
        0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )
    if ($publicKey.Length -ne 44) {
        throw "微信规则包 Release 信任公钥格式无效"
    }
    for ($index = 0; $index -lt $ed25519Prefix.Length; $index++) {
        if ($publicKey[$index] -ne $ed25519Prefix[$index]) {
            throw "微信规则包 Release 信任公钥格式无效"
        }
    }
    try {
        $rulePackageDirectory = [IO.Path]::GetFullPath(
            $values["AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR"]
        )
        if (-not [IO.Path]::IsPathRooted($rulePackageDirectory) -or
            (Test-PathInsideRoot -CandidatePath $rulePackageDirectory -RootPath $RepositoryRoot)) {
            throw "invalid rule package directory"
        }
    } catch {
        throw "微信规则包 Release 目录必须是仓库外绝对路径"
    }
    if (-not (Test-Path -LiteralPath $rulePackageDirectory -PathType Container)) {
        throw "微信规则包 Release 目录不可用"
    }
    $values["AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR"] = $rulePackageDirectory
    return $values
}

function Read-WechatActionPlanTrustConfiguration {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigFile,

        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    try {
        $normalizedConfigFile = ConvertTo-NormalizedPathInput -Value $ConfigFile
        if (-not [IO.Path]::IsPathRooted($normalizedConfigFile)) {
            throw "not rooted"
        }
        $configItem = Get-Item -LiteralPath $normalizedConfigFile -ErrorAction Stop
        if ($configItem.PSIsContainer -or
            (Test-PathInsideRoot -CandidatePath $configItem.FullName -RootPath $RepositoryRoot)) {
            throw "invalid configuration file"
        }
        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $configuration = [IO.File]::ReadAllText($configItem.FullName, $strictUtf8) |
            ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "微信动作计划 Release 信任配置文件不可用"
    }

    $expectedNames = @(
        "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID",
        "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64"
    )
    $actualNames = @($configuration.PSObject.Properties.Name)
    $difference = @(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualNames)
    if ($difference.Count -ne 0) {
        throw "微信动作计划 Release 信任配置键不完整或包含未知项"
    }
    $values = @{}
    foreach ($name in $expectedNames) {
        $value = $configuration.$name
        if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value) -or
            $value -ne $value.Trim()) {
            throw "微信动作计划 Release 信任配置值格式无效"
        }
        $values[$name] = $value
    }
    if ($values["AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID"] -notmatch
        "^[A-Za-z0-9][A-Za-z0-9._+\-]{0,59}$") {
        throw "微信动作计划 Release 密钥编号格式无效"
    }
    try {
        $publicKey = [Convert]::FromBase64String(
            $values["AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64"]
        )
    } catch {
        throw "微信动作计划 Release 信任公钥格式无效"
    }
    $ed25519Prefix = [byte[]]@(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03,
        0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    )
    if ($publicKey.Length -ne 44) {
        throw "微信动作计划 Release 信任公钥格式无效"
    }
    for ($index = 0; $index -lt $ed25519Prefix.Length; $index++) {
        if ($publicKey[$index] -ne $ed25519Prefix[$index]) {
            throw "微信动作计划 Release 信任公钥格式无效"
        }
    }
    return $values
}

if ($DiagnosticsSelfTest) {
    $diagnosticCases = @(
        @("AI_FRIEND_SIGNING_MISSING", "Release 签名输入缺少必填项"),
        @("AI_FRIEND_SIGNING_STORE_TYPE_INVALID", "keystore 类型必须是 JKS 或 PKCS12"),
        @("AI_FRIEND_SIGNING_STORE_PATH_NOT_ABSOLUTE", "keystore 必须使用仓库外绝对路径"),
        @("AI_FRIEND_SIGNING_STORE_INSIDE_REPOSITORY", "keystore 不得位于项目仓库内"),
        @("AI_FRIEND_SIGNING_STORE_FILE_UNAVAILABLE", "keystore 文件不可用"),
        @("AI_FRIEND_SIGNING_STORE_OR_PASSWORD_MISMATCH", "keystore 类型或口令不匹配"),
        @("AI_FRIEND_SIGNING_ALIAS_NOT_FOUND", "密钥别名不存在"),
        @("AI_FRIEND_SIGNING_KEY_PASSWORD_MISMATCH", "key 口令不匹配"),
        @("AI_FRIEND_SIGNING_ALIAS_NOT_PRIVATE_KEY", "密钥别名不是私钥条目")
    )
    foreach ($diagnosticCase in $diagnosticCases) {
        $actualCategory = Get-ReleaseFailureCategory -GradleOutput @(
            "Execution failed for task ':app:verifyReleaseSigning'.",
            "[$($diagnosticCase[0])] deliberately garbled localized message"
        )
        if ($actualCategory -ne $diagnosticCase[1]) {
            throw "Release 构建诊断自检失败：$($diagnosticCase[0])"
        }
    }
    $memoryFailureCategory = Get-ReleaseFailureCategory -GradleOutput @(
        "There is insufficient memory for the Java Runtime Environment to continue.",
        "Out of Memory Error"
    )
    if ($memoryFailureCategory -ne "Release 构建失败：系统可用内存或虚拟内存不足；签名输入没有问题") {
        throw "Release 构建诊断自检失败：JVM_MEMORY"
    }
    $daemonFailureCategory = Get-ReleaseFailureCategory -GradleOutput @(
        "Gradle build daemon disappeared unexpectedly",
        "JVM crash log found"
    )
    if ($daemonFailureCategory -ne "Release 构建 JVM 异常退出；请先释放系统内存和虚拟内存，签名输入没有问题") {
        throw "Release 构建诊断自检失败：JVM_CRASH"
    }
    Write-Host "Release 构建诊断自检通过"
    return
}

$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $androidRoot ".."))
$gradleWrapper = Join-Path $androidRoot "gradlew.bat"
$apksigner = Join-Path $AndroidSdkRoot "build-tools\35.0.0\apksigner.bat"
$releaseApk = Join-Path $androidRoot "app\build\outputs\apk\release\app-release.apk"
$environmentNames = @(
    "AI_FRIEND_ANDROID_RELEASE_STORE_FILE",
    "AI_FRIEND_ANDROID_RELEASE_STORE_TYPE",
    "AI_FRIEND_ANDROID_RELEASE_STORE_PASSWORD",
    "AI_FRIEND_ANDROID_RELEASE_KEY_ALIAS",
    "AI_FRIEND_ANDROID_RELEASE_KEY_PASSWORD",
    "AI_FRIEND_ANDROID_RELEASE_API_BASE_URL",
    "AI_FRIEND_ANDROID_WECHAT_APP_ID",
    "AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR",
    "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID",
    "AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64",
    "AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256",
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID",
    "AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64"
)

if ($androidRoot -match "[^\u0000-\u007F]") {
    throw "请从项目既有的 ASCII Android 目录映射运行本脚本"
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle Wrapper 不可用"
}
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "Android SDK 35 apksigner 不可用"
}
if (-not [IO.Path]::IsPathRooted($GradleUserHome)) {
    throw "GradleUserHome 必须使用绝对路径"
}
$availableVirtualMemoryMegabytes = Get-AvailableVirtualMemoryMegabytes
if ($null -ne $availableVirtualMemoryMegabytes -and
    $availableVirtualMemoryMegabytes -lt 4096) {
    throw "Release 构建前检查失败：系统可用内存和虚拟内存不足 4 GB；尚未读取签名信息，请先关闭 Android Studio 或其他占用内存的程序"
}

$storeFile = $null
$storeType = $null
$storePassword = $null
$keyAlias = $null
$keyPassword = $null
$apiBaseUrl = $null
$wechatAppId = $null
$storeItem = $null
$wechatRuleConfiguration = $null
$wechatActionPlanTrustConfiguration = $null

try {
    if (-not [string]::IsNullOrWhiteSpace($WechatRulePackageConfigFile)) {
        $wechatRuleConfiguration = Read-WechatRulePackageConfiguration -ConfigFile $WechatRulePackageConfigFile -RepositoryRoot $repositoryRoot
        foreach ($name in $wechatRuleConfiguration.Keys) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $wechatRuleConfiguration[$name],
                "Process"
            )
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($WechatActionPlanTrustConfigFile)) {
        $wechatActionPlanTrustConfiguration = Read-WechatActionPlanTrustConfiguration `
            -ConfigFile $WechatActionPlanTrustConfigFile `
            -RepositoryRoot $repositoryRoot
        foreach ($name in $wechatActionPlanTrustConfiguration.Keys) {
            [Environment]::SetEnvironmentVariable(
                $name,
                $wechatActionPlanTrustConfiguration[$name],
                "Process"
            )
        }
    }
    Write-Host "请输入已有 keystore 信息。隐藏输入不会显示，也不会写入文件。"
    try {
        $storeFile = ConvertTo-NormalizedPathInput `
            -Value (Read-HiddenText -Prompt "keystore 绝对路径")
    } catch {
        throw "keystore 路径格式无效"
    }
    $storeType = (Read-Host -Prompt "keystore 类型（JKS 或 PKCS12）").Trim().ToUpperInvariant()
    $storePassword = Read-HiddenText -Prompt "keystore 口令"
    $keyAlias = Read-HiddenText -Prompt "密钥别名"
    $keyPassword = Read-HiddenText -Prompt "key 口令"
    $apiBaseUrl = (Read-Host -Prompt "Release API 基础 HTTPS 地址（必须以 /api/v1/ 结尾）").Trim()
    $wechatAppId = (Read-Host -Prompt "微信 AppID（wx 加 16 位小写十六进制；暂不启用可留空）").Trim()

    if ([string]::IsNullOrWhiteSpace($storeFile)) {
        throw "keystore 必须使用仓库外绝对路径"
    }
    try {
        $storePathIsRooted = [IO.Path]::IsPathRooted($storeFile)
    } catch {
        throw "keystore 路径格式无效"
    }
    if (-not $storePathIsRooted) {
        throw "keystore 必须使用仓库外绝对路径"
    }
    if ($storeType -notin @("JKS", "PKCS12")) {
        throw "keystore 类型必须是 JKS 或 PKCS12"
    }
    if ([string]::IsNullOrWhiteSpace($storePassword) -or
        [string]::IsNullOrWhiteSpace($keyAlias) -or
        [string]::IsNullOrWhiteSpace($keyPassword)) {
        throw "keystore、别名和口令均不能为空"
    }
    if (-not [string]::IsNullOrWhiteSpace($wechatAppId) -and
        $wechatAppId -notmatch "^wx[0-9a-f]{16}$") {
        throw "微信 AppID 必须是 wx 加 16 位小写十六进制"
    }
    try {
        $storeInsideRepository = Test-PathInsideRoot `
            -CandidatePath $storeFile `
            -RootPath $repositoryRoot
    } catch {
        throw "keystore 路径格式无效"
    }
    if ($storeInsideRepository) {
        throw "keystore 不得位于项目仓库内"
    }
    try {
        $storeItem = Get-Item -LiteralPath $storeFile -ErrorAction Stop
    } catch {
        throw "keystore 文件不可用"
    }
    if ($storeItem.PSIsContainer) {
        throw "keystore 文件不可用"
    }

    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_STORE_FILE",
        $storeItem.FullName,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_STORE_TYPE",
        $storeType,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_STORE_PASSWORD",
        $storePassword,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_KEY_ALIAS",
        $keyAlias,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_KEY_PASSWORD",
        $keyPassword,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_RELEASE_API_BASE_URL",
        $apiBaseUrl,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "AI_FRIEND_ANDROID_WECHAT_APP_ID",
        $wechatAppId,
        "Process"
    )

    Write-Host "正在执行受控 Release 构建，原始构建输出不会回显。"
    $previousErrorActionPreference = $ErrorActionPreference
    Push-Location -LiteralPath $androidRoot
    try {
        # Windows PowerShell 5.1 会把原生命令的标准错误流包装成
        # NativeCommandError；这里继续收集完整输出，最终只按退出码判定结果。
        $ErrorActionPreference = "Continue"
        $gradleOutput = @(
            & $gradleWrapper `
                -g $GradleUserHome `
                :app:assembleRelease `
                --no-daemon `
                --no-parallel `
                --max-workers=2 `
                --no-configuration-cache `
                "-Pkotlin.incremental=false" `
                "-Pkotlin.compiler.execution.strategy=in-process" `
                "-PasciiProjectRoot=$androidRoot" `
                --rerun-tasks 2>&1
        )
        $gradleExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Pop-Location
    }
    if ($gradleExitCode -ne 0) {
        $failureCategory = Get-ReleaseFailureCategory -GradleOutput $gradleOutput
        throw $failureCategory
    }
    if (-not (Test-Path -LiteralPath $releaseApk -PathType Leaf)) {
        throw "Release 构建没有产生预期 APK"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $apksignerOutput = @(& $apksigner verify --verbose --print-certs $releaseApk 2>&1)
        $apksignerExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($apksignerExitCode -ne 0) {
        throw "Release APK 签名验证失败"
    }
    $certificateLine = $apksignerOutput |
        Where-Object { $_ -match "certificate SHA-256 digest:" } |
        Select-Object -First 1
    if ($null -eq $certificateLine) {
        throw "Release APK 缺少可核验的签名证书摘要"
    }
    $certificateSha256 = (($certificateLine -split ":", 2)[1]).Trim().ToUpperInvariant()
    if ($certificateSha256 -notmatch "^[0-9A-F]{64}$") {
        throw "Release APK 签名证书摘要格式无效"
    }
    $certificateMd5Line = $apksignerOutput |
        Where-Object { $_ -match "certificate MD5 digest:" } |
        Select-Object -First 1
    if ($null -eq $certificateMd5Line) {
        throw "Release APK 缺少微信开放平台所需的签名证书 MD5"
    }
    $certificateMd5 = (($certificateMd5Line -split ":", 2)[1]).Trim().ToLowerInvariant()
    if ($certificateMd5 -notmatch "^[0-9a-f]{32}$") {
        throw "Release APK 签名证书 MD5 格式无效"
    }
    $apkSha256 = (Get-FileHash -LiteralPath $releaseApk -Algorithm SHA256).Hash.ToUpperInvariant()

    Write-Host "正式签名 APK 已生成并通过 apksigner 验证。"
    Write-Host "APK：app/build/outputs/apk/release/app-release.apk"
    Write-Host "APK SHA-256：$apkSha256"
    Write-Host "签名证书 SHA-256：$certificateSha256"
    Write-Host "微信开放平台 Android 应用签名（证书 MD5，小写无分隔符）：$certificateMd5"
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $null, "Process")
    }
    $storeFile = $null
    $storeType = $null
    $storePassword = $null
    $keyAlias = $null
    $keyPassword = $null
    $apiBaseUrl = $null
    $wechatAppId = $null
    $storeItem = $null
    $wechatRuleConfiguration = $null
    $wechatActionPlanTrustConfiguration = $null
    $gradleOutput = $null
    $apksignerOutput = $null
    $certificateMd5Line = $null
    $certificateMd5 = $null
}
