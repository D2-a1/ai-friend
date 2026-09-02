[CmdletBinding()]
param(
    [string]$Region = "ap-shanghai",
    [string]$Bucket = "",
    [switch]$PreflightOnly,
    [switch]$VerifyRestoreOnly,
    [string]$SnapshotId = "",
    [string]$CreatedAt = "",
    [ValidateRange(1, 100)]
    [int]$PageSize = 100,
    [string]$ConnectTimeout = "PT2S",
    [string]$ReadTimeout = "PT5S"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-SecretText {
    param([Parameter(Mandatory = $true)][string]$Prompt)

    $secureValue = Read-Host $Prompt -AsSecureString
    $secretPointer = [IntPtr]::Zero
    try {
        $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    }
    finally {
        if ($secretPointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
        }
        $secureValue.Dispose()
    }
}

function Read-DerKeyBase64 {
    param(
        [Parameter(Mandatory = $true)][string]$Prompt,
        [Parameter(Mandatory = $true)][string]$ExpectedPemLabel,
        [Parameter(Mandatory = $true)][bool]$MustStayOutsideRepository,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )

    $enteredPath = Read-Host $Prompt
    $resolvedPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $enteredPath).Path)
    if (-not [IO.File]::Exists($resolvedPath)) {
        throw "密钥文件不存在"
    }
    $repositoryPrefix = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    if ($MustStayOutsideRepository -and
        $resolvedPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Ed25519 私钥文件必须位于项目仓库之外"
    }

    $rawBytes = $null
    try {
        $fileText = [IO.File]::ReadAllText($resolvedPath)
        $beginMarker = "-----BEGIN $ExpectedPemLabel-----"
        $endMarker = "-----END $ExpectedPemLabel-----"
        if ($fileText.Contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
            throw "当前工具不接受加密 PKCS#8；请在离线受控目录准备临时 PKCS#8 DER 或 PRIVATE KEY PEM"
        }
        if ($fileText.Contains($beginMarker)) {
            $base64Text = $fileText.Replace($beginMarker, "").Replace($endMarker, "")
            $base64Text = [Text.RegularExpressions.Regex]::Replace($base64Text, "\s", "")
            $rawBytes = [Convert]::FromBase64String($base64Text)
        }
        else {
            $rawBytes = [IO.File]::ReadAllBytes($resolvedPath)
        }
        if ($rawBytes.Length -eq 0) {
            throw "密钥文件为空"
        }
        return [Convert]::ToBase64String($rawBytes)
    }
    finally {
        if ($null -ne $rawBytes) {
            [Array]::Clear($rawBytes, 0, $rawBytes.Length)
        }
        $enteredPath = $null
        $resolvedPath = $null
    }
}

$serverRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $serverRoot ".."))
$environmentNames = @(
    "AI_FRIEND_DR_PUBLISH_REGION",
    "AI_FRIEND_DR_PUBLISH_BUCKET",
    "AI_FRIEND_DR_PUBLISH_SECRET_ID",
    "AI_FRIEND_DR_PUBLISH_SECRET_KEY",
    "AI_FRIEND_DR_PUBLISH_SESSION_TOKEN",
    "AI_FRIEND_DR_EXPORT_SECRET_ID",
    "AI_FRIEND_DR_RESTORE_SECRET_ID",
    "AI_FRIEND_DR_PUBLISH_SNAPSHOT_ID",
    "AI_FRIEND_DR_PUBLISH_CREATED_AT",
    "AI_FRIEND_DR_PUBLISH_PAGE_SIZE",
    "AI_FRIEND_DR_PUBLISH_CONNECT_TIMEOUT",
    "AI_FRIEND_DR_PUBLISH_READ_TIMEOUT",
    "AI_FRIEND_DR_MANIFEST_PRIVATE_KEY_PKCS8_BASE64",
    "AI_FRIEND_DR_MANIFEST_PUBLIC_KEY_X509_BASE64",
    "AI_FRIEND_DR_PREFLIGHT_ONLY",
    "AI_FRIEND_DR_PREFLIGHT_REGION",
    "AI_FRIEND_DR_PREFLIGHT_BUCKET",
    "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_ID",
    "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_KEY",
    "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SESSION_TOKEN",
    "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_ID",
    "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_KEY",
    "AI_FRIEND_DR_PREFLIGHT_EXPORT_SESSION_TOKEN",
    "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_ID",
    "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_KEY",
    "AI_FRIEND_DR_PREFLIGHT_RESTORE_SESSION_TOKEN",
    "AI_FRIEND_DR_PREFLIGHT_CONNECT_TIMEOUT",
    "AI_FRIEND_DR_PREFLIGHT_READ_TIMEOUT",
    "AI_FRIEND_DR_VERIFY_REGION",
    "AI_FRIEND_DR_VERIFY_BUCKET",
    "AI_FRIEND_DR_VERIFY_SNAPSHOT_ID",
    "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_ID",
    "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_KEY",
    "AI_FRIEND_DR_VERIFY_RESTORE_SESSION_TOKEN",
    "AI_FRIEND_DR_VERIFY_EXPORT_SECRET_ID",
    "AI_FRIEND_DR_VERIFY_EXPORT_KEY_ID",
    "AI_FRIEND_DR_VERIFY_EXPORT_KEY_BASE64",
    "AI_FRIEND_DR_VERIFY_MANIFEST_PUBLIC_KEY_X509_BASE64",
    "AI_FRIEND_DR_VERIFY_CONNECT_TIMEOUT",
    "AI_FRIEND_DR_VERIFY_READ_TIMEOUT"
)

$publisherSecretId = $null
$publisherSecretKey = $null
$publisherSessionToken = $null
$exportSecretId = $null
$exportSecretKey = $null
$exportSessionToken = $null
$restoreSecretId = $null
$restoreSecretKey = $null
$restoreSessionToken = $null
$exportKeyId = $null
$exportEncryptionKeyBase64 = $null
$privateKeyBase64 = $null
$publicKeyBase64 = $null

try {
    if ($PreflightOnly -and $VerifyRestoreOnly) {
        throw "只读预检与快照恢复校验不能同时执行"
    }
    if ([string]::IsNullOrWhiteSpace($Bucket)) {
        $Bucket = Read-Host "请输入完整私有 COS Bucket 名称"
    }
    if ($PreflightOnly) {
        $publisherSecretId = Read-SecretText "请输入快照发布专用 CAM SecretId"
        $publisherSecretKey = Read-SecretText "请输入快照发布专用 CAM SecretKey"
        $publisherSessionToken = Read-SecretText "请输入发布身份临时安全令牌；没有则直接回车"
        $exportSecretId = Read-SecretText "请输入运行时导出 CAM SecretId"
        $exportSecretKey = Read-SecretText "请输入运行时导出 CAM SecretKey"
        $exportSessionToken = Read-SecretText "请输入导出身份临时安全令牌；没有则直接回车"
        $restoreSecretId = Read-SecretText "请输入运行时恢复 CAM SecretId"
        $restoreSecretKey = Read-SecretText "请输入运行时恢复 CAM SecretKey"
        $restoreSessionToken = Read-SecretText "请输入恢复身份临时安全令牌；没有则直接回车"

        $env:AI_FRIEND_DR_PREFLIGHT_ONLY = "true"
        $env:AI_FRIEND_DR_PREFLIGHT_REGION = $Region
        $env:AI_FRIEND_DR_PREFLIGHT_BUCKET = $Bucket
        $env:AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_ID = $publisherSecretId
        $env:AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_KEY = $publisherSecretKey
        $env:AI_FRIEND_DR_PREFLIGHT_PUBLISH_SESSION_TOKEN = $publisherSessionToken
        $env:AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_ID = $exportSecretId
        $env:AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_KEY = $exportSecretKey
        $env:AI_FRIEND_DR_PREFLIGHT_EXPORT_SESSION_TOKEN = $exportSessionToken
        $env:AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_ID = $restoreSecretId
        $env:AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_KEY = $restoreSecretKey
        $env:AI_FRIEND_DR_PREFLIGHT_RESTORE_SESSION_TOKEN = $restoreSessionToken
        $env:AI_FRIEND_DR_PREFLIGHT_CONNECT_TIMEOUT = $ConnectTimeout
        $env:AI_FRIEND_DR_PREFLIGHT_READ_TIMEOUT = $ReadTimeout

        Write-Host "准备执行三套 CAM 只读预检；不会写入或删除 COS 对象"
        Push-Location $serverRoot
        try {
            & .\mvnw.cmd -q -DskipTests compile '-Dexec.mainClass=com.aifriend.retention.infrastructure.DisasterRecoverySnapshotPublisherCli' org.codehaus.mojo:exec-maven-plugin:3.5.0:java
            if ($LASTEXITCODE -ne 0) {
                throw "COS 灾备三身份只读预检进程执行失败"
            }
        }
        finally {
            Pop-Location
        }
        return
    }
    if ($VerifyRestoreOnly) {
        if ([string]::IsNullOrWhiteSpace($SnapshotId)) {
            $SnapshotId = Read-Host "请输入需要校验的固定快照编号"
        }
        $restoreSecretId = Read-SecretText "请输入运行时恢复 CAM SecretId"
        $restoreSecretKey = Read-SecretText "请输入运行时恢复 CAM SecretKey"
        $restoreSessionToken = Read-SecretText "请输入恢复身份临时安全令牌；没有则直接回车"
        $exportSecretId = Read-SecretText "请输入运行时导出 CAM SecretId（只用于身份分离校验）"
        $exportKeyId = Read-Host "请输入灾备信封 AES 密钥编号"
        $exportEncryptionKeyBase64 = Read-SecretText "请输入灾备信封 AES 密钥标准 Base64"
        $publicKeyBase64 = Read-DerKeyBase64 -Prompt "请输入 Ed25519 X.509 公钥文件路径" -ExpectedPemLabel "PUBLIC KEY" -MustStayOutsideRepository $false -RepositoryRoot $repositoryRoot

        $env:AI_FRIEND_DR_VERIFY_REGION = $Region
        $env:AI_FRIEND_DR_VERIFY_BUCKET = $Bucket
        $env:AI_FRIEND_DR_VERIFY_SNAPSHOT_ID = $SnapshotId
        $env:AI_FRIEND_DR_VERIFY_RESTORE_SECRET_ID = $restoreSecretId
        $env:AI_FRIEND_DR_VERIFY_RESTORE_SECRET_KEY = $restoreSecretKey
        $env:AI_FRIEND_DR_VERIFY_RESTORE_SESSION_TOKEN = $restoreSessionToken
        $env:AI_FRIEND_DR_VERIFY_EXPORT_SECRET_ID = $exportSecretId
        $env:AI_FRIEND_DR_VERIFY_EXPORT_KEY_ID = $exportKeyId
        $env:AI_FRIEND_DR_VERIFY_EXPORT_KEY_BASE64 = $exportEncryptionKeyBase64
        $env:AI_FRIEND_DR_VERIFY_MANIFEST_PUBLIC_KEY_X509_BASE64 = $publicKeyBase64
        $env:AI_FRIEND_DR_VERIFY_CONNECT_TIMEOUT = $ConnectTimeout
        $env:AI_FRIEND_DR_VERIFY_READ_TIMEOUT = $ReadTimeout

        Write-Host "准备使用独立恢复身份只读校验快照：$SnapshotId"
        Write-Host "不会连接数据库，也不会写入或删除 COS 对象"
        Push-Location $serverRoot
        try {
            & .\mvnw.cmd -q -DskipTests compile '-Dexec.mainClass=com.aifriend.retention.infrastructure.DisasterRecoverySnapshotRestoreVerifierCli' org.codehaus.mojo:exec-maven-plugin:3.5.0:java
            if ($LASTEXITCODE -ne 0) {
                throw "COS 灾备快照只读恢复校验进程执行失败"
            }
        }
        finally {
            Pop-Location
        }
        return
    }
    if ([string]::IsNullOrWhiteSpace($SnapshotId) -and
        [string]::IsNullOrWhiteSpace($CreatedAt)) {
        $snapshotInstant = [DateTimeOffset]::UtcNow
        $SnapshotId = $snapshotInstant.ToString("'snapshot-'yyyyMMdd'T'HHmmss'Z'")
        $CreatedAt = $snapshotInstant.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
    elseif ([string]::IsNullOrWhiteSpace($SnapshotId) -or
            [string]::IsNullOrWhiteSpace($CreatedAt)) {
        throw "重试既有快照时必须同时提供 SnapshotId 和 CreatedAt"
    }

    $publisherSecretId = Read-SecretText "请输入快照发布专用 CAM SecretId"
    $publisherSecretKey = Read-SecretText "请输入快照发布专用 CAM SecretKey"
    $publisherSessionToken = Read-SecretText "请输入临时安全令牌；没有则直接回车"
    $exportSecretId = Read-SecretText "请输入运行时导出 CAM SecretId（只用于身份分离校验）"
    $restoreSecretId = Read-SecretText "请输入运行时恢复 CAM SecretId（只用于身份分离校验）"
    $privateKeyBase64 = Read-DerKeyBase64 -Prompt "请输入仓库外 Ed25519 PKCS#8 私钥文件路径" -ExpectedPemLabel "PRIVATE KEY" -MustStayOutsideRepository $true -RepositoryRoot $repositoryRoot
    $publicKeyBase64 = Read-DerKeyBase64 -Prompt "请输入 Ed25519 X.509 公钥文件路径" -ExpectedPemLabel "PUBLIC KEY" -MustStayOutsideRepository $false -RepositoryRoot $repositoryRoot

    $env:AI_FRIEND_DR_PUBLISH_REGION = $Region
    $env:AI_FRIEND_DR_PUBLISH_BUCKET = $Bucket
    $env:AI_FRIEND_DR_PUBLISH_SECRET_ID = $publisherSecretId
    $env:AI_FRIEND_DR_PUBLISH_SECRET_KEY = $publisherSecretKey
    $env:AI_FRIEND_DR_PUBLISH_SESSION_TOKEN = $publisherSessionToken
    $env:AI_FRIEND_DR_EXPORT_SECRET_ID = $exportSecretId
    $env:AI_FRIEND_DR_RESTORE_SECRET_ID = $restoreSecretId
    $env:AI_FRIEND_DR_PUBLISH_SNAPSHOT_ID = $SnapshotId
    $env:AI_FRIEND_DR_PUBLISH_CREATED_AT = $CreatedAt
    $env:AI_FRIEND_DR_PUBLISH_PAGE_SIZE = $PageSize.ToString(
        [Globalization.CultureInfo]::InvariantCulture)
    $env:AI_FRIEND_DR_PUBLISH_CONNECT_TIMEOUT = $ConnectTimeout
    $env:AI_FRIEND_DR_PUBLISH_READ_TIMEOUT = $ReadTimeout
    $env:AI_FRIEND_DR_MANIFEST_PRIVATE_KEY_PKCS8_BASE64 = $privateKeyBase64
    $env:AI_FRIEND_DR_MANIFEST_PUBLIC_KEY_X509_BASE64 = $publicKeyBase64

    Write-Host "准备发布快照：$SnapshotId"
    Write-Host "固定生成时间：$CreatedAt"
    Push-Location $serverRoot
    try {
        & .\mvnw.cmd -q -DskipTests compile '-Dexec.mainClass=com.aifriend.retention.infrastructure.DisasterRecoverySnapshotPublisherCli' org.codehaus.mojo:exec-maven-plugin:3.5.0:java
        if ($LASTEXITCODE -ne 0) {
            throw "离线快照发布进程执行失败"
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($environmentName in $environmentNames) {
        Remove-Item -LiteralPath "Env:$environmentName" -ErrorAction SilentlyContinue
    }
    $publisherSecretId = $null
    $publisherSecretKey = $null
    $publisherSessionToken = $null
    $exportSecretId = $null
    $exportSecretKey = $null
    $exportSessionToken = $null
    $restoreSecretId = $null
    $restoreSecretKey = $null
    $restoreSessionToken = $null
    $exportKeyId = $null
    $exportEncryptionKeyBase64 = $null
    $privateKeyBase64 = $null
    $publicKeyBase64 = $null
}
