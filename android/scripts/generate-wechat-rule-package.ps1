[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputFile,

    [Parameter(Mandatory = $true)]
    [string]$KeyDirectory,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false

function ConvertTo-NormalizedPathInput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

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
            $normalizedValue = $normalizedValue.Substring(
                1,
                $normalizedValue.Length - 2
            ).Trim().Trim($invisibleCharacters).Trim()
        }
    }
    if ([string]::IsNullOrWhiteSpace($normalizedValue) -or
        $normalizedValue.IndexOfAny([IO.Path]::GetInvalidPathChars()) -ge 0) {
        throw "路径格式无效"
    }
    try {
        if (-not [IO.Path]::IsPathRooted($normalizedValue)) {
            throw "必须使用绝对路径"
        }
        return [IO.Path]::GetFullPath($normalizedValue)
    } catch {
        throw "路径格式无效"
    }
}

function Find-JavaExecutable {
    $javaHomeValue = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
    if (-not [string]::IsNullOrWhiteSpace($javaHomeValue)) {
        $javaFromHome = Join-Path -Path $javaHomeValue -ChildPath "bin\java.exe"
        if (Test-Path -LiteralPath $javaFromHome -PathType Leaf) {
            return $javaFromHome
        }
    }
    $javaCommand = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($null -ne $javaCommand) {
        return $javaCommand.Source
    }
    $androidStudioJava = Join-Path -Path $env:ProgramFiles -ChildPath (
        "Android\Android Studio\jbr\bin\java.exe"
    )
    if (Test-Path -LiteralPath $androidStudioJava -PathType Leaf) {
        return $androidStudioJava
    }
    throw "未找到 Java 17，请先确认 Android Studio 或 JAVA_HOME 可用"
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
    return $normalizedCandidate.Equals(
        $normalizedRoot,
        [StringComparison]::OrdinalIgnoreCase
    ) -or $normalizedCandidate.StartsWith(
        $normalizedRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )
}

$normalizedInput = ConvertTo-NormalizedPathInput -Value $InputFile
$normalizedKeyDirectory = ConvertTo-NormalizedPathInput -Value $KeyDirectory
$normalizedOutputDirectory = ConvertTo-NormalizedPathInput -Value $OutputDirectory
if (-not (Test-Path -LiteralPath $normalizedInput -PathType Leaf)) {
    throw "微信规则包输入配置文件不存在"
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
if ((Test-PathInsideRoot -CandidatePath $normalizedKeyDirectory -RootPath $repositoryRoot) -or
    (Test-PathInsideRoot -CandidatePath $normalizedOutputDirectory -RootPath $repositoryRoot)) {
    throw "微信规则包密钥和输出目录必须位于项目仓库外"
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$generatorSource = Join-Path -Path $scriptDirectory -ChildPath (
    "tools\WechatRulePackageGenerator.java"
)
if (-not (Test-Path -LiteralPath $generatorSource -PathType Leaf)) {
    throw "微信规则包生成器源码缺失"
}
$javaExecutable = Find-JavaExecutable
$generatorArguments = @(
    $generatorSource,
    "--input",
    $normalizedInput,
    "--key-directory",
    $normalizedKeyDirectory,
    "--output-directory",
    $normalizedOutputDirectory
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    # Windows PowerShell 5.1 会先把 Java 标准错误包装成 NativeCommandError；
    # 仅在原生命令调用期间允许捕获，随后仍以退出码和 ASCII 协议判断结果。
    $ErrorActionPreference = "Continue"
    $generatorOutput = @(& $javaExecutable $generatorArguments 2>&1)
    $generatorExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($generatorExitCode -ne 0) {
    $encodedError = $generatorOutput |
        ForEach-Object { $_.ToString() } |
        Where-Object { $_.StartsWith("ERROR_BASE64=") } |
        Select-Object -Last 1
    if ($null -ne $encodedError) {
        try {
            $decodedError = [Text.Encoding]::UTF8.GetString(
                [Convert]::FromBase64String(
                    $encodedError.Substring("ERROR_BASE64=".Length)
                )
            )
            throw "微信规则包生成失败：$decodedError"
        } catch [FormatException] {
            throw "微信规则包生成失败；错误信息无法解码"
        }
    }
    throw "微信规则包生成失败；生成器未返回可识别错误"
}
if (-not ($generatorOutput | ForEach-Object { $_.ToString() } |
        Where-Object { $_ -eq "RESULT=SUCCESS" })) {
    throw "微信规则包生成失败；生成器未返回成功标记"
}

Write-Output "微信规则包生成成功"
Write-Output (
    "规则包资产目录：" +
    (Join-Path -Path $normalizedOutputDirectory -ChildPath "assets")
)
Write-Output (
    "非秘密 Release 配置：" +
    (Join-Path -Path $normalizedOutputDirectory -ChildPath "wechat-rule-release.json")
)
Write-Output "私钥已保存在独立密钥目录；请勿上传、提交或发送。"
