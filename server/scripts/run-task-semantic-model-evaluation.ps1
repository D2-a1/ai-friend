[CmdletBinding()]
param(
    [ValidateSet("Smoke", "ReleaseGate")]
    [string]$Mode = "Smoke",
    [string]$DatasetFile = "",
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[a-z0-9][a-z0-9._-]{0,63}$")]
    [string]$EvaluationName,
    [Parameter(Mandatory = $true)]
    [switch]$PrivacyApproved
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $PrivacyApproved) {
    throw "必须明确确认评测数据已获授权并完成脱敏"
}

$serverRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$defaultDataset = [IO.Path]::GetFullPath((Join-Path $serverRoot `
    "src\test\resources\task-semantic-model\evaluation-seed-v1.jsonl"))
if ([string]::IsNullOrWhiteSpace($DatasetFile)) {
    $DatasetFile = $defaultDataset
}
if (-not [IO.Path]::IsPathFullyQualified($DatasetFile)) {
    throw "评测数据集必须使用绝对路径"
}
$datasetPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $DatasetFile).Path)
if (-not [IO.File]::Exists($datasetPath)) {
    throw "评测数据集不存在"
}
if (([IO.File]::GetAttributes($datasetPath) -band `
        [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "评测数据集不能是目录连接或符号链接"
}
if ($Mode -eq "ReleaseGate" -and $datasetPath -eq $defaultDataset) {
    throw "内置脱敏冒烟集不能用于上线门禁，请提供经授权的完整评测集"
}

$requiredEnvironment = @(
    "AI_FRIEND_SEMANTIC_EVAL_PROVIDER",
    "AI_FRIEND_SEMANTIC_EVAL_ENDPOINT",
    "AI_FRIEND_SEMANTIC_EVAL_ALLOWED_HOSTS",
    "AI_FRIEND_SEMANTIC_EVAL_MODEL",
    "AI_FRIEND_SEMANTIC_EVAL_API_KEY",
    "AI_FRIEND_SEMANTIC_EVAL_TOKEN_LIMIT_FIELD",
    "AI_FRIEND_SEMANTIC_EVAL_MAX_OUTPUT_TOKENS",
    "AI_FRIEND_SEMANTIC_EVAL_TEMPERATURE",
    "AI_FRIEND_SEMANTIC_EVAL_THINKING_MODE",
    "AI_FRIEND_SEMANTIC_EVAL_CONNECT_TIMEOUT_SECONDS",
    "AI_FRIEND_SEMANTIC_EVAL_READ_TIMEOUT_SECONDS"
)
foreach ($name in $requiredEnvironment) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "缺少评测环境变量：$name"
    }
}
$endpointText = [Environment]::GetEnvironmentVariable(
    "AI_FRIEND_SEMANTIC_EVAL_ENDPOINT")
$endpoint = $null
if (-not [Uri]::TryCreate($endpointText, [UriKind]::Absolute, [ref]$endpoint) `
        -or $endpoint.Scheme -ne "https") {
    throw "AI_FRIEND_SEMANTIC_EVAL_ENDPOINT 必须是绝对 HTTPS 地址"
}
$allowedHosts = [Environment]::GetEnvironmentVariable(
    "AI_FRIEND_SEMANTIC_EVAL_ALLOWED_HOSTS").Split(",") |
    ForEach-Object { $_.Trim().ToLowerInvariant() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($allowedHosts -notcontains $endpoint.DnsSafeHost.ToLowerInvariant()) {
    throw "评测 endpoint 主机必须精确列入 AI_FRIEND_SEMANTIC_EVAL_ALLOWED_HOSTS"
}
$tokenLimitField = [Environment]::GetEnvironmentVariable(
    "AI_FRIEND_SEMANTIC_EVAL_TOKEN_LIMIT_FIELD")
if ($tokenLimitField -notin @("MAX_TOKENS", "MAX_COMPLETION_TOKENS")) {
    throw "AI_FRIEND_SEMANTIC_EVAL_TOKEN_LIMIT_FIELD 配置无效"
}
$thinkingMode = [Environment]::GetEnvironmentVariable(
    "AI_FRIEND_SEMANTIC_EVAL_THINKING_MODE")
if ($thinkingMode -notin @(
        "OMIT", "DISABLED", "ENABLED", "OBJECT_DISABLED", "OBJECT_ENABLED")) {
    throw "AI_FRIEND_SEMANTIC_EVAL_THINKING_MODE 配置无效"
}
foreach ($name in @(
        "AI_FRIEND_SEMANTIC_EVAL_MAX_OUTPUT_TOKENS",
        "AI_FRIEND_SEMANTIC_EVAL_CONNECT_TIMEOUT_SECONDS",
        "AI_FRIEND_SEMANTIC_EVAL_READ_TIMEOUT_SECONDS")) {
    $parsedInteger = 0
    if (-not [int]::TryParse(
            [Environment]::GetEnvironmentVariable($name), [ref]$parsedInteger) `
            -or $parsedInteger -le 0) {
        throw "$name 必须是正整数"
    }
}
$parsedTemperature = 0.0
if (-not [double]::TryParse(
        [Environment]::GetEnvironmentVariable(
            "AI_FRIEND_SEMANTIC_EVAL_TEMPERATURE"),
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsedTemperature) `
        -or $parsedTemperature -lt 0.0 -or $parsedTemperature -gt 1.0) {
    throw "AI_FRIEND_SEMANTIC_EVAL_TEMPERATURE 必须在 0 到 1 之间"
}

$reportDirectory = [IO.Path]::GetFullPath((Join-Path $serverRoot `
    "target\semantic-model-evaluations"))
if ([IO.Directory]::Exists($reportDirectory) -and
        (([IO.File]::GetAttributes($reportDirectory) -band `
            [IO.FileAttributes]::ReparsePoint) -ne 0)) {
    throw "评测报告目录不能是目录连接或符号链接"
}
[IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
if (([IO.File]::GetAttributes($reportDirectory) -band `
        [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "评测报告目录不能是目录连接或符号链接"
}
$reportPath = [IO.Path]::GetFullPath((Join-Path $reportDirectory `
    "$EvaluationName.json"))
if ([IO.File]::Exists($reportPath)) {
    throw "同名评测报告已存在，禁止覆盖"
}

$datasetHashBefore = (Get-FileHash -LiteralPath $datasetPath `
    -Algorithm SHA256).Hash
$javaMode = if ($Mode -eq "ReleaseGate") { "RELEASE_GATE" } else { "SMOKE" }
$arguments = @(
    "-Dtest=com.aifriend.task.infrastructure.TaskSemanticModelLiveEvaluationIT",
    "-DaiFriend.semanticEvaluation.dataset=$datasetPath",
    "-DaiFriend.semanticEvaluation.report=$reportPath",
    "-DaiFriend.semanticEvaluation.mode=$javaMode",
    "test"
)

$evaluationExitCode = -1
Push-Location $serverRoot
try {
    & ".\mvnw.cmd" @arguments
    $evaluationExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

$datasetHashAfter = (Get-FileHash -LiteralPath $datasetPath `
    -Algorithm SHA256).Hash
if ($datasetHashAfter -ne $datasetHashBefore) {
    throw "评测期间数据集发生变化，报告无效"
}
if ([IO.File]::Exists($reportPath)) {
    Write-Output "评测报告：$reportPath"
    Write-Output "数据集 SHA-256：$($datasetHashAfter.ToLowerInvariant())"
}
if ($evaluationExitCode -ne 0) {
    throw "语义模型评测失败，未通过当前模式门禁"
}
if (-not [IO.File]::Exists($reportPath)) {
    throw "评测过程未生成报告"
}

Write-Output "评测完成"
