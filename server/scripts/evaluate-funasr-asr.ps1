[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDirectory,
    [Parameter(Mandatory = $true)]
    [string]$HypothesisFile,
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9._-]{0,63}$")]
    [string]$EvaluationName,
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

function Resolve-AbsoluteFile {
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
    if (-not [IO.File]::Exists($resolved)) {
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

function Read-ReferenceList {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][int]$ExpectedCount
    )

    [string[]]$lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedCount) {
        throw "验证集复核文字数量与 dataset.json 不一致"
    }
    $ids = New-Object "System.Collections.Generic.List[string]"
    $textById = @{}
    foreach ($line in $lines) {
        $separator = $line.IndexOf([char]9)
        if ($separator -le 0 -or $separator -ge $line.Length - 1) {
            throw "验证集复核文字必须使用制表符分隔编号和文字"
        }
        $utteranceId = $line.Substring(0, $separator)
        $transcript = $line.Substring($separator + 1)
        if ($utteranceId -notmatch "^utt_[0-9]{6}$" -or
            [string]::IsNullOrWhiteSpace($transcript) -or
            $textById.ContainsKey($utteranceId)) {
            throw "验证集复核文字包含非法、空白或重复语句"
        }
        $ids.Add($utteranceId)
        $textById[$utteranceId] = $transcript
    }
    return [pscustomobject]@{
        Ids = [string[]]$ids.ToArray()
        TextById = $textById
    }
}

function Read-HypothesisList {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ExpectedIds
    )

    [string[]]$lines = @(Get-Content -LiteralPath $FilePath -Encoding UTF8)
    if ($lines.Count -ne $ExpectedIds.Count) {
        throw "识别结果数量与验证集不一致"
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
            throw "识别结果必须以合法语句编号开头"
        }
        if ($ExpectedIds -notcontains $utteranceId -or
            $textById.ContainsKey($utteranceId)) {
            throw "识别结果包含未知或重复语句编号"
        }
        $textById[$utteranceId] = $transcript
    }
    foreach ($expectedId in $ExpectedIds) {
        if (-not $textById.ContainsKey($expectedId)) {
            throw "识别结果缺少验证集语句"
        }
    }
    return $textById
}

function ConvertTo-CerText {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $withoutMetaTags = [Text.RegularExpressions.Regex]::Replace(
        $Text,
        "<\|.*?\|>",
        ""
    )
    return [Text.RegularExpressions.Regex]::Replace(
        $withoutMetaTags.ToUpperInvariant(),
        "[^\w\u4E00-\u9FFF]",
        ""
    )
}

function Get-EditDistance {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Reference,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Hypothesis
    )

    if ($Reference.Length -gt 4096 -or $Hypothesis.Length -gt 4096) {
        throw "单条归一化文字超过 4096 个字符，拒绝评测"
    }
    [int[]]$previous = 0..$Hypothesis.Length
    [int[]]$current = @(0) * ($Hypothesis.Length + 1)
    for ($referenceIndex = 1; $referenceIndex -le $Reference.Length; $referenceIndex++) {
        $current[0] = $referenceIndex
        for ($hypothesisIndex = 1;
                $hypothesisIndex -le $Hypothesis.Length;
                $hypothesisIndex++) {
            $substitutionCost = 1
            if ($Reference[$referenceIndex - 1] -eq
                $Hypothesis[$hypothesisIndex - 1]) {
                $substitutionCost = 0
            }
            $deletion = $previous[$hypothesisIndex] + 1
            $insertion = $current[$hypothesisIndex - 1] + 1
            $substitution = $previous[$hypothesisIndex - 1] + $substitutionCost
            $current[$hypothesisIndex] = [Math]::Min(
                $deletion,
                [Math]::Min($insertion, $substitution)
            )
        }
        $swap = $previous
        $previous = $current
        $current = $swap
    }
    return $previous[$Hypothesis.Length]
}

function Write-Utf8WithoutBom {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )

    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($FilePath, $Content, $encoding)
}

$datasetRoot = Resolve-AbsoluteDirectory -PathText $DatasetDirectory
$hypothesisPath = Resolve-AbsoluteFile -PathText $HypothesisFile `
    -Description "识别结果文件"
$funAsrDirectory = Join-Path $datasetRoot "funasr"
$manifestPath = Join-Path $datasetRoot "dataset.json"
$referencePath = Join-Path $funAsrDirectory "val_text.txt"
$jsonlDirectory = Join-Path $funAsrDirectory "jsonl"
$conversionPath = Join-Path $jsonlDirectory "conversion.json"
$validationJsonlPath = Join-Path $jsonlDirectory "val.jsonl"
foreach ($requiredPath in @(
        $manifestPath,
        $referencePath,
        $conversionPath,
        $validationJsonlPath)) {
    if (-not [IO.File]::Exists($requiredPath)) {
        throw "离线评测缺少已校验的数据集或 JSONL 文件"
    }
}
Assert-NotReparsePoint -PathText $datasetRoot -Description "训练数据目录"
Assert-NotReparsePoint -PathText $funAsrDirectory -Description "FunASR 目录"
Assert-NotReparsePoint -PathText $jsonlDirectory -Description "JSONL 目录"
Assert-NotReparsePoint -PathText $manifestPath -Description "dataset.json"
Assert-NotReparsePoint -PathText $referencePath -Description "验证集复核文字"
Assert-NotReparsePoint -PathText $conversionPath -Description "转换清单"
Assert-NotReparsePoint -PathText $validationJsonlPath -Description "验证 JSONL"
Assert-NotReparsePoint -PathText ([IO.Path]::GetDirectoryName($hypothesisPath)) `
    -Description "识别结果目录"
Assert-NotReparsePoint -PathText $hypothesisPath -Description "识别结果文件"

$manifest = Read-JsonObject -FilePath $manifestPath -Description "dataset.json"
if ([string](Get-RequiredProperty -Object $manifest -Name "formatVersion" `
        -SourceName "dataset.json") -ne "voice-training-input-v3" -or
    [string](Get-RequiredProperty -Object $manifest -Name "trainingFormatVersion" `
        -SourceName "dataset.json") -ne "funasr-scp-v2") {
    throw "训练数据格式不是当前支持的 voice-training-input-v3/funasr-scp-v2"
}
$validationCount = [int](Get-RequiredProperty -Object $manifest `
    -Name "validationSampleCount" -SourceName "dataset.json")
if ($validationCount -lt 1 -or
    [string](Get-RequiredProperty -Object $manifest `
        -Name "funAsrValidationText" -SourceName "dataset.json") -ne
        "funasr/val_text.txt") {
    throw "dataset.json 的验证集数量或文字清单路径无效"
}

$conversion = Read-JsonObject -FilePath $conversionPath `
    -Description "conversion.json"
if ([string](Get-RequiredProperty -Object $conversion -Name "formatVersion" `
        -SourceName "conversion.json") -ne "funasr-jsonl-v1" -or
    [int](Get-RequiredProperty -Object $conversion -Name "validationSampleCount" `
        -SourceName "conversion.json") -ne $validationCount -or
    [string](Get-RequiredProperty -Object $conversion -Name "validationJsonl" `
        -SourceName "conversion.json") -ne "val.jsonl") {
    throw "FunASR JSONL 转换清单与当前评测格式不一致"
}
$datasetHash = Get-Sha256Hex -FilePath $manifestPath
$conversionHash = Get-Sha256Hex -FilePath $conversionPath
$validationJsonlHash = Get-Sha256Hex -FilePath $validationJsonlPath
$referenceHash = Get-Sha256Hex -FilePath $referencePath
$hypothesisHash = Get-Sha256Hex -FilePath $hypothesisPath
if ([string](Get-RequiredProperty -Object $conversion -Name "datasetJsonSha256" `
        -SourceName "conversion.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $conversion -Name "validationJsonlSha256" `
        -SourceName "conversion.json") -ne $validationJsonlHash) {
    throw "数据集或验证 JSONL 摘要与转换清单不一致"
}

$references = Read-ReferenceList -FilePath $referencePath `
    -ExpectedCount $validationCount
$hypotheses = Read-HypothesisList -FilePath $hypothesisPath `
    -ExpectedIds $references.Ids

$totalReferenceCharacters = 0L
$totalHypothesisCharacters = 0L
$totalEditDistance = 0L
$exactSentenceCount = 0
$detailLines = New-Object "System.Collections.Generic.List[string]"
foreach ($utteranceId in $references.Ids) {
    $normalizedReference = ConvertTo-CerText `
        -Text ([string]$references.TextById[$utteranceId])
    $normalizedHypothesis = ConvertTo-CerText `
        -Text ([string]$hypotheses[$utteranceId])
    if ($normalizedReference.Length -eq 0) {
        throw "验证集复核文字归一化后为空"
    }
    $editDistance = Get-EditDistance -Reference $normalizedReference `
        -Hypothesis $normalizedHypothesis
    $isExact = $editDistance -eq 0
    $totalReferenceCharacters += $normalizedReference.Length
    $totalHypothesisCharacters += $normalizedHypothesis.Length
    $totalEditDistance += $editDistance
    if ($isExact) {
        $exactSentenceCount++
    }
    $detail = [ordered]@{
        utteranceId = $utteranceId
        referenceCharacterCount = $normalizedReference.Length
        hypothesisCharacterCount = $normalizedHypothesis.Length
        editDistance = $editDistance
        exact = $isExact
    }
    $detailLines.Add(($detail | ConvertTo-Json -Compress))
}

$microCer = [Math]::Round(
    [double]$totalEditDistance / [double]$totalReferenceCharacters,
    8
)
$sentenceAccuracy = [Math]::Round(
    [double]$exactSentenceCount / [double]$validationCount,
    8
)
if ((Get-Sha256Hex -FilePath $manifestPath) -ne $datasetHash -or
    (Get-Sha256Hex -FilePath $conversionPath) -ne $conversionHash -or
    (Get-Sha256Hex -FilePath $validationJsonlPath) -ne $validationJsonlHash -or
    (Get-Sha256Hex -FilePath $referencePath) -ne $referenceHash -or
    (Get-Sha256Hex -FilePath $hypothesisPath) -ne $hypothesisHash) {
    throw "输入文件在评测期间发生变化，拒绝生成报告"
}
$report = [ordered]@{
    formatVersion = "funasr-asr-evaluation-v1"
    evaluationName = $EvaluationName
    metricProtocol = "funasr-normalized-micro-cer-v1"
    normalization = "strip-meta-tags;uppercase;remove-non-word-and-non-cjk"
    datasetJsonSha256 = $datasetHash
    conversionJsonSha256 = $conversionHash
    validationJsonlSha256 = $validationJsonlHash
    referenceTextSha256 = $referenceHash
    hypothesisSha256 = $hypothesisHash
    sampleCount = $validationCount
    failedSampleCount = 0
    referenceCharacterCount = $totalReferenceCharacters
    hypothesisCharacterCount = $totalHypothesisCharacters
    editDistance = $totalEditDistance
    microCer = $microCer
    exactSentenceCount = $exactSentenceCount
    sentenceAccuracy = $sentenceAccuracy
    thresholdApplied = $false
}

Write-Host "离线 ASR 评测完成：样本=$validationCount，CER=$microCer，整句准确率=$sentenceAccuracy"
if ($ValidateOnly) {
    Write-Host "当前为只校验模式，未生成评测目录"
    return
}

$evaluationsDirectory = Join-Path $funAsrDirectory "evaluations"
if (-not [IO.Directory]::Exists($evaluationsDirectory)) {
    [IO.Directory]::CreateDirectory($evaluationsDirectory) | Out-Null
}
Assert-NotReparsePoint -PathText $evaluationsDirectory -Description "评测目录"
$finalDirectory = Join-Path $evaluationsDirectory $EvaluationName
if ([IO.Directory]::Exists($finalDirectory) -or [IO.File]::Exists($finalDirectory)) {
    throw "同名评测结果已存在，禁止覆盖"
}
$stagingDirectory = Join-Path $evaluationsDirectory `
    (".evaluation-staging-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
$published = $false
try {
    Write-Utf8WithoutBom -FilePath (Join-Path $stagingDirectory "evaluation.json") `
        -Content ($report | ConvertTo-Json)
    Write-Utf8WithoutBom -FilePath (Join-Path $stagingDirectory "details.jsonl") `
        -Content ([string]::Join([Environment]::NewLine, $detailLines))
    [IO.Directory]::Move($stagingDirectory, $finalDirectory)
    $published = $true
    Write-Host "离线 ASR 评测报告已生成并校验"
}
finally {
    if (-not $published -and [IO.Directory]::Exists($stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
