param(
    [Parameter(Mandatory)][string]$Dataset,
    [Parameter(Mandatory)][string]$Output,
    [ValidateRange(1, 100)][int]$TopK = 5,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

. (Join-Path $PSScriptRoot 'rag-tool-common.ps1')
$repoRoot = Initialize-RagToolEnvironment -JdkHome $JdkHome
$datasetPath = Resolve-RagToolPath $repoRoot $Dataset
$outputPath = Resolve-RagToolPath $repoRoot $Output
$code = Invoke-RagJavaMain -RepoRoot $repoRoot `
    -MainClass 'com.example.server.evaluation.dataset.DatasetToolCli' `
    -ApplicationArguments @(
        '--evaluation.dataset.command=check-retrieval',
        "--evaluation.dataset.input=$datasetPath",
        "--evaluation.dataset.output=$outputPath",
        "--evaluation.dataset.top-k=$TopK"
    )
exit $code
