param(
    [Parameter(Mandatory)][string]$Dataset,
    [Parameter(Mandatory)][string]$Chunks,
    [Parameter(Mandatory)][string]$Output,
    [string]$JudgeBaseUrl,
    [string]$JudgeModel,
    [string]$JudgeApiKey,
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 120,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

. (Join-Path $PSScriptRoot 'rag-tool-common.ps1')
$repoRoot = Initialize-RagToolEnvironment -JdkHome $JdkHome
if ($JudgeBaseUrl) { $env:DATASET_JUDGE_BASE_URL = $JudgeBaseUrl }
if ($JudgeModel) { $env:DATASET_JUDGE_MODEL = $JudgeModel }
if ($JudgeApiKey) { $env:DATASET_JUDGE_API_KEY = $JudgeApiKey }
$env:DATASET_JUDGE_TIMEOUT_SECONDS = "$TimeoutSeconds"

$datasetPath = Resolve-RagToolPath $repoRoot $Dataset
$chunksPath = Resolve-RagToolPath $repoRoot $Chunks
$outputPath = Resolve-RagToolPath $repoRoot $Output
$code = Invoke-RagJavaMain -RepoRoot $repoRoot `
    -MainClass 'com.example.server.evaluation.dataset.DatasetToolCli' `
    -ApplicationArguments @(
        '--evaluation.dataset.command=judge',
        "--evaluation.dataset.input=$datasetPath",
        "--evaluation.dataset.chunks=$chunksPath",
        "--evaluation.dataset.output=$outputPath",
        "--evaluation.dataset.judge-timeout-seconds=$TimeoutSeconds"
    )
exit $code
