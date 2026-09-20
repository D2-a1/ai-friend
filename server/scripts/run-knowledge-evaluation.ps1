<#
冻结60场景的离线功能回放。仅SQL/网络替身，不是Live模型、真实数据库或性能评测。
60场景+1本地召回门禁+3数据门禁+3架构+2旧任务+1默认关闭装配=70个测试实例。
不接受任意测试选择器，不运行全量、IT、verify/package或部署。
#>
[CmdletBinding()]
param([string]$TestJvmArguments = '-Xms64m -Xmx512m')
$ErrorActionPreference = 'Stop'
$serverDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$suites = @(
    @{ Class='com.aifriend.retrieval.infrastructure.KnowledgeFrozenLocalReplayTest'; Methods=[ordered]@{
        frozenQuestionMustReturnSupportedExtractOrAbstain=28; frozenAnswerableBm25RecallAtFourMustMeetNinetyPercent=1 } },
    @{ Class='com.aifriend.retrieval.infrastructure.KnowledgeAcceptanceDatasetTest'; Methods=[ordered]@{
        frozenInputsMustRetainPreExecutionDigests=1; sixtyCasesMustKeepIndependentCategoriesAndExplicitOracles=1;
        answerableOraclesMustReferenceSyntheticCorpusFacts=1 } },
    @{ Class='com.aifriend.ArchitectureTest'; Methods=[ordered]@{
        privateGraphMustNotDependOnModelGateways=1; knowledgeMustNotDependOnContactExecutionOrTaskAuthorization=1;
        domainPackagesMustNotDependOnSpringOrJpa=1 } },
    @{ Class='com.aifriend.assistant.infrastructure.JdbcAssistantTurnRepositoryTest'; Methods=[ordered]@{
        frozenGraphScenarioUsesActualHttpTraversalAndRevalidation=8;
        frozenSecurityScenarioCrossesHttpBoundaryWithoutUnauthorizedEffects=10;
        frozenDeletedEvidenceNeverReappearsUnderOriginalKey=1;
        frozenFaultHasBoundedEffectsAndNoLateSuccess=6 } },
    @{ Class='com.aifriend.retrieval.infrastructure.JdbcKnowledgeImportRegistrationAdapterTest'; Methods=[ordered]@{
        frozenRegistrationPreservesOriginalIdentity=2 } },
    @{ Class='com.aifriend.retrieval.infrastructure.JdbcKnowledgeGenerationAdapterTest'; Methods=[ordered]@{
        frozenPublicationChangesAllOrNothing=2 } },
    @{ Class='com.aifriend.retrieval.infrastructure.KnowledgeDeletionRebuildSmokeTest'; Methods=[ordered]@{
        frozenDeletionRebuildAndUnknownCommitStayRecoverable=3 } },
    @{ Class='com.aifriend.task.application.TaskSessionServiceTest'; Methods=[ordered]@{
        confirmedSendMustAllowInvitationContactWithoutHistoricalWechatVersionAndBindProof=1;
        confirmedVoiceCallMustAllowDifferentWechatVersionAndBindCurrentVersionProof=1 } },
    @{ Class='com.aifriend.assistant.infrastructure.AssistantSessionConfigurationTest'; Methods=[ordered]@{
        defaultOffKeepsCleanupButNoOnlineServicesOrThreadPool=1 } }
)
$selection = ($suites | ForEach-Object { $_.Class + '#' + ($_.Methods.Keys -join '+') }) -join ','
$startedUtc = [DateTime]::UtcNow
Push-Location -LiteralPath $serverDirectory
try {
    & (Join-Path $serverDirectory 'mvnw.cmd') -o -q "-Dtest=$selection" "-DargLine=$TestJvmArguments" test
    if ($LASTEXITCODE -ne 0) { throw 'Frozen functional replay failed; no PASS summary emitted.' }
    $verified = 0
    foreach ($suite in $suites) {
        $path = Join-Path $serverDirectory ('target/surefire-reports/TEST-' + $suite.Class + '.xml')
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -lt $startedUtc) { throw "Stale report: $($suite.Class)" }
        [xml]$report = Get-Content -LiteralPath $path -Raw
        $expected = [int](($suite.Methods.Values | Measure-Object -Sum).Sum)
        if ([int]$report.testsuite.tests -ne $expected -or [int]$report.testsuite.failures -ne 0 -or
            [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0) {
            throw "Incorrect count, failed or skipped tests: $($suite.Class)"
        }
        foreach ($method in $suite.Methods.Keys) {
            $pattern = '^' + [regex]::Escape($method) + '(?:\([^)]*\)\[\d+\])?$'
            $cases = @($report.testsuite.testcase | Where-Object { $_.name -match $pattern })
            if ($cases.Count -ne $suite.Methods[$method]) { throw "Missing or extra selected instances: $method" }
            $verified += $cases.Count
        }
    }
    if ($verified -ne 70) { throw 'Frozen suite instance total changed unexpectedly.' }
    Write-Output 'OFFLINE FUNCTIONAL REPLAY PASS: 60/60 frozen scenarios; 10 supporting checks; 70/70 test instances.'
    Write-Output 'Categories: public supported 20, no evidence 8, lifecycle 8, graph 8, security 10, faults 6.'
    Write-Output 'Lifecycle registration/publication include component simulations; other boundary scenarios use HTTP simulations.'
    Write-Output 'No live model quality, real MySQL isolation/migration, device or performance acceptance is claimed.'
} finally {
    Pop-Location
}
