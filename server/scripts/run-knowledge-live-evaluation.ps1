<#
显式真实模型评测入口。未获独立外发和费用授权时禁止运行。
仅发送仓库中哈希冻结的公开合成语料，不连接业务数据库。
独立 AI_FRIEND_KNOWLEDGE_EVAL_* 配置只从进程环境读取，不加载生产配置。
运行完成不等于答案质量通过；报告必须人工复核、对账后另行判定。
#>
[CmdletBinding()]
param(
    [switch]$AllowExternalCalls,
    [switch]$PublicDataApproved,
    [Parameter(Mandatory=$true)][ValidateSet('A','B')][string]$ProfileLabel,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}$')][string]$RunId
)
$ErrorActionPreference = 'Stop'
if (-not $AllowExternalCalls -or -not $PublicDataApproved) {
    throw 'Explicit external-call/cost authorization and public-data approval are both required. No evaluation was started.'
}
$serverDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$startedUtc = [DateTime]::UtcNow
$overrides = @{
    AI_FRIEND_KNOWLEDGE_EVAL_ENABLED = 'true'
    AI_FRIEND_KNOWLEDGE_EVAL_PUBLIC_DATA_APPROVED = 'true'
    AI_FRIEND_KNOWLEDGE_EVAL_PROFILE_LABEL = $ProfileLabel
    AI_FRIEND_KNOWLEDGE_EVAL_RUN_ID = $RunId
}
$previous = @{}
Push-Location -LiteralPath $serverDirectory
try {
    foreach ($name in $overrides.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $overrides[$name], 'Process')
    }
    & (Join-Path $serverDirectory 'mvnw.cmd') -o -q '-Dtest=KnowledgeLiveEvaluationIT' '-DargLine=-Xms64m -Xmx512m' test
    $testExit = $LASTEXITCODE
    $folder = Join-Path $serverDirectory 'target/knowledge-live-evaluation'
    $reports = @()
    if (Test-Path -LiteralPath $folder) {
        $reports = @(Get-ChildItem -LiteralPath $folder -Filter "$RunId-*.json" -File |
            Where-Object { $_.LastWriteTimeUtc -ge $startedUtc })
    }
    if ($reports.Count -eq 1) { Write-Output "KNOWLEDGE_LIVE_REVIEW_REPORT=$($reports[0].FullName)" }
    if ($testExit -ne 0) { throw 'Evaluation incomplete. Retain any redacted report; no quality PASS is emitted.' }
    if ($reports.Count -ne 1) { throw 'Missing or ambiguous fresh evaluation report.' }
    $xmlPath = Join-Path $serverDirectory 'target/surefire-reports/TEST-com.aifriend.retrieval.infrastructure.KnowledgeLiveEvaluationIT.xml'
    if ((Get-Item -LiteralPath $xmlPath).LastWriteTimeUtc -lt $startedUtc) { throw 'Stale evaluation XML.' }
    [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
    if ([int]$xml.testsuite.tests -ne 1 -or [int]$xml.testsuite.failures -ne 0 -or
        [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) { throw 'Incomplete evaluation XML.' }
    $report = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($report.schema -ne 'knowledge-live-evaluation-v1' -or $report.runId -ne $RunId -or
        $report.profileLabel -ne $ProfileLabel -or $report.samples.Count -ne 28 -or
        $report.qualityDecision -ne 'PENDING_MANUAL_REVIEW_NOT_AUTOMATIC_PASS' -or
        $report.realDatabaseUsed -ne $false) { throw 'Unexpected evaluation report scope.' }
    Write-Output 'EVALUATION RECORDED; QUALITY REVIEW PENDING. No release approval.'
    Write-Output 'Review evidence support, answer correctness, abstention and generated/extractive counts separately; reconcile provider billing.'
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
