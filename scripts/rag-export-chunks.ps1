param(
    [Parameter(Mandatory)][string]$Request,
    [Parameter(Mandatory)][string]$Output,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

. (Join-Path $PSScriptRoot 'rag-tool-common.ps1')
$repoRoot = Initialize-RagToolEnvironment -JdkHome $JdkHome
$inputPath = Resolve-RagToolPath $repoRoot $Request
$outputPath = Resolve-RagToolPath $repoRoot $Output
$code = Invoke-RagJavaMain -RepoRoot $repoRoot `
    -MainClass 'com.example.server.evaluation.dataset.DatasetToolCli' `
    -ApplicationArguments @(
        '--evaluation.dataset.command=export-chunks',
        "--evaluation.dataset.input=$inputPath",
        "--evaluation.dataset.output=$outputPath"
    )
exit $code
