[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$AppVersion,

    [Parameter(Mandatory = $true)]
    [string]$WechatVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "当前密钥生成脚本只支持 Windows 本机"
}

$serverRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $serverRoot ".."))
$environmentNames = @(
    "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_REPOSITORY_ROOT",
    "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_OUTPUT_DIRECTORY",
    "AI_FRIEND_WECHAT_ACTION_PLAN_APP_VERSION",
    "AI_FRIEND_WECHAT_ACTION_PLAN_WECHAT_VERSION"
)

try {
    $isDriveQualified = $OutputDirectory -match '^[A-Za-z]:[\\/]'
    $isUncQualified = $OutputDirectory -match '^\\\\[^\\]+\\[^\\]+'
    if (-not ($isDriveQualified -or $isUncQualified)) {
        throw "密钥目录必须使用绝对路径"
    }
    if ($AppVersion -notmatch '^[^\s:]{1,100}$') {
        throw "AppVersion 格式无效"
    }
    if ($WechatVersion -notmatch '^[^\s:]{1,100}$') {
        throw "WechatVersion 格式无效"
    }

    $resolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
    $repositoryPrefix = $repositoryRoot.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedOutputDirectory.Equals(
            $repositoryRoot,
            [StringComparison]::OrdinalIgnoreCase) -or
        $resolvedOutputDirectory.StartsWith(
            $repositoryPrefix,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "密钥目录必须位于项目仓库之外"
    }
    if ([IO.Directory]::Exists($resolvedOutputDirectory) -or
        [IO.File]::Exists($resolvedOutputDirectory)) {
        throw "为防止覆盖旧密钥，请指定一个尚不存在的全新目录"
    }

    Push-Location -LiteralPath $serverRoot
    try {
        & .\mvnw.cmd -q -DskipTests compile
        if ($LASTEXITCODE -ne 0) {
            throw "微信动作计划密钥生成入口编译失败"
        }
    } finally {
        Pop-Location
    }

    $createdDirectory = [IO.Directory]::CreateDirectory($resolvedOutputDirectory)
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $aclArguments = @(
        $createdDirectory.FullName,
        "/inheritance:r",
        "/grant:r",
        "${currentIdentity}:(OI)(CI)(F)"
    )
    $aclOutput = & icacls.exe @aclArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "密钥目录权限收紧失败：$aclOutput"
    }

    $env:AI_FRIEND_WECHAT_ACTION_PLAN_KEY_REPOSITORY_ROOT = $repositoryRoot
    $env:AI_FRIEND_WECHAT_ACTION_PLAN_KEY_OUTPUT_DIRECTORY = $createdDirectory.FullName
    $env:AI_FRIEND_WECHAT_ACTION_PLAN_APP_VERSION = $AppVersion
    $env:AI_FRIEND_WECHAT_ACTION_PLAN_WECHAT_VERSION = $WechatVersion
    Push-Location -LiteralPath $serverRoot
    try {
        $classesDirectory = Join-Path $serverRoot "target\classes"
        & java.exe -cp $classesDirectory com.aifriend.task.infrastructure.WechatActionPlanKeyGeneratorCli
        if ($LASTEXITCODE -ne 0) {
            throw "微信动作计划密钥生成进程执行失败"
        }
    } finally {
        Pop-Location
    }

    Write-Host "输出目录已限制为当前 Windows 账号访问：$($createdDirectory.FullName)"
    Write-Host "服务端私密配置：$(Join-Path $createdDirectory.FullName 'wechat-action-plan-server.env')"
    Write-Host "Android 公开信任配置：$(Join-Path $createdDirectory.FullName 'wechat-action-plan-android-trust.json')"
    Write-Host "server.env 片段含私钥，只能上传到既有云端秘密目录；Android JSON 只含公钥。"
} finally {
    foreach ($environmentName in $environmentNames) {
        Remove-Item -LiteralPath "Env:$environmentName" -ErrorAction SilentlyContinue
    }
    $OutputDirectory = $null
    $AppVersion = $null
    $WechatVersion = $null
}
