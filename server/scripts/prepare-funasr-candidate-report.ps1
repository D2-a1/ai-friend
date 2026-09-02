[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDirectory,
    [Parameter(Mandatory = $true)]
    [string]$InferenceDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9-]{0,47}$")]
    [string]$PreparationName,
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

function Write-Utf8WithoutBom {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )

    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($FilePath, $Content, $encoding)
}

function Assert-FileHashUnchanged {
    param(
        [Parameter(Mandatory = $true)]$ExpectedHashes
    )

    foreach ($entry in $ExpectedHashes.GetEnumerator()) {
        Assert-NotReparsePoint -PathText $entry.Key -Description "候选准备输入文件"
        if ((Get-Sha256Hex -FilePath $entry.Key) -ne $entry.Value) {
            throw "候选准备期间输入发生变化，拒绝生成报告"
        }
    }
}

$datasetRoot = Resolve-AbsoluteDirectory -PathText $DatasetDirectory `
    -Description "训练数据目录"
$inferenceRoot = Resolve-AbsoluteDirectory -PathText $InferenceDirectory `
    -Description "离线推理结果目录"
Assert-NotReparsePoint -PathText $datasetRoot -Description "训练数据目录"
Assert-NotReparsePoint -PathText $inferenceRoot -Description "离线推理结果目录"

$funAsrDirectory = Join-Path $datasetRoot "funasr"
$inferencesDirectory = Join-Path $funAsrDirectory "inferences"
if (-not [IO.Directory]::Exists($funAsrDirectory) -or
    -not [IO.Directory]::Exists($inferencesDirectory)) {
    throw "训练数据目录缺少 FunASR 推理结果结构"
}
Assert-NotReparsePoint -PathText $funAsrDirectory -Description "FunASR 目录"
Assert-NotReparsePoint -PathText $inferencesDirectory -Description "推理结果根目录"
$expectedInferenceParent = [IO.Path]::GetFullPath($inferencesDirectory)
$actualInferenceParent = [IO.Path]::GetFullPath(
    [IO.Path]::GetDirectoryName($inferenceRoot))
if (-not [string]::Equals(
        $expectedInferenceParent,
        $actualInferenceParent,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "离线推理结果必须位于当前数据集的 funasr/inferences 目录"
}

$inferenceManifestPath = Join-Path $inferenceRoot "inference-run.json"
$hypothesisPath = Join-Path $inferenceRoot "hypotheses.txt"
$datasetManifestPath = Join-Path $datasetRoot "dataset.json"
$conversionManifestPath = Join-Path $funAsrDirectory "jsonl/conversion.json"
$validationJsonlPath = Join-Path $funAsrDirectory "jsonl/val.jsonl"
$evaluationScriptPath = Join-Path $PSScriptRoot "evaluate-funasr-asr.ps1"
foreach ($requiredFile in @(
        $inferenceManifestPath,
        $hypothesisPath,
        $datasetManifestPath,
        $conversionManifestPath,
        $validationJsonlPath,
        $evaluationScriptPath
    )) {
    if (-not [IO.File]::Exists($requiredFile) -or
        (Get-Item -LiteralPath $requiredFile).Length -le 0) {
        throw "候选准备所需输入文件不完整"
    }
    Assert-NotReparsePoint -PathText $requiredFile -Description "候选准备输入文件"
}

$inferenceManifest = Read-JsonObject -FilePath $inferenceManifestPath `
    -Description "inference-run.json"
if ([string](Get-RequiredProperty -Object $inferenceManifest -Name "formatVersion" `
        -SourceName "inference-run.json") -ne "funasr-offline-inference-v1" -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "inferenceName" `
        -SourceName "inference-run.json") -ne (Split-Path -Leaf $inferenceRoot) -or
    [bool](Get-RequiredProperty -Object $inferenceManifest -Name "evaluationCompleted" `
        -SourceName "inference-run.json") -or
    [bool](Get-RequiredProperty -Object $inferenceManifest -Name "candidateRegistered" `
        -SourceName "inference-run.json") -or
    [bool](Get-RequiredProperty -Object $inferenceManifest -Name "runtimeReplaced" `
        -SourceName "inference-run.json")) {
    throw "离线推理结果状态不允许生成候选准备报告"
}

$trainingRunName = [string](Get-RequiredProperty -Object $inferenceManifest `
    -Name "trainingRunName" -SourceName "inference-run.json")
if ($trainingRunName -notmatch "^[a-z0-9][a-z0-9-]{0,47}$") {
    throw "训练结果名称非法"
}
$trainingRunRoot = Join-Path $funAsrDirectory "training-runs/$trainingRunName"
if (-not [IO.Directory]::Exists($trainingRunRoot)) {
    throw "离线推理绑定的训练结果不存在"
}
Assert-NotReparsePoint -PathText $trainingRunRoot -Description "训练结果目录"
$trainingManifestPath = Join-Path $trainingRunRoot "training-run.json"
$trainedModelPath = Join-Path $trainingRunRoot "model.pt"
$trainedConfigurationPath = Join-Path $trainingRunRoot "configuration.json"
if (-not [IO.File]::Exists($trainedConfigurationPath)) {
    $trainedConfigurationPath = Join-Path $trainingRunRoot "config.yaml"
}
foreach ($trainingFile in @(
        $trainingManifestPath,
        $trainedModelPath,
        $trainedConfigurationPath
    )) {
    if (-not [IO.File]::Exists($trainingFile) -or
        (Get-Item -LiteralPath $trainingFile).Length -le 0) {
        throw "离线推理绑定的训练结果不完整"
    }
    Assert-NotReparsePoint -PathText $trainingFile -Description "训练结果文件"
}

$trainingManifest = Read-JsonObject -FilePath $trainingManifestPath `
    -Description "training-run.json"
if ([string](Get-RequiredProperty -Object $trainingManifest -Name "formatVersion" `
        -SourceName "training-run.json") -ne "funasr-training-run-v1" -or
    [string](Get-RequiredProperty -Object $trainingManifest -Name "runName" `
        -SourceName "training-run.json") -ne $trainingRunName -or
    [bool](Get-RequiredProperty -Object $trainingManifest -Name "candidateRegistered" `
        -SourceName "training-run.json") -or
    [bool](Get-RequiredProperty -Object $trainingManifest -Name "runtimeReplaced" `
        -SourceName "training-run.json")) {
    throw "训练结果状态不允许继续候选准备"
}

$datasetHash = Get-Sha256Hex -FilePath $datasetManifestPath
$conversionHash = Get-Sha256Hex -FilePath $conversionManifestPath
$validationJsonlHash = Get-Sha256Hex -FilePath $validationJsonlPath
$hypothesisHash = Get-Sha256Hex -FilePath $hypothesisPath
$trainingManifestHash = Get-Sha256Hex -FilePath $trainingManifestPath
$trainedModelHash = Get-Sha256Hex -FilePath $trainedModelPath
$trainedConfigurationHash = Get-Sha256Hex -FilePath $trainedConfigurationPath
$sampleCount = [int](Get-RequiredProperty -Object $inferenceManifest `
    -Name "sampleCount" -SourceName "inference-run.json")
if ($sampleCount -lt 1 -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "datasetJsonSha256" `
        -SourceName "inference-run.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "conversionJsonSha256" `
        -SourceName "inference-run.json") -ne $conversionHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "validationJsonlSha256" `
        -SourceName "inference-run.json") -ne $validationJsonlHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "hypothesisSha256" `
        -SourceName "inference-run.json") -ne $hypothesisHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest `
        -Name "trainingRunJsonSha256" -SourceName "inference-run.json") -ne
        $trainingManifestHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest -Name "trainedModelSha256" `
        -SourceName "inference-run.json") -ne $trainedModelHash -or
    [string](Get-RequiredProperty -Object $inferenceManifest `
        -Name "trainedConfigurationSha256" -SourceName "inference-run.json") -ne
        $trainedConfigurationHash -or
    [string](Get-RequiredProperty -Object $trainingManifest -Name "trainedModelSha256" `
        -SourceName "training-run.json") -ne $trainedModelHash -or
    [string](Get-RequiredProperty -Object $trainingManifest -Name "datasetJsonSha256" `
        -SourceName "training-run.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $trainingManifest -Name "conversionJsonSha256" `
        -SourceName "training-run.json") -ne $conversionHash -or
    [string](Get-RequiredProperty -Object $trainingManifest -Name "validationJsonlSha256" `
        -SourceName "training-run.json") -ne $validationJsonlHash -or
    [int](Get-RequiredProperty -Object $trainingManifest -Name "validationSampleCount" `
        -SourceName "training-run.json") -ne $sampleCount -or
    [string](Get-RequiredProperty -Object $trainingManifest `
        -Name "trainedConfigurationSha256" -SourceName "training-run.json") -ne
        $trainedConfigurationHash) {
    throw "推理、训练与数据集摘要不一致"
}

$boundHashes = @{}
foreach ($boundFile in @(
        $inferenceManifestPath,
        $hypothesisPath,
        $datasetManifestPath,
        $conversionManifestPath,
        $validationJsonlPath,
        $trainingManifestPath,
        $trainedModelPath,
        $trainedConfigurationPath,
        $evaluationScriptPath
    )) {
    $boundHashes[$boundFile] = Get-Sha256Hex -FilePath $boundFile
}

$evaluationName = "$PreparationName-evaluation"
$evaluationsDirectory = Join-Path $funAsrDirectory "evaluations"
$evaluationDirectory = Join-Path $evaluationsDirectory $evaluationName
$preparationsDirectory = Join-Path $funAsrDirectory "candidate-preparations"
$finalPreparationDirectory = Join-Path $preparationsDirectory $PreparationName

Write-Host "正在使用既有离线评测器核验推理结果"
if ($ValidateOnly) {
    & $evaluationScriptPath `
        -DatasetDirectory $datasetRoot `
        -HypothesisFile $hypothesisPath `
        -EvaluationName $evaluationName `
        -ValidateOnly
    Assert-FileHashUnchanged -ExpectedHashes $boundHashes
    Write-Host "候选准备输入与评测计算通过；当前未生成任何报告"
    return
}
if ([IO.Directory]::Exists($finalPreparationDirectory) -or
    [IO.File]::Exists($finalPreparationDirectory)) {
    throw "同名候选准备报告已存在，禁止覆盖"
}

if ([IO.File]::Exists($evaluationDirectory)) {
    throw "评测结果路径被同名文件占用"
}
if (-not [IO.Directory]::Exists($evaluationDirectory)) {
    & $evaluationScriptPath `
        -DatasetDirectory $datasetRoot `
        -HypothesisFile $hypothesisPath `
        -EvaluationName $evaluationName
}
if (-not [IO.Directory]::Exists($evaluationsDirectory)) {
    throw "离线评测器未生成评测根目录"
}
Assert-NotReparsePoint -PathText $evaluationsDirectory -Description "离线评测根目录"
Assert-NotReparsePoint -PathText $evaluationDirectory -Description "离线评测结果目录"
$evaluationReportPath = Join-Path $evaluationDirectory "evaluation.json"
$evaluationDetailsPath = Join-Path $evaluationDirectory "details.jsonl"
foreach ($evaluationFile in @($evaluationReportPath, $evaluationDetailsPath)) {
    if (-not [IO.File]::Exists($evaluationFile) -or
        (Get-Item -LiteralPath $evaluationFile).Length -le 0) {
        throw "离线评测结果不完整"
    }
    Assert-NotReparsePoint -PathText $evaluationFile -Description "离线评测结果文件"
}
$evaluationReportHash = Get-Sha256Hex -FilePath $evaluationReportPath
$evaluationDetailsHash = Get-Sha256Hex -FilePath $evaluationDetailsPath

$evaluation = Read-JsonObject -FilePath $evaluationReportPath `
    -Description "evaluation.json"
if ([string](Get-RequiredProperty -Object $evaluation -Name "formatVersion" `
        -SourceName "evaluation.json") -ne "funasr-asr-evaluation-v1" -or
    [string](Get-RequiredProperty -Object $evaluation -Name "evaluationName" `
        -SourceName "evaluation.json") -ne $evaluationName -or
    [string](Get-RequiredProperty -Object $evaluation -Name "metricProtocol" `
        -SourceName "evaluation.json") -ne "funasr-normalized-micro-cer-v1" -or
    [int](Get-RequiredProperty -Object $evaluation -Name "sampleCount" `
        -SourceName "evaluation.json") -ne $sampleCount -or
    [int](Get-RequiredProperty -Object $evaluation -Name "failedSampleCount" `
        -SourceName "evaluation.json") -ne 0 -or
    [bool](Get-RequiredProperty -Object $evaluation -Name "thresholdApplied" `
        -SourceName "evaluation.json") -or
    [string](Get-RequiredProperty -Object $evaluation -Name "datasetJsonSha256" `
        -SourceName "evaluation.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $evaluation -Name "conversionJsonSha256" `
        -SourceName "evaluation.json") -ne $conversionHash -or
    [string](Get-RequiredProperty -Object $evaluation -Name "validationJsonlSha256" `
        -SourceName "evaluation.json") -ne $validationJsonlHash -or
    [string](Get-RequiredProperty -Object $evaluation -Name "hypothesisSha256" `
        -SourceName "evaluation.json") -ne $hypothesisHash) {
    throw "离线评测结果与当前推理输入不一致"
}
$microCer = [double](Get-RequiredProperty -Object $evaluation -Name "microCer" `
    -SourceName "evaluation.json")
$sentenceAccuracy = [double](Get-RequiredProperty -Object $evaluation `
    -Name "sentenceAccuracy" -SourceName "evaluation.json")
if ([double]::IsNaN($microCer) -or [double]::IsInfinity($microCer) -or
    $microCer -lt 0 -or [double]::IsNaN($sentenceAccuracy) -or
    [double]::IsInfinity($sentenceAccuracy) -or $sentenceAccuracy -lt 0 -or
    $sentenceAccuracy -gt 1) {
    throw "离线评测指标不在合法范围"
}
Assert-FileHashUnchanged -ExpectedHashes $boundHashes
Assert-NotReparsePoint -PathText $evaluationReportPath `
    -Description "离线评测结果文件"
Assert-NotReparsePoint -PathText $evaluationDetailsPath `
    -Description "离线评测结果文件"
if ((Get-Sha256Hex -FilePath $evaluationReportPath) -ne $evaluationReportHash -or
    (Get-Sha256Hex -FilePath $evaluationDetailsPath) -ne $evaluationDetailsHash) {
    throw "候选准备期间评测结果发生变化，拒绝生成报告"
}

$candidateReport = [ordered]@{
    formatVersion = "funasr-candidate-preparation-v1"
    preparationName = $PreparationName
    inferenceName = [string]$inferenceManifest.inferenceName
    trainingRunName = $trainingRunName
    evaluationName = $evaluationName
    metricProtocol = [string]$evaluation.metricProtocol
    inferenceRunJsonSha256 = Get-Sha256Hex -FilePath $inferenceManifestPath
    trainingRunJsonSha256 = $trainingManifestHash
    datasetJsonSha256 = $datasetHash
    conversionJsonSha256 = $conversionHash
    validationJsonlSha256 = $validationJsonlHash
    hypothesisSha256 = $hypothesisHash
    evaluationScriptSha256 = Get-Sha256Hex -FilePath $evaluationScriptPath
    evaluationJsonSha256 = $evaluationReportHash
    evaluationDetailsSha256 = $evaluationDetailsHash
    trainedModelSha256 = $trainedModelHash
    trainedConfigurationSha256 = $trainedConfigurationHash
    sampleCount = $sampleCount
    microCer = $microCer
    sentenceAccuracy = $sentenceAccuracy
    thresholdApplied = $false
    candidateReady = $false
    candidateRegistrationAllowed = $false
    modelSigningAllowed = $false
    runtimeReplacementAllowed = $false
    decisionReason = "FORMAL_THRESHOLDS_NOT_CONFIGURED"
}

if (-not [IO.Directory]::Exists($preparationsDirectory)) {
    [IO.Directory]::CreateDirectory($preparationsDirectory) | Out-Null
}
Assert-NotReparsePoint -PathText $preparationsDirectory `
    -Description "候选准备报告根目录"
$stagingDirectory = Join-Path $preparationsDirectory `
    (".candidate-preparation-staging-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
$published = $false
try {
    Write-Utf8WithoutBom `
        -FilePath (Join-Path $stagingDirectory "candidate-preparation.json") `
        -Content ($candidateReport | ConvertTo-Json)
    [IO.Directory]::Move($stagingDirectory, $finalPreparationDirectory)
    $published = $true
    Write-Host "候选准备报告已生成；正式阈值和候选登记仍保持关闭"
}
finally {
    if (-not $published -and [IO.Directory]::Exists($stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
