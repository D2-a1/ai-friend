[CmdletBinding()]
param(
    [string]$OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "当前密钥生成脚本只支持 Windows 本机"
}

$serverRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $serverRoot ".."))
$environmentNames = @(
    "AI_FRIEND_DR_KEY_REPOSITORY_ROOT",
    "AI_FRIEND_DR_KEY_OUTPUT_DIRECTORY"
)

try {
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Read-Host "请输入仓库外全新密钥目录的绝对路径"
    }
    $isDriveQualified = $OutputDirectory -match '^[A-Za-z]:[\\/]'
    $isUncQualified = $OutputDirectory -match '^\\\\[^\\]+\\[^\\]+'
    if (-not ($isDriveQualified -or $isUncQualified)) {
        throw "密钥目录必须使用绝对路径"
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

    Push-Location $serverRoot
    try {
        & .\mvnw.cmd -q -DskipTests compile
        if ($LASTEXITCODE -ne 0) {
            throw "灾备清单密钥生成入口编译失败"
        }
    }
    finally {
        Pop-Location
    }

    $createdDirectory = [IO.Directory]::CreateDirectory($resolvedOutputDirectory)
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $aclOutput = & icacls.exe $createdDirectory.FullName `
        /inheritance:r /grant:r "${currentIdentity}:(OI)(CI)(F)" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "密钥目录权限收紧失败：$aclOutput"
    }

    $env:AI_FRIEND_DR_KEY_REPOSITORY_ROOT = $repositoryRoot
    $env:AI_FRIEND_DR_KEY_OUTPUT_DIRECTORY = $createdDirectory.FullName
    Push-Location $serverRoot
    try {
        $classesDirectory = Join-Path $serverRoot "target\classes"
        & java.exe -cp $classesDirectory `
            com.aifriend.retention.infrastructure.DisasterRecoveryManifestKeyGeneratorCli
        if ($LASTEXITCODE -ne 0) {
            throw "灾备清单密钥生成进程执行失败"
        }
    }
    finally {
        Pop-Location
    }

    Write-Host "密钥目录已限制为当前 Windows 账号访问：$($createdDirectory.FullName)"
    Write-Host "私钥不得上传服务器、腾讯云、仓库或聊天；仅在本机离线发布快照时使用。"
}
finally {
    foreach ($environmentName in $environmentNames) {
        Remove-Item -LiteralPath "Env:$environmentName" -ErrorAction SilentlyContinue
    }
    $OutputDirectory = $null
}
