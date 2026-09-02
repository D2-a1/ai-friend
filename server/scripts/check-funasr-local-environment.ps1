[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$FunAsrRepositoryDirectory,
    [Parameter(Mandatory = $true)]
    [string]$InitialModelDirectory,
    [string]$PythonExecutable = "python",
    [string]$TorchRunExecutable = "torchrun",
    [ValidatePattern("^(cpu|cuda(?::[0-9]+)?)$")]
    [string]$Device = "cpu",
    [ValidateRange(5, 120)]
    [int]$ProbeTimeoutSeconds = 30
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

function Assert-RequiredFile {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not [IO.File]::Exists($FilePath) -or
        (Get-Item -LiteralPath $FilePath).Length -le 0) {
        throw "[FUNASR_ENV_REQUIRED_FILE_INVALID] $Description 缺失或为空"
    }
    Assert-NotReparsePoint -PathText $FilePath -Description $Description
}

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ([IO.File]::Exists($Executable)) {
        return [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Executable).Path)
    }
    try {
        $command = Get-Command -Name $Executable -CommandType Application `
            -ErrorAction Stop
        return $command.Source
    }
    catch {
        throw "$Description 不可用"
    }
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

function Invoke-ReadOnlyProbe {
    param(
        [Parameter(Mandatory = $true)][string]$ExecutablePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $argumentLine = [string]::Join(" ", @(
        $Arguments | ForEach-Object { ConvertTo-ProcessArgument -Value $_ }
    ))
    $process = Start-Process -FilePath $ExecutablePath -ArgumentList $argumentLine `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru
    try {
        [void]$process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "[FUNASR_ENV_PROBE_TIMEOUT] $Description 超时"
        }
        $process.WaitForExit()
        $process.Refresh()
        if ($process.ExitCode -ne 0) {
            throw "[FUNASR_ENV_PROBE_FAILED] $Description 失败，exitCode=$($process.ExitCode)"
        }
    }
    finally {
        $process.Dispose()
    }
}

$funAsrRepositoryRoot = Resolve-AbsoluteDirectory `
    -PathText $FunAsrRepositoryDirectory -Description "FunASR 源码目录"
$initialModelRoot = Resolve-AbsoluteDirectory `
    -PathText $InitialModelDirectory -Description "初始模型目录"
Assert-NotReparsePoint -PathText $funAsrRepositoryRoot `
    -Description "FunASR 源码目录"
Assert-NotReparsePoint -PathText $initialModelRoot -Description "初始模型目录"

$trainEntryPath = Join-Path $funAsrRepositoryRoot "funasr/bin/train_ds.py"
$inferenceEntryPath = Join-Path $funAsrRepositoryRoot "funasr/bin/inference.py"
$initialModelPath = Join-Path $initialModelRoot "model.pt"
$initialConfigurationPath = Join-Path $initialModelRoot "configuration.json"
if (-not [IO.File]::Exists($initialConfigurationPath)) {
    $initialConfigurationPath = Join-Path $initialModelRoot "config.yaml"
}
Assert-RequiredFile -FilePath $trainEntryPath -Description "FunASR 训练入口"
Assert-RequiredFile -FilePath $inferenceEntryPath -Description "FunASR 推理入口"
Assert-RequiredFile -FilePath $initialModelPath -Description "初始模型文件"
Assert-RequiredFile -FilePath $initialConfigurationPath `
    -Description "初始模型配置文件"

$pythonPath = Resolve-Executable -Executable $PythonExecutable `
    -Description "Python 可执行文件"
$torchRunPath = Resolve-Executable -Executable $TorchRunExecutable `
    -Description "torchrun 可执行文件"
$pythonProbe = "import funasr,torch,sys;device=sys.argv[1];" +
    "assert torch.distributed.is_available(),'torch.distributed unavailable';" +
    "cuda=device.startswith('cuda');" +
    "assert (not cuda) or torch.cuda.is_available(),'cuda unavailable';" +
    "index=int(device.split(':',1)[1]) if ':' in device else 0;" +
    "assert (not cuda) or index < torch.cuda.device_count(),'cuda index unavailable';" +
    "print('FUNASR_LOCAL_ENVIRONMENT_OK')"

Invoke-ReadOnlyProbe -ExecutablePath $pythonPath `
    -Arguments @("-B", "-c", $pythonProbe, $Device) `
    -WorkingDirectory $funAsrRepositoryRoot -TimeoutSeconds $ProbeTimeoutSeconds `
    -Description "Python、PyTorch 与 FunASR 导入预检"
Invoke-ReadOnlyProbe -ExecutablePath $torchRunPath -Arguments @("--help") `
    -WorkingDirectory $funAsrRepositoryRoot -TimeoutSeconds $ProbeTimeoutSeconds `
    -Description "torchrun 可用性预检"

Write-Host "[FUNASR_ENV_OK] FunASR 本机环境预检通过：Python、PyTorch、FunASR、torchrun、本地模型均可用"
Write-Host "本次只读取本机环境，未下载、安装、训练或创建结果"
