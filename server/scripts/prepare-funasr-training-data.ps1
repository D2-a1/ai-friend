[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDirectory,
    [string]$PythonExecutable = "python",
    [ValidateRange(30, 3600)]
    [int]$ConversionTimeoutSeconds = 600,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AbsoluteDirectory {
    param([Parameter(Mandatory = $true)][string]$PathText)

    $isDriveQualified = $PathText -match "^[A-Za-z]:[\\/]"
    $isUncQualified = $PathText -match "^\\\\[^\\]+\\[^\\]+"
    if (-not $isDriveQualified -and -not $isUncQualified) {
        throw "训练数据目录必须使用绝对路径"
    }
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PathText).Path)
    if (-not [IO.Directory]::Exists($resolved)) {
        throw "训练数据目录不存在"
    }
    return $resolved
}

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ($Object.PSObject.Properties.Name -notcontains $Name) {
        throw "dataset.json 缺少字段：$Name"
    }
    return $Object.$Name
}

function Assert-NotReparsePoint {
    param(
        [Parameter(Mandatory = $true)][string]$PathText,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $attributes = [IO.File]::GetAttributes($PathText)
    if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description 不允许使用目录连接或符号链接"
    }
}

function Read-PairedList {
    param(
        [Parameter(Mandatory = $true)][string]$DatasetRoot,
        [Parameter(Mandatory = $true)][string]$WavRelativePath,
        [Parameter(Mandatory = $true)][string]$TextRelativePath,
        [Parameter(Mandatory = $true)][int]$ExpectedCount,
        [Parameter(Mandatory = $true)][string]$ListName
    )

    $wavPath = Join-Path $DatasetRoot $WavRelativePath
    $textPath = Join-Path $DatasetRoot $TextRelativePath
    if (-not [IO.File]::Exists($wavPath) -or -not [IO.File]::Exists($textPath)) {
        throw "$ListName 的 wav.scp 或 text.txt 不存在"
    }

    [string[]]$wavLines = @(Get-Content -LiteralPath $wavPath -Encoding UTF8)
    [string[]]$textLines = @(Get-Content -LiteralPath $textPath -Encoding UTF8)
    if ($wavLines.Count -ne $ExpectedCount -or $textLines.Count -ne $ExpectedCount) {
        throw "$ListName 的清单数量与 dataset.json 不一致"
    }

    $ids = New-Object "System.Collections.Generic.List[string]"
    $sourceById = @{}
    $transcriptById = @{}
    $textIds = New-Object "System.Collections.Generic.List[string]"
    $rootPrefix = $DatasetRoot.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar

    foreach ($line in $wavLines) {
        $separator = $line.IndexOf([char]9)
        if ($separator -le 0 -or $separator -ge $line.Length - 1) {
            throw "$ListName 的 wav.scp 必须使用制表符分隔编号和路径"
        }
        $utteranceId = $line.Substring(0, $separator)
        $sourcePath = $line.Substring($separator + 1)
        if ($utteranceId -notmatch "^utt_[0-9]{6}$") {
            throw "$ListName 包含非法语句编号"
        }
        if ($sourcePath -notmatch "^audio/[0-9]{6}[.]wav$") {
            throw "$ListName 包含非法或非相对 WAV 路径"
        }
        if ($sourceById.ContainsKey($utteranceId)) {
            throw "$ListName 包含重复语句编号"
        }
        $absoluteAudioPath = [IO.Path]::GetFullPath(
            (Join-Path $DatasetRoot ($sourcePath.Replace("/", [IO.Path]::DirectorySeparatorChar)))
        )
        $audioDirectory = [IO.Path]::GetDirectoryName($absoluteAudioPath)
        if (-not $absoluteAudioPath.StartsWith(
                $rootPrefix,
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.File]::Exists($absoluteAudioPath)) {
            throw "$ListName 引用的数据集 WAV 不存在或越过目录边界"
        }
        Assert-NotReparsePoint -PathText $audioDirectory -Description "音频目录"
        Assert-NotReparsePoint -PathText $absoluteAudioPath -Description "音频文件"
        $ids.Add($utteranceId)
        $sourceById[$utteranceId] = $sourcePath
    }

    foreach ($line in $textLines) {
        $separator = $line.IndexOf([char]9)
        if ($separator -le 0 -or $separator -ge $line.Length - 1) {
            throw "$ListName 的 text.txt 必须使用制表符分隔编号和文字"
        }
        $utteranceId = $line.Substring(0, $separator)
        $transcript = $line.Substring($separator + 1)
        if ($utteranceId -notmatch "^utt_[0-9]{6}$" -or
            [string]::IsNullOrWhiteSpace($transcript)) {
            throw "$ListName 包含非法语句编号或空复核文字"
        }
        if ($transcriptById.ContainsKey($utteranceId)) {
            throw "$ListName 的 text.txt 包含重复语句编号"
        }
        $textIds.Add($utteranceId)
        $transcriptById[$utteranceId] = $transcript
    }

    if ([string]::Join("|", $ids) -ne [string]::Join("|", $textIds)) {
        throw "$ListName 的 wav.scp 与 text.txt 编号或顺序不一致"
    }
    return [pscustomobject]@{
        Ids = [string[]]$ids.ToArray()
        Sources = $sourceById
        Transcripts = $transcriptById
    }
}

function Assert-SetEquals {
    param(
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string[]]$Actual,
        [Parameter(Mandatory = $true)][string]$Message
    )

    $expectedSorted = @($Expected | Sort-Object)
    $actualSorted = @($Actual | Sort-Object)
    if ([string]::Join("|", $expectedSorted) -ne
        [string]::Join("|", $actualSorted)) {
        throw $Message
    }
}

function Test-JsonlOutput {
    param(
        [Parameter(Mandatory = $true)][string]$JsonlPath,
        [Parameter(Mandatory = $true)][string[]]$ExpectedIds,
        [Parameter(Mandatory = $true)]$ExpectedSources,
        [Parameter(Mandatory = $true)]$ExpectedTranscripts,
        [Parameter(Mandatory = $true)][string]$ListName
    )

    [string[]]$lines = @(Get-Content -LiteralPath $JsonlPath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedIds.Count) {
        throw "$ListName JSONL 数量不正确"
    }
    $actualIds = New-Object "System.Collections.Generic.List[string]"
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            throw "$ListName JSONL 包含空行"
        }
        try {
            $entry = $line | ConvertFrom-Json
        }
        catch {
            throw "$ListName JSONL 不是合法 JSON"
        }
        foreach ($field in @("key", "source", "source_len", "target", "target_len")) {
            if ($entry.PSObject.Properties.Name -notcontains $field) {
                throw "$ListName JSONL 缺少字段：$field"
            }
        }
        $key = [string]$entry.key
        if ($ExpectedIds -notcontains $key -or $actualIds.Contains($key)) {
            throw "$ListName JSONL 包含未知或重复语句编号"
        }
        if ([string]$entry.source -ne [string]$ExpectedSources[$key]) {
            throw "$ListName JSONL 的音频路径与源清单不一致"
        }
        if ([string]$entry.target -ne [string]$ExpectedTranscripts[$key]) {
            throw "$ListName JSONL 的复核文字与源清单不一致"
        }
        if ([int64]$entry.source_len -le 0 -or
            [int64]$entry.target_len -le 0 -or
            [string]::IsNullOrWhiteSpace([string]$entry.target)) {
            throw "$ListName JSONL 的长度或复核文字无效"
        }
        $actualIds.Add($key)
    }
    Assert-SetEquals -Expected $ExpectedIds -Actual $actualIds.ToArray() `
        -Message "$ListName JSONL 的语句编号集合不完整"
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$FilePath)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    $fileStream = [IO.File]::OpenRead($FilePath)
    try {
        $hashBytes = $sha256.ComputeHash($fileStream)
        return [BitConverter]::ToString($hashBytes).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $fileStream.Dispose()
        $sha256.Dispose()
    }
}

function Resolve-PythonExecutable {
    param([Parameter(Mandatory = $true)][string]$Executable)

    if ([IO.File]::Exists($Executable)) {
        return [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Executable).Path)
    }
    $command = Get-Command -Name $Executable -CommandType Application -ErrorAction Stop
    return $command.Source
}

function Invoke-Scp2Jsonl {
    param(
        [Parameter(Mandatory = $true)][string]$PythonPath,
        [Parameter(Mandatory = $true)][string]$WavRelativePath,
        [Parameter(Mandatory = $true)][string]$TextRelativePath,
        [Parameter(Mandatory = $true)][string]$OutputRelativePath,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$PhaseName
    )

    Write-Host "正在生成 $PhaseName JSONL"
    $arguments = @(
        "-m",
        "funasr.datasets.audio_datasets.scp2jsonl",
        "++scp_file_list=[$WavRelativePath,$TextRelativePath]",
        "++data_type_list=[source,target]",
        "++jsonl_file_out=$OutputRelativePath"
    )
    $process = Start-Process -FilePath $PythonPath -ArgumentList $arguments `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru
    try {
        [void]$process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "FunASR $PhaseName JSONL 转换超时，timeoutSeconds=$TimeoutSeconds"
        }
        $process.WaitForExit()
        $process.Refresh()
        $processExitCode = $process.ExitCode
        if ($processExitCode -ne 0) {
            throw "FunASR $PhaseName JSONL 转换失败，exitCode=$processExitCode"
        }
    }
    finally {
        $process.Dispose()
    }
}

$datasetRoot = Resolve-AbsoluteDirectory -PathText $DatasetDirectory
$funAsrDirectory = Join-Path $datasetRoot "funasr"
$audioDirectory = Join-Path $datasetRoot "audio"
$manifestPath = Join-Path $datasetRoot "dataset.json"
if (-not [IO.File]::Exists($manifestPath)) {
    throw "训练数据目录缺少 dataset.json"
}
if (-not [IO.Directory]::Exists($funAsrDirectory) -or
    -not [IO.Directory]::Exists($audioDirectory)) {
    throw "训练数据目录缺少 funasr 或 audio 子目录"
}
Assert-NotReparsePoint -PathText $datasetRoot -Description "训练数据目录"
Assert-NotReparsePoint -PathText $funAsrDirectory -Description "FunASR 清单目录"
Assert-NotReparsePoint -PathText $audioDirectory -Description "音频目录"
Assert-NotReparsePoint -PathText $manifestPath -Description "dataset.json"

try {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
}
catch {
    throw "dataset.json 不是合法 JSON"
}

if ([string](Get-RequiredProperty -Object $manifest -Name "formatVersion") -ne
        "voice-training-input-v3" -or
    [string](Get-RequiredProperty -Object $manifest -Name "trainingFormatVersion") -ne
        "funasr-scp-v2" -or
    -not [bool](Get-RequiredProperty -Object $manifest -Name "jsonlGenerationRequired")) {
    throw "训练数据格式不是当前支持的 voice-training-input-v3/funasr-scp-v2"
}

$sampleCount = [int](Get-RequiredProperty -Object $manifest -Name "sampleCount")
$trainCount = [int](Get-RequiredProperty -Object $manifest -Name "trainSampleCount")
$validationCount = [int](Get-RequiredProperty -Object $manifest -Name "validationSampleCount")
if ($sampleCount -lt 2 -or $trainCount -lt 1 -or $validationCount -lt 1 -or
    $sampleCount -ne $trainCount + $validationCount) {
    throw "dataset.json 的训练/验证数量无效"
}

$expectedPaths = @{
    funAsrWavScp = "funasr/all_wav.scp"
    funAsrText = "funasr/all_text.txt"
    funAsrTrainWavScp = "funasr/train_wav.scp"
    funAsrTrainText = "funasr/train_text.txt"
    funAsrValidationWavScp = "funasr/val_wav.scp"
    funAsrValidationText = "funasr/val_text.txt"
}
foreach ($field in $expectedPaths.Keys) {
    if ([string](Get-RequiredProperty -Object $manifest -Name $field) -ne
        $expectedPaths[$field]) {
        throw "dataset.json 的清单路径与当前格式不一致：$field"
    }
}

$allList = Read-PairedList -DatasetRoot $datasetRoot `
    -WavRelativePath $expectedPaths.funAsrWavScp `
    -TextRelativePath $expectedPaths.funAsrText `
    -ExpectedCount $sampleCount -ListName "全量"
$trainList = Read-PairedList -DatasetRoot $datasetRoot `
    -WavRelativePath $expectedPaths.funAsrTrainWavScp `
    -TextRelativePath $expectedPaths.funAsrTrainText `
    -ExpectedCount $trainCount -ListName "训练"
$validationList = Read-PairedList -DatasetRoot $datasetRoot `
    -WavRelativePath $expectedPaths.funAsrValidationWavScp `
    -TextRelativePath $expectedPaths.funAsrValidationText `
    -ExpectedCount $validationCount -ListName "验证"

$overlap = @($trainList.Ids | Where-Object { $validationList.Ids -contains $_ })
if ($overlap.Count -ne 0) {
    throw "训练集和验证集包含重复语句编号"
}
$splitIds = @($trainList.Ids) + @($validationList.Ids)
Assert-SetEquals -Expected $allList.Ids -Actual $splitIds `
    -Message "训练集与验证集不能完整覆盖全量清单"

Write-Host "FunASR 输入预检通过：总数=$sampleCount，训练=$trainCount，验证=$validationCount"
if ($ValidateOnly) {
    Write-Host "当前为只校验模式，未调用 Python，也未生成 JSONL"
    return
}

$pythonPath = Resolve-PythonExecutable -Executable $PythonExecutable
$finalDirectory = Join-Path $funAsrDirectory "jsonl"
if ([IO.Directory]::Exists($finalDirectory) -or [IO.File]::Exists($finalDirectory)) {
    throw "funasr/jsonl 已存在，禁止覆盖"
}

$stagingName = ".jsonl-staging-" + [Guid]::NewGuid().ToString("N")
$stagingDirectory = Join-Path $funAsrDirectory $stagingName
[IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
$published = $false
try {
    $trainOutputRelative = "funasr/$stagingName/train.jsonl"
    $validationOutputRelative = "funasr/$stagingName/val.jsonl"
    Invoke-Scp2Jsonl -PythonPath $pythonPath `
        -WavRelativePath $expectedPaths.funAsrTrainWavScp `
        -TextRelativePath $expectedPaths.funAsrTrainText `
        -OutputRelativePath $trainOutputRelative `
        -WorkingDirectory $datasetRoot `
        -TimeoutSeconds $ConversionTimeoutSeconds -PhaseName "训练"
    Invoke-Scp2Jsonl -PythonPath $pythonPath `
        -WavRelativePath $expectedPaths.funAsrValidationWavScp `
        -TextRelativePath $expectedPaths.funAsrValidationText `
        -OutputRelativePath $validationOutputRelative `
        -WorkingDirectory $datasetRoot `
        -TimeoutSeconds $ConversionTimeoutSeconds -PhaseName "验证"

    $trainJsonlPath = Join-Path $stagingDirectory "train.jsonl"
    $validationJsonlPath = Join-Path $stagingDirectory "val.jsonl"
    Test-JsonlOutput -JsonlPath $trainJsonlPath `
        -ExpectedIds $trainList.Ids -ExpectedSources $trainList.Sources `
        -ExpectedTranscripts $trainList.Transcripts `
        -ListName "训练"
    Test-JsonlOutput -JsonlPath $validationJsonlPath `
        -ExpectedIds $validationList.Ids `
        -ExpectedSources $validationList.Sources `
        -ExpectedTranscripts $validationList.Transcripts -ListName "验证"

    $conversionManifest = [ordered]@{
        formatVersion = "funasr-jsonl-v1"
        sourceFormatVersion = "voice-training-input-v3"
        sourceTrainingFormatVersion = "funasr-scp-v2"
        datasetJsonSha256 = Get-Sha256Hex -FilePath $manifestPath
        trainSampleCount = $trainCount
        validationSampleCount = $validationCount
        trainJsonl = "train.jsonl"
        validationJsonl = "val.jsonl"
        trainJsonlSha256 = Get-Sha256Hex -FilePath $trainJsonlPath
        validationJsonlSha256 = Get-Sha256Hex -FilePath $validationJsonlPath
        converterModule = "funasr.datasets.audio_datasets.scp2jsonl"
    }
    $manifestJson = $conversionManifest | ConvertTo-Json
    $utf8WithoutBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText(
        (Join-Path $stagingDirectory "conversion.json"),
        $manifestJson,
        $utf8WithoutBom
    )

    [IO.Directory]::Move($stagingDirectory, $finalDirectory)
    $published = $true
    Write-Host "FunASR JSONL 已生成并校验"
}
finally {
    if (-not $published -and [IO.Directory]::Exists($stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
