<#
固定SM01-SM12离线模拟入口。不得据此宣称真实数据库、真实模型或手机验收通过。
SM03是登记与发布的互补组件用例；SM12含旧任务基线，并非真机端到端验证。
只执行Maven test，不运行IT/全量测试/打包，不接受任意测试选择器。
#>
[CmdletBinding()]
param([string]$TestJvmArguments = '-Xms64m -Xmx512m')

$ErrorActionPreference = 'Stop'
$serverDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$http = 'com.aifriend.assistant.infrastructure.JdbcAssistantTurnRepositoryTest'
$generation = 'com.aifriend.retrieval.infrastructure.JdbcKnowledgeGenerationAdapterTest'
$graph = "$http#httpGraphAmbiguityForeignContactUnbindAndConsentRevocationSmoke"
$checks = @(
    @{ Id='SM01'; Scope='HTTP/extractive'; Tests=@("$http#sm01And02HttpExtractiveStepsAndNoEvidenceNeverInventAnswers") },
    @{ Id='SM02'; Scope='HTTP/no evidence'; Tests=@("$http#sm01And02HttpExtractiveStepsAndNoEvidenceNeverInventAnswers") },
    @{ Id='SM03'; Scope='Complementary JDBC simulations, not one import HTTP chain'; Tests=@('com.aifriend.retrieval.infrastructure.JdbcKnowledgeImportRegistrationAdapterTest#sameKeyReturnsOriginalJobWithoutNewDocumentOrVersion', "$generation#beginAndBatchReplayDoNotOverwriteOrDuplicate") },
    @{ Id='SM04'; Scope='Actual publication/reader/BM25 over simulated SQL'; Tests=@("$generation#sm04UpdatePublicationChangesAnswerVersionAndInvalidatesOldEvidence") },
    @{ Id='SM05'; Scope='HTTP/delete/rebuild/replay'; Tests=@("$http#httpAnswerAdminDeletionOldReplayAndRebuiltRemainingKnowledgeSmoke") },
    @{ Id='SM06'; Scope='HTTP with production model protocol and mocked transport'; Tests=@("$http#sm06Http401AndInvalidJsonYieldLabeledEvidenceWithoutBlindRetry") },
    @{ Id='SM07'; Scope='Actual graph traversal, simulated source/persistence'; Tests=@("$http#privateQuestionToActualGraphTraversalCommitAndReplayNeverInvokesKnowledge") },
    @{ Id='SM08'; Scope='HTTP authorization/owner boundaries'; Tests=@("$http#httpBearerAccountDeviceAndOwnerIsolationBeforeAnswerReads", $graph) },
    @{ Id='SM09'; Scope='HTTP ambiguity'; Tests=@($graph) },
    @{ Id='SM10'; Scope='HTTP stale projection/unbind/revoke'; Tests=@($graph) },
    @{ Id='SM11'; Scope='HTTP injection plus architecture boundary'; Tests=@("$http#sm11HttpMaliciousGuideNeverReachesModelOrContactActions", 'com.aifriend.ArchitectureTest#knowledgeMustNotDependOnContactExecutionOrTaskAuthorization', 'com.aifriend.ArchitectureTest#privateGraphMustNotDependOnModelGateways') },
    @{ Id='SM12'; Scope='Feature-off wiring/HTTP plus old-task mock baseline, not device acceptance'; Tests=@("$http#httpFeatureOffRejectsNewWorkButAuthorizedLogicalDeletionStillWorks", 'com.aifriend.assistant.infrastructure.AssistantSessionConfigurationTest#defaultOffKeepsCleanupButNoOnlineServicesOrThreadPool', 'com.aifriend.task.application.TaskSessionServiceTest#confirmedSendMustAllowInvitationContactWithoutHistoricalWechatVersionAndBindProof', 'com.aifriend.task.application.TaskSessionServiceTest#confirmedVoiceCallMustAllowDifferentWechatVersionAndBindCurrentVersionProof') }
)
$guard = 'com.aifriend.retrieval.infrastructure.KnowledgeAcceptanceDatasetTest'
$selectors = @($checks | ForEach-Object { $_.Tests }) + @(
    "$guard#frozenInputsMustRetainPreExecutionDigests",
    "$guard#sixtyCasesMustKeepIndependentCategoriesAndExplicitOracles",
    "$guard#answerableOraclesMustReferenceSyntheticCorpusFacts")
$selectors = @($selectors | Sort-Object -Unique)
$groups = $selectors | Group-Object { ($_ -split '#')[0] }
$selection = ($groups | ForEach-Object {
    $_.Name + '#' + (($_.Group | ForEach-Object { ($_ -split '#')[1] }) -join '+')
}) -join ','
$startedUtc = [DateTime]::UtcNow
Push-Location -LiteralPath $serverDirectory
try {
    & (Join-Path $serverDirectory 'mvnw.cmd') -o -q "-Dtest=$selection" "-DargLine=$TestJvmArguments" test
    if ($LASTEXITCODE -ne 0) { throw 'Targeted knowledge smoke failed; no PASS report emitted.' }
    foreach ($group in $groups) {
        $reportPath = Join-Path $serverDirectory ('target/surefire-reports/TEST-' + $group.Name + '.xml')
        $reportFile = Get-Item -LiteralPath $reportPath
        if ($reportFile.LastWriteTimeUtc -lt $startedUtc) { throw "Stale test report: $($group.Name)" }
        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        if ([int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0 -or [int]$report.testsuite.skipped -ne 0) {
            throw "Failed or skipped tests: $($group.Name)"
        }
        foreach ($selector in $group.Group) {
            $method = ($selector -split '#')[1]
            $matches = @($report.testsuite.testcase | Where-Object { $_.name -eq $method })
            if ($matches.Count -ne 1) { throw "Missing or duplicate selected test: $selector" }
        }
    }
    foreach ($check in $checks) { Write-Output ($check.Id + ' PASS [SIMULATION]: ' + $check.Scope) }
    Write-Output ("Selected tests: " + $selectors.Count + '; frozen-data checks included, not 60 functional results.')
    Write-Output 'Real MySQL isolation/migrations, real model quality and device acceptance remain UNVERIFIED.'
} finally {
    Pop-Location
}
