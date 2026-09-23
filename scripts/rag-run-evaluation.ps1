param(
    [Parameter(Mandatory)][string]$Config,
    [string]$Dataset,
    [string]$Output,
    [string]$JudgeBaseUrl,
    [string]$JudgeModel,
    [string]$JudgeApiKey,
    [ValidateRange(1, 3600)][int]$JudgeTimeoutSeconds = 120,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

. (Join-Path $PSScriptRoot 'rag-tool-common.ps1')
$repoRoot = Initialize-RagToolEnvironment -JdkHome $JdkHome
if ($JudgeBaseUrl) { $env:DATASET_JUDGE_BASE_URL = $JudgeBaseUrl }
if ($JudgeModel) { $env:DATASET_JUDGE_MODEL = $JudgeModel }
if ($JudgeApiKey) { $env:DATASET_JUDGE_API_KEY = $JudgeApiKey }
$env:DATASET_JUDGE_TIMEOUT_SECONDS = "$JudgeTimeoutSeconds"

$judgeValues = @($env:DATASET_JUDGE_API_KEY, $env:DATASET_JUDGE_BASE_URL, $env:DATASET_JUDGE_MODEL)
$configuredCount = @($judgeValues | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_) }).Count
if ($configuredCount -ne 0 -and $configuredCount -ne 3) {
    throw '独立 Judge 配置不完整：必须同时提供 DATASET_JUDGE_API_KEY / BASE_URL / MODEL'
}
$env:EVALUATION_RUNNER_CLAIM_JUDGE_ENABLED = if ($configuredCount -eq 3) { 'true' } else { 'false' }

$arguments = @('--config=' + (Resolve-RagToolPath $repoRoot $Config))
if ($Dataset) { $arguments += '--dataset=' + (Resolve-RagToolPath $repoRoot $Dataset) }
if ($Output) { $arguments += '--output=' + (Resolve-RagToolPath $repoRoot $Output) }
$code = Invoke-RagJavaMain -RepoRoot $repoRoot `
    -MainClass 'com.example.server.evaluation.runner.EvaluationRunnerCli' `
    -ApplicationArguments $arguments
exit $code
