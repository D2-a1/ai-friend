<#
固定离线性能专项：100条/组、冷实例和预热实例、并发1/4，三类本地服务。
SQL/授权/向量模型为内存替身，不是云端、真实模型或端到端延迟验收。
仅显式test；不运行默认全量、verify/package、迁移或部署。
#>
[CmdletBinding()]
param([string]$TestJvmArguments = '-Xms64m -Xmx512m')
$ErrorActionPreference = 'Stop'
$serverDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$startedUtc = [DateTime]::UtcNow
Push-Location -LiteralPath $serverDirectory
try {
    & (Join-Path $serverDirectory 'mvnw.cmd') -o -q '-Dtest=KnowledgePerformanceMeasurementsTest,KnowledgeLocalPerformanceIT' "-DargLine=$TestJvmArguments" test
    $testExit = $LASTEXITCODE
    $reportDirectory = Join-Path $serverDirectory 'target/knowledge-performance'
    if (Test-Path -LiteralPath $reportDirectory) {
        $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'local-*.json' -File |
            Where-Object { $_.LastWriteTimeUtc -ge $startedUtc })
        if ($reports.Count -eq 1) { Write-Output "LOCAL_PERFORMANCE_REPORT=$($reports[0].FullName)" }
    } else { $reports = @() }
    if ($testExit -ne 0) { throw 'Local performance or measurement test failed. Retain the fresh report; no PASS is emitted.' }
    if ($reports.Count -ne 1) { throw 'Missing or ambiguous fresh local performance report.' }
    foreach ($suite in @(@{Name='KnowledgePerformanceMeasurementsTest';Count=2}, @{Name='KnowledgeLocalPerformanceIT';Count=1})) {
        $path = Join-Path $serverDirectory ('target/surefire-reports/TEST-com.aifriend.retrieval.infrastructure.' + $suite.Name + '.xml')
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -lt $startedUtc) { throw 'Stale performance test XML.' }
        [xml]$xml = Get-Content -LiteralPath $path -Raw
        if ([int]$xml.testsuite.tests -ne $suite.Count -or [int]$xml.testsuite.failures -ne 0 -or
            [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0) { throw 'Incomplete or failed performance test XML.' }
    }
    $report = Get-Content -LiteralPath $reports[0].FullName -Raw | ConvertFrom-Json
    if ($report.schema -ne 'knowledge-local-performance-v1' -or $report.groups.Count -ne 12 -or
        $report.chunksPerSnapshot -ne 2000 -or $report.retainedSnapshots -ne 2 -or
        $report.realDbConnections -ne 0 -or $report.realExternalCalls -ne 0) { throw 'Unexpected performance scope or group count.' }
    foreach ($subject in @('RETRIEVAL', 'EXTRACTIVE_TEXT', 'GRAPH_QUERY')) {
        foreach ($temperature in @('COLD_INSTANCE', 'WARMED_SERVICE')) {
            foreach ($concurrency in @(1, 4)) {
                $group = @($report.groups | Where-Object { $_.subject -eq $subject -and $_.temperature -eq $temperature -and $_.concurrency -eq $concurrency })
                if ($group.Count -ne 1 -or $group[0].stats.samples -ne 100 -or $group[0].stats.failures -ne 0 -or
                    $group[0].stats.overDeadline -ne 0 -or $group[0].stats.p95Ms -gt $group[0].targetP95Ms) { throw 'A separate performance group failed its gate.' }
                Write-Output ('{0} {1} concurrency={2} samples=100 P95_ms={3}' -f $subject,$temperature,$concurrency,$group[0].stats.p95Ms)
            }
        }
    }
    Write-Output 'LOCAL COMPONENT PERFORMANCE PASS: 12 groups / 1200 measured requests. 120 unmeasured warmups are separate.'
    Write-Output 'No cold-JVM, real database, network latency, generated-answer quality or cloud performance claim.'
} finally { Pop-Location }
