param(
    [Parameter(Mandatory)][string]$Dataset,
    [Parameter(Mandatory)][string]$Output,
    [ValidateRange(-1.0, 1.0)][double]$Threshold = 0.92,
    [string]$JdkHome = 'C:\Program Files\Java\jdk-21.0.12.1'
)

. (Join-Path $PSScriptRoot 'rag-tool-common.ps1')
$repoRoot = Initialize-RagToolEnvironment -JdkHome $JdkHome
$datasetPath = Resolve-RagToolPath $repoRoot $Dataset
$outputPath = Resolve-RagToolPath $repoRoot $Output
$thresholdText = $Threshold.ToString([System.Globalization.CultureInfo]::InvariantCulture)
$code = Invoke-RagJavaMain -RepoRoot $repoRoot `
    -MainClass 'com.example.server.evaluation.dataset.DatasetToolCli' `
    -ApplicationArguments @(
        '--evaluation.dataset.command=dedupe',
        "--evaluation.dataset.input=$datasetPath",
        "--evaluation.dataset.output=$outputPath",
        "--evaluation.dataset.threshold=$thresholdText"
    )
exit $code
