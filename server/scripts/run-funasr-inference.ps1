[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDirectory,
    [Parameter(Mandatory = $true)]
    [string]$FunAsrRepositoryDirectory,
    [Parameter(Mandatory = $true)]
    [string]$TrainingRunDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9-]{0,47}$")]
    [string]$InferenceName,
    [string]$PythonExecutable = "python",
    [ValidatePattern("^(cpu|cuda(?::[0-9]+)?)$")]
    [string]$Device = "cpu",
    [ValidateRange(1, 128)]
    [int]$BatchSize = 1,
    [ValidateRange(1, 64)]
    [int]$Ncpu = 1,
    [ValidateRange(60, 604800)]
    [int]$InferenceTimeoutSeconds = 86400,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-AbsoluteDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$PathText,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $isDriveQualified = $PathText -match "^[A-Za-z]:[\\/]"
    $isUncQualified = $PathText -match "^\\\\[^\\]+\\[^\\]+"
    if (-not $isDriveQualified -and -not $isUncQualified) {
        throw "$Description 必须使用绝对路径"
    }
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PathText).Path)
    if (-not [IO.Directory]::Exists($resolved)) {
        throw "$Description 不存在"
    }
    return $resolved
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

function Read-JsonObject {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Description
    )

    try {
        return Get-Content -LiteralPath $FilePath -Raw -Encoding UTF8 |
            ConvertFrom-Json
    }
    catch {
        throw "$Description 不是合法 JSON"
    }
}

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$SourceName
    )

    if ($Object.PSObject.Properties.Name -notcontains $Name) {
        throw "$SourceName 缺少字段：$Name"
    }
    return $Object.$Name
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$FilePath)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($FilePath)
    try {
        $hashBytes = $sha256.ComputeHash($stream)
        return [BitConverter]::ToString($hashBytes).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}

function Get-TextSha256Hex {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        return [BitConverter]::ToString($hashBytes).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Resolve-Executable {
    param([Parameter(Mandatory = $true)][string]$Executable)

    if ([IO.File]::Exists($Executable)) {
        return [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Executable).Path)
    }
    $command = Get-Command -Name $Executable -CommandType Application -ErrorAction Stop
    return $command.Source
}

function ConvertTo-ProcessArgument {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append('"')
    $backslashCount = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) {
            $backslashCount++
            continue
        }
        if ($character -eq [char]34) {
            [void]$builder.Append(('\\' * ($backslashCount * 2 + 1)))
            [void]$builder.Append('"')
        }
        else {
            [void]$builder.Append(('\\' * $backslashCount))
            [void]$builder.Append($character)
        }
        $backslashCount = 0
    }
    [void]$builder.Append(('\\' * ($backslashCount * 2)))
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-FunAsrInference {
    param(
        [Parameter(Mandatory = $true)][string]$PythonPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )

    $argumentLine = [string]::Join(" ", @(
        $Arguments | ForEach-Object { ConvertTo-ProcessArgument -Value $_ }
    ))
    $process = Start-Process -FilePath $PythonPath -ArgumentList $argumentLine `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru
    try {
        [void]$process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "FunASR 离线推理超时，timeoutSeconds=$TimeoutSeconds"
        }
        $process.WaitForExit()
        $process.Refresh()
        if ($process.ExitCode -ne 0) {
            throw "FunASR 离线推理失败，exitCode=$($process.ExitCode)"
        }
    }
    finally {
        $process.Dispose()
    }
}

function Write-Utf8WithoutBom {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )

    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($FilePath, $Content, $encoding)
}

function Read-ValidationAudioList {
    param(
        [Parameter(Mandatory = $true)][string]$DatasetRoot,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][int]$ExpectedCount,
        [Parameter(Mandatory = $true)]$LabelsByOrder
    )

    [string[]]$lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedCount) {
        throw "验证音频清单数量与 dataset.json 不一致"
    }
    $ids = New-Object "System.Collections.Generic.List[string]"
    $audioPaths = New-Object "System.Collections.Generic.List[string]"
    $audioDigestLines = New-Object "System.Collections.Generic.List[string]"
    $seenIds = @{}
    $datasetPrefix = $DatasetRoot.TrimEnd([char]92, [char]47) + [IO.Path]::DirectorySeparatorChar
    foreach ($line in $lines) {
        $separator = $line.IndexOf([char]9)
        if ($separator -le 0 -or $separator -ge $line.Length - 1) {
            throw "验证音频清单必须使用制表符分隔编号和相对路径"
        }
        $utteranceId = $line.Substring(0, $separator)
        $relativePath = $line.Substring($separator + 1)
        if ($utteranceId -notmatch "^utt_([0-9]{6})$") {
            throw "验证音频清单包含非法语句编号"
        }
        $memberOrder = [int]$Matches[1]
        if ($seenIds.ContainsKey($utteranceId) -or
            [IO.Path]::IsPathRooted($relativePath)) {
            throw "验证音频清单包含非法、重复编号或绝对路径"
        }
        if (-not $LabelsByOrder.ContainsKey($memberOrder)) {
            throw "验证音频清单缺少对应标签"
        }
        $label = $LabelsByOrder[$memberOrder]
        if ([string]$label.split -ne "VALIDATION" -or
            [string]$label.audioFile -ne $relativePath) {
            throw "验证音频清单与标签的分组或路径不一致"
        }
        $audioPath = [IO.Path]::GetFullPath((Join-Path $DatasetRoot $relativePath))
        if (-not $audioPath.StartsWith($datasetPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not [IO.File]::Exists($audioPath) -or
            (Get-Item -LiteralPath $audioPath).Length -le 0) {
            throw "验证音频路径越界、缺失或为空"
        }
        Assert-NotReparsePoint -PathText $audioPath -Description "验证音频文件"
        $audioHash = Get-Sha256Hex -FilePath $audioPath
        if ($audioHash -ne [string]$label.audioSha256) {
            throw "验证音频内容摘要与标签不一致"
        }
        $seenIds[$utteranceId] = $true
        $ids.Add($utteranceId)
        $audioPaths.Add($audioPath)
        $audioDigestLines.Add("$utteranceId|$audioHash")
    }
    return [pscustomobject]@{
        Ids = [string[]]$ids.ToArray()
        AudioPaths = [string[]]$audioPaths.ToArray()
        Digest = Get-TextSha256Hex -Text ([string]::Join("`n", $audioDigestLines))
    }
}

function Read-LabelsByOrder {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][int]$ExpectedCount
    )

    [string[]]$lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedCount) {
        throw "labels.jsonl 数量与 dataset.json 不一致"
    }
    $labelsByOrder = @{}
    foreach ($line in $lines) {
        try {
            $label = $line | ConvertFrom-Json
        }
        catch {
            throw "labels.jsonl 包含非法 JSON"
        }
        $memberOrder = [int](Get-RequiredProperty -Object $label -Name "memberOrder" `
            -SourceName "labels.jsonl")
        $audioSha256 = [string](Get-RequiredProperty -Object $label -Name "audioSha256" `
            -SourceName "labels.jsonl")
        if ($memberOrder -lt 0 -or $memberOrder -ge $ExpectedCount -or
            $labelsByOrder.ContainsKey($memberOrder) -or
            $audioSha256 -notmatch "^[0-9a-f]{64}$") {
            throw "labels.jsonl 包含非法或重复成员"
        }
        [void](Get-RequiredProperty -Object $label -Name "audioFile" `
            -SourceName "labels.jsonl")
        [void](Get-RequiredProperty -Object $label -Name "split" `
            -SourceName "labels.jsonl")
        $labelsByOrder[$memberOrder] = $label
    }
    return $labelsByOrder
}

function Read-OfficialHypotheses {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ExpectedIds
    )

    [string[]]$lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedIds.Count) {
        throw "FunASR 识别结果数量与验证集不一致"
    }
    $textById = @{}
    foreach ($line in $lines) {
        if ($line -match "^(utt_[0-9]{6})$") {
            $utteranceId = $Matches[1]
            $transcript = ""
        }
        elseif ($line -match "^(utt_[0-9]{6})(?:`t| +)(.*)$") {
            $utteranceId = $Matches[1]
            $transcript = $Matches[2]
        }
        else {
            throw "FunASR 识别结果必须以合法语句编号开头"
        }
        if ($ExpectedIds -notcontains $utteranceId -or
            $textById.ContainsKey($utteranceId) -or
            $transcript.Length -gt 8192 -or
            $transcript -match "[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]") {
            throw "FunASR 识别结果包含未知、重复或异常文字"
        }
        $textById[$utteranceId] = $transcript
    }
    $canonicalLines = New-Object "System.Collections.Generic.List[string]"
    foreach ($expectedId in $ExpectedIds) {
        if (-not $textById.ContainsKey($expectedId)) {
            throw "FunASR 识别结果缺少验证集语句"
        }
        $canonicalLines.Add("$expectedId`t$([string]$textById[$expectedId])")
    }
    return [string]::Join([Environment]::NewLine, $canonicalLines)
}

$datasetRoot = Resolve-AbsoluteDirectory -PathText $DatasetDirectory `
    -Description "训练数据目录"
$funAsrRepositoryRoot = Resolve-AbsoluteDirectory `
    -PathText $FunAsrRepositoryDirectory -Description "FunASR 源码目录"
$trainingRunRoot = Resolve-AbsoluteDirectory -PathText $TrainingRunDirectory `
    -Description "训练结果目录"
Assert-NotReparsePoint -PathText $datasetRoot -Description "训练数据目录"
Assert-NotReparsePoint -PathText $funAsrRepositoryRoot -Description "FunASR 源码目录"
Assert-NotReparsePoint -PathText $trainingRunRoot -Description "训练结果目录"

$datasetManifestPath = Join-Path $datasetRoot "dataset.json"
$labelsPath = Join-Path $datasetRoot "labels.jsonl"
$validationWavScpPath = Join-Path $datasetRoot "funasr/val_wav.scp"
$conversionManifestPath = Join-Path $datasetRoot "funasr/jsonl/conversion.json"
$validationJsonlPath = Join-Path $datasetRoot "funasr/jsonl/val.jsonl"
$trainingRunManifestPath = Join-Path $trainingRunRoot "training-run.json"
$trainedModelPath = Join-Path $trainingRunRoot "model.pt"
$trainedConfigurationPath = Join-Path $trainingRunRoot "configuration.json"
if (-not [IO.File]::Exists($trainedConfigurationPath)) {
    $trainedConfigurationPath = Join-Path $trainingRunRoot "config.yaml"
}
$inferenceEntryPath = Join-Path $funAsrRepositoryRoot "funasr/bin/inference.py"
foreach ($requiredFile in @(
        $datasetManifestPath,
        $labelsPath,
        $validationWavScpPath,
        $conversionManifestPath,
        $validationJsonlPath,
        $trainingRunManifestPath,
        $trainedModelPath,
        $trainedConfigurationPath,
        $inferenceEntryPath
    )) {
    if (-not [IO.File]::Exists($requiredFile)) {
        throw "离线推理所需文件不完整"
    }
    Assert-NotReparsePoint -PathText $requiredFile -Description "离线推理输入文件"
}

$dataset = Read-JsonObject -FilePath $datasetManifestPath -Description "dataset.json"
$conversion = Read-JsonObject -FilePath $conversionManifestPath `
    -Description "conversion.json"
$trainingRun = Read-JsonObject -FilePath $trainingRunManifestPath `
    -Description "training-run.json"
if ([string](Get-RequiredProperty -Object $dataset -Name "formatVersion" `
        -SourceName "dataset.json") -ne "voice-training-input-v3" -or
    [string](Get-RequiredProperty -Object $dataset -Name "trainingFormatVersion" `
        -SourceName "dataset.json") -ne "funasr-scp-v2" -or
    [string](Get-RequiredProperty -Object $dataset -Name "funAsrValidationWavScp" `
        -SourceName "dataset.json") -ne "funasr/val_wav.scp") {
    throw "训练数据格式或验证音频清单路径无效"
}
if ([string](Get-RequiredProperty -Object $conversion -Name "formatVersion" `
        -SourceName "conversion.json") -ne "funasr-jsonl-v1" -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "formatVersion" `
        -SourceName "training-run.json") -ne "funasr-training-run-v1") {
    throw "JSONL 转换或训练结果格式不受支持"
}

$sampleCount = [int](Get-RequiredProperty -Object $dataset -Name "sampleCount" `
    -SourceName "dataset.json")
$validationCount = [int](Get-RequiredProperty -Object $dataset `
    -Name "validationSampleCount" -SourceName "dataset.json")
if ($sampleCount -lt 2 -or $validationCount -lt 1 -or
    [int](Get-RequiredProperty -Object $conversion -Name "validationSampleCount" `
        -SourceName "conversion.json") -ne $validationCount -or
    [int](Get-RequiredProperty -Object $trainingRun -Name "validationSampleCount" `
        -SourceName "training-run.json") -ne $validationCount) {
    throw "数据集、转换清单与训练结果的验证集数量不一致"
}

$datasetHash = Get-Sha256Hex -FilePath $datasetManifestPath
$labelsHash = Get-Sha256Hex -FilePath $labelsPath
$validationWavScpHash = Get-Sha256Hex -FilePath $validationWavScpPath
$conversionHash = Get-Sha256Hex -FilePath $conversionManifestPath
$validationJsonlHash = Get-Sha256Hex -FilePath $validationJsonlPath
$trainingRunManifestHash = Get-Sha256Hex -FilePath $trainingRunManifestPath
$trainedModelHash = Get-Sha256Hex -FilePath $trainedModelPath
$trainedConfigurationHash = Get-Sha256Hex -FilePath $trainedConfigurationPath
$inferenceEntryHash = Get-Sha256Hex -FilePath $inferenceEntryPath
$trainingRunName = [string](Get-RequiredProperty -Object $trainingRun -Name "runName" `
    -SourceName "training-run.json")
if ($trainingRunName -ne (Split-Path -Leaf $trainingRunRoot) -or
    [string](Get-RequiredProperty -Object $conversion -Name "datasetJsonSha256" `
        -SourceName "conversion.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $conversion -Name "validationJsonlSha256" `
        -SourceName "conversion.json") -ne $validationJsonlHash -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "datasetJsonSha256" `
        -SourceName "training-run.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "conversionJsonSha256" `
        -SourceName "training-run.json") -ne $conversionHash -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "validationJsonlSha256" `
        -SourceName "training-run.json") -ne $validationJsonlHash -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "trainedModelSha256" `
        -SourceName "training-run.json") -ne $trainedModelHash -or
    [string](Get-RequiredProperty -Object $trainingRun -Name "trainedConfigurationSha256" `
        -SourceName "training-run.json") -ne $trainedConfigurationHash -or
    [bool](Get-RequiredProperty -Object $trainingRun -Name "candidateRegistered" `
        -SourceName "training-run.json") -or
    [bool](Get-RequiredProperty -Object $trainingRun -Name "runtimeReplaced" `
        -SourceName "training-run.json")) {
    throw "训练结果与数据集、转换清单或模型摘要不一致"
}

$labelsByOrder = Read-LabelsByOrder -FilePath $labelsPath `
    -ExpectedCount $sampleCount
$validationAudio = Read-ValidationAudioList -DatasetRoot $datasetRoot `
    -FilePath $validationWavScpPath -ExpectedCount $validationCount `
    -LabelsByOrder $labelsByOrder
Write-Host "FunASR 离线推理输入预检通过：验证=$validationCount，训练结果=$trainingRunName"
if ($ValidateOnly) {
    Write-Host "当前为只校验模式，未启动推理，也未创建推理结果"
    return
}

$pythonPath = Resolve-Executable -Executable $PythonExecutable
$inferencesDirectory = Join-Path $datasetRoot "funasr/inferences"
if (-not [IO.Directory]::Exists($inferencesDirectory)) {
    [IO.Directory]::CreateDirectory($inferencesDirectory) | Out-Null
}
Assert-NotReparsePoint -PathText $inferencesDirectory -Description "推理结果目录"
$finalDirectory = Join-Path $inferencesDirectory $InferenceName
if ([IO.Directory]::Exists($finalDirectory) -or [IO.File]::Exists($finalDirectory)) {
    throw "同名推理结果已存在，禁止覆盖"
}
$stagingDirectory = Join-Path $inferencesDirectory `
    (".inference-staging-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
$published = $false
try {
    $rawOutputDirectory = Join-Path $stagingDirectory "official-output"
    [IO.Directory]::CreateDirectory($rawOutputDirectory) | Out-Null
    $arguments = @(
        "-m",
        "funasr.bin.inference",
        "++model=$trainingRunRoot",
        "++input=$validationWavScpPath",
        "++output_dir=$rawOutputDirectory",
        "++device=$Device",
        "++ncpu=$Ncpu",
        "++batch_size=$BatchSize",
        "++disable_log=true"
    )
    Write-Host "正在启动 FunASR 本机离线推理"
    Invoke-FunAsrInference -PythonPath $pythonPath -Arguments $arguments `
        -WorkingDirectory $funAsrRepositoryRoot `
        -TimeoutSeconds $InferenceTimeoutSeconds

    $officialBestDirectory = Join-Path $rawOutputDirectory "1best_recog"
    Assert-NotReparsePoint -PathText $stagingDirectory `
        -Description "推理暂存目录"
    Assert-NotReparsePoint -PathText $rawOutputDirectory `
        -Description "FunASR 官方输出目录"
    if (-not [IO.Directory]::Exists($officialBestDirectory)) {
        throw "FunASR 离线推理未生成 1best_recog 目录"
    }
    Assert-NotReparsePoint -PathText $officialBestDirectory `
        -Description "FunASR 一优识别目录"
    $officialTextPath = Join-Path $rawOutputDirectory "1best_recog/text"
    if (-not [IO.File]::Exists($officialTextPath) -or
        (Get-Item -LiteralPath $officialTextPath).Length -le 0) {
        throw "FunASR 离线推理未生成可核验的 1best_recog/text"
    }
    Assert-NotReparsePoint -PathText $officialTextPath `
        -Description "FunASR 官方识别结果"
    $officialTextHash = Get-Sha256Hex -FilePath $officialTextPath
    $canonicalHypotheses = Read-OfficialHypotheses -FilePath $officialTextPath `
        -ExpectedIds $validationAudio.Ids
    $hypothesisPath = Join-Path $stagingDirectory "hypotheses.txt"
    if ([IO.File]::Exists($hypothesisPath) -or
        [IO.Directory]::Exists($hypothesisPath)) {
        throw "推理子进程越界创建了规范识别结果"
    }
    Write-Utf8WithoutBom -FilePath $hypothesisPath -Content $canonicalHypotheses

    foreach ($requiredFile in @(
            $datasetManifestPath,
            $labelsPath,
            $validationWavScpPath,
            $conversionManifestPath,
            $validationJsonlPath,
            $trainingRunManifestPath,
            $trainedModelPath,
            $trainedConfigurationPath,
            $inferenceEntryPath
        )) {
        Assert-NotReparsePoint -PathText $requiredFile `
            -Description "离线推理输入文件"
    }
    $currentAudioDigestLines = New-Object "System.Collections.Generic.List[string]"
    for ($index = 0; $index -lt $validationAudio.Ids.Count; $index++) {
        Assert-NotReparsePoint -PathText $validationAudio.AudioPaths[$index] `
            -Description "验证音频文件"
        $currentAudioDigestLines.Add(
            "$($validationAudio.Ids[$index])|$(Get-Sha256Hex -FilePath $validationAudio.AudioPaths[$index])")
    }
    $currentAudioDigest = Get-TextSha256Hex `
        -Text ([string]::Join("`n", $currentAudioDigestLines))
    if ((Get-Sha256Hex -FilePath $datasetManifestPath) -ne $datasetHash -or
        (Get-Sha256Hex -FilePath $labelsPath) -ne $labelsHash -or
        (Get-Sha256Hex -FilePath $validationWavScpPath) -ne $validationWavScpHash -or
        (Get-Sha256Hex -FilePath $conversionManifestPath) -ne $conversionHash -or
        (Get-Sha256Hex -FilePath $validationJsonlPath) -ne $validationJsonlHash -or
        (Get-Sha256Hex -FilePath $trainingRunManifestPath) -ne $trainingRunManifestHash -or
        (Get-Sha256Hex -FilePath $trainedModelPath) -ne $trainedModelHash -or
        (Get-Sha256Hex -FilePath $trainedConfigurationPath) -ne $trainedConfigurationHash -or
        (Get-Sha256Hex -FilePath $inferenceEntryPath) -ne $inferenceEntryHash -or
        $currentAudioDigest -ne $validationAudio.Digest) {
        throw "离线推理期间输入发生变化，拒绝发布结果"
    }

    $inferenceManifest = [ordered]@{
        formatVersion = "funasr-offline-inference-v1"
        inferenceName = $InferenceName
        trainingRunName = $trainingRunName
        datasetJsonSha256 = $datasetHash
        labelsJsonlSha256 = $labelsHash
        validationWavScpSha256 = $validationWavScpHash
        validationJsonlSha256 = $validationJsonlHash
        validationAudioSetSha256 = $validationAudio.Digest
        trainingRunJsonSha256 = $trainingRunManifestHash
        trainedModelSha256 = $trainedModelHash
        trainedConfigurationSha256 = $trainedConfigurationHash
        funAsrInferenceEntrySha256 = $inferenceEntryHash
        officialOutputTextSha256 = $officialTextHash
        hypothesisSha256 = Get-Sha256Hex -FilePath $hypothesisPath
        sampleCount = $validationCount
        device = $Device
        batchSize = $BatchSize
        ncpu = $Ncpu
        evaluationCompleted = $false
        candidateRegistered = $false
        runtimeReplaced = $false
    }
    $inferenceManifestPath = Join-Path $stagingDirectory "inference-run.json"
    if ([IO.File]::Exists($inferenceManifestPath) -or
        [IO.Directory]::Exists($inferenceManifestPath)) {
        throw "推理子进程越界创建了结果清单"
    }
    Write-Utf8WithoutBom -FilePath $inferenceManifestPath `
        -Content ($inferenceManifest | ConvertTo-Json)
    Remove-Item -LiteralPath $rawOutputDirectory -Recurse -Force
    [IO.Directory]::Move($stagingDirectory, $finalDirectory)
    $published = $true
    Write-Host "FunASR 本机离线推理完成并发布结果：$InferenceName"
}
finally {
    if (-not $published -and [IO.Directory]::Exists($stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
