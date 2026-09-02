[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetDirectory,
    [Parameter(Mandatory = $true)]
    [string]$FunAsrRepositoryDirectory,
    [Parameter(Mandatory = $true)]
    [string]$InitialModelDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9-]{0,47}$")]
    [string]$RunName,
    [string]$TorchRunExecutable = "torchrun",
    [ValidateRange(60, 604800)]
    [int]$TrainingTimeoutSeconds = 86400,
    [ValidateRange(1, 1000)]
    [int]$MaxEpoch = 50,
    [ValidateRange(1, 1000000)]
    [int]$BatchSize = 6000,
    [ValidateRange(0.000001, 1.0)]
    [double]$LearningRate = 0.0002,
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

function Invoke-FunAsrTraining {
    param(
        [Parameter(Mandatory = $true)][string]$TorchRunPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )

    $argumentLine = [string]::Join(" ", @(
        $Arguments | ForEach-Object { ConvertTo-ProcessArgument -Value $_ }
    ))
    $process = Start-Process -FilePath $TorchRunPath -ArgumentList $argumentLine `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru
    try {
        [void]$process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "FunASR 训练超时，timeoutSeconds=$TimeoutSeconds"
        }
        $process.WaitForExit()
        $process.Refresh()
        if ($process.ExitCode -ne 0) {
            throw "FunASR 训练失败，exitCode=$($process.ExitCode)"
        }
    }
    finally {
        $process.Dispose()
    }
}

function Write-Utf8WithoutBom {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($FilePath, $Content, $encoding)
}

$datasetRoot = Resolve-AbsoluteDirectory -PathText $DatasetDirectory `
    -Description "训练数据目录"
$funAsrRepositoryRoot = Resolve-AbsoluteDirectory `
    -PathText $FunAsrRepositoryDirectory -Description "FunASR 源码目录"
$initialModelRoot = Resolve-AbsoluteDirectory `
    -PathText $InitialModelDirectory -Description "初始模型目录"
Assert-NotReparsePoint -PathText $datasetRoot -Description "训练数据目录"
Assert-NotReparsePoint -PathText $funAsrRepositoryRoot -Description "FunASR 源码目录"
Assert-NotReparsePoint -PathText $initialModelRoot -Description "初始模型目录"

$datasetManifestPath = Join-Path $datasetRoot "dataset.json"
$jsonlDirectory = Join-Path $datasetRoot "funasr/jsonl"
$conversionManifestPath = Join-Path $jsonlDirectory "conversion.json"
$trainJsonlPath = Join-Path $jsonlDirectory "train.jsonl"
$validationJsonlPath = Join-Path $jsonlDirectory "val.jsonl"
$trainEntryPath = Join-Path $funAsrRepositoryRoot "funasr/bin/train_ds.py"
$initialModelPath = Join-Path $initialModelRoot "model.pt"
$initialConfigurationPath = Join-Path $initialModelRoot "configuration.json"
if (-not [IO.File]::Exists($initialConfigurationPath)) {
    $initialConfigurationPath = Join-Path $initialModelRoot "config.yaml"
}
foreach ($requiredFile in @(
        $datasetManifestPath,
        $conversionManifestPath,
        $trainJsonlPath,
        $validationJsonlPath,
        $trainEntryPath,
        $initialModelPath,
        $initialConfigurationPath
    )) {
    if (-not [IO.File]::Exists($requiredFile)) {
        throw "训练所需文件不完整"
    }
    Assert-NotReparsePoint -PathText $requiredFile -Description "训练输入文件"
}
Assert-NotReparsePoint -PathText $jsonlDirectory -Description "JSONL 目录"

$dataset = Read-JsonObject -FilePath $datasetManifestPath -Description "dataset.json"
$conversion = Read-JsonObject -FilePath $conversionManifestPath `
    -Description "conversion.json"
if ([string](Get-RequiredProperty -Object $dataset -Name "formatVersion" `
        -SourceName "dataset.json") -ne "voice-training-input-v3" -or
    [string](Get-RequiredProperty -Object $dataset -Name "trainingFormatVersion" `
        -SourceName "dataset.json") -ne "funasr-scp-v2") {
    throw "训练数据格式不是当前支持的 voice-training-input-v3/funasr-scp-v2"
}
if ([string](Get-RequiredProperty -Object $conversion -Name "formatVersion" `
        -SourceName "conversion.json") -ne "funasr-jsonl-v1") {
    throw "JSONL 转换格式不是当前支持的 funasr-jsonl-v1"
}

$datasetHash = Get-Sha256Hex -FilePath $datasetManifestPath
$conversionHash = Get-Sha256Hex -FilePath $conversionManifestPath
$trainJsonlHash = Get-Sha256Hex -FilePath $trainJsonlPath
$validationJsonlHash = Get-Sha256Hex -FilePath $validationJsonlPath
if ([string](Get-RequiredProperty -Object $conversion -Name "datasetJsonSha256" `
        -SourceName "conversion.json") -ne $datasetHash -or
    [string](Get-RequiredProperty -Object $conversion -Name "trainJsonlSha256" `
        -SourceName "conversion.json") -ne $trainJsonlHash -or
    [string](Get-RequiredProperty -Object $conversion -Name "validationJsonlSha256" `
        -SourceName "conversion.json") -ne $validationJsonlHash) {
    throw "训练数据或 JSONL 摘要与转换清单不一致"
}
$trainCount = [int](Get-RequiredProperty -Object $conversion -Name "trainSampleCount" `
    -SourceName "conversion.json")
$validationCount = [int](Get-RequiredProperty -Object $conversion `
    -Name "validationSampleCount" -SourceName "conversion.json")
if ($trainCount -lt 1 -or $validationCount -lt 1 -or
    @(Get-Content -LiteralPath $trainJsonlPath -Encoding UTF8).Count -ne $trainCount -or
    @(Get-Content -LiteralPath $validationJsonlPath -Encoding UTF8).Count -ne $validationCount) {
    throw "训练或验证 JSONL 数量无效"
}

$trainEntryHash = Get-Sha256Hex -FilePath $trainEntryPath
$initialModelHash = Get-Sha256Hex -FilePath $initialModelPath
$initialConfigurationHash = Get-Sha256Hex -FilePath $initialConfigurationPath
Write-Host "FunASR 训练输入预检通过：训练=$trainCount，验证=$validationCount"
if ($ValidateOnly) {
    Write-Host "当前为只校验模式，未启动训练，也未创建训练结果"
    return
}

$torchRunPath = Resolve-Executable -Executable $TorchRunExecutable
$runsDirectory = Join-Path $datasetRoot "funasr/training-runs"
if (-not [IO.Directory]::Exists($runsDirectory)) {
    [IO.Directory]::CreateDirectory($runsDirectory) | Out-Null
}
Assert-NotReparsePoint -PathText $runsDirectory -Description "训练结果目录"
$finalDirectory = Join-Path $runsDirectory $RunName
if ([IO.Directory]::Exists($finalDirectory) -or [IO.File]::Exists($finalDirectory)) {
    throw "同名训练结果已存在，禁止覆盖"
}
$stagingDirectory = Join-Path $runsDirectory `
    (".training-staging-" + [Guid]::NewGuid().ToString("N"))
[IO.Directory]::CreateDirectory($stagingDirectory) | Out-Null
$published = $false
try {
    $arguments = @(
        "--standalone",
        "--nnodes=1",
        "--nproc_per_node=1",
        $trainEntryPath,
        "++model=$initialModelRoot",
        "++train_data_set_list=$trainJsonlPath",
        "++valid_data_set_list=$validationJsonlPath",
        "++dataset=AudioDataset",
        "++dataset_conf.index_ds=IndexDSJsonl",
        "++dataset_conf.data_split_num=1",
        "++dataset_conf.batch_sampler=BatchSampler",
        "++dataset_conf.batch_size=$BatchSize",
        "++dataset_conf.sort_size=1024",
        "++dataset_conf.batch_type=token",
        "++dataset_conf.num_workers=0",
        "++train_conf.max_epoch=$MaxEpoch",
        "++train_conf.log_interval=1",
        "++train_conf.resume=false",
        "++train_conf.validate_interval=1",
        "++train_conf.save_checkpoint_interval=1",
        "++train_conf.keep_nbest_models=3",
        "++train_conf.avg_nbest_model=1",
        "++train_conf.use_deepspeed=false",
        "++optim_conf.lr=$($LearningRate.ToString([Globalization.CultureInfo]::InvariantCulture))",
        "++output_dir=$stagingDirectory"
    )
    Write-Host "正在启动 FunASR 单机单进程训练"
    Invoke-FunAsrTraining -TorchRunPath $torchRunPath -Arguments $arguments `
        -WorkingDirectory $datasetRoot -TimeoutSeconds $TrainingTimeoutSeconds

    $trainedModelPath = Join-Path $stagingDirectory "model.pt"
    $trainedConfigurationPath = Join-Path $stagingDirectory "configuration.json"
    if (-not [IO.File]::Exists($trainedConfigurationPath)) {
        $trainedConfigurationPath = Join-Path $stagingDirectory "config.yaml"
    }
    if (-not [IO.File]::Exists($trainedModelPath) -or
        -not [IO.File]::Exists($trainedConfigurationPath) -or
        (Get-Item -LiteralPath $trainedModelPath).Length -le 0 -or
        (Get-Item -LiteralPath $trainedConfigurationPath).Length -le 0) {
        throw "FunASR 训练未生成可核验的 model.pt 和配置文件"
    }
    Assert-NotReparsePoint -PathText $trainedModelPath `
        -Description "训练模型文件"
    Assert-NotReparsePoint -PathText $trainedConfigurationPath `
        -Description "训练配置文件"
    if ((Get-Sha256Hex -FilePath $datasetManifestPath) -ne $datasetHash -or
        (Get-Sha256Hex -FilePath $conversionManifestPath) -ne $conversionHash -or
        (Get-Sha256Hex -FilePath $trainJsonlPath) -ne $trainJsonlHash -or
        (Get-Sha256Hex -FilePath $validationJsonlPath) -ne $validationJsonlHash -or
        (Get-Sha256Hex -FilePath $trainEntryPath) -ne $trainEntryHash -or
        (Get-Sha256Hex -FilePath $initialModelPath) -ne $initialModelHash -or
        (Get-Sha256Hex -FilePath $initialConfigurationPath) -ne
            $initialConfigurationHash) {
        throw "训练期间输入发生变化，拒绝发布结果"
    }

    $runManifest = [ordered]@{
        formatVersion = "funasr-training-run-v1"
        runName = $RunName
        datasetJsonSha256 = $datasetHash
        conversionJsonSha256 = $conversionHash
        trainJsonlSha256 = $trainJsonlHash
        validationJsonlSha256 = $validationJsonlHash
        trainSampleCount = $trainCount
        validationSampleCount = $validationCount
        funAsrTrainEntrySha256 = $trainEntryHash
        initialModelSha256 = $initialModelHash
        initialConfigurationSha256 = $initialConfigurationHash
        maxEpoch = $MaxEpoch
        batchSize = $BatchSize
        learningRate = $LearningRate
        processCount = 1
        deepspeedEnabled = $false
        trainedModelSha256 = Get-Sha256Hex -FilePath $trainedModelPath
        trainedConfigurationSha256 = Get-Sha256Hex `
            -FilePath $trainedConfigurationPath
        candidateRegistered = $false
        runtimeReplaced = $false
    }
    Write-Utf8WithoutBom -FilePath (Join-Path $stagingDirectory "training-run.json") `
        -Content ($runManifest | ConvertTo-Json)
    [IO.Directory]::Move($stagingDirectory, $finalDirectory)
    $published = $true
    Write-Host "FunASR 训练完成并发布本机结果：$RunName"
}
finally {
    if (-not $published -and [IO.Directory]::Exists($stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
