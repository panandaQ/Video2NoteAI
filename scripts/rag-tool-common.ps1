Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Initialize-RagToolEnvironment {
    param([string]$JdkHome)

    $repoRoot = Split-Path -Parent $PSScriptRoot
    $envFile = Join-Path $repoRoot '.env'
    if (Test-Path $envFile) {
        Get-Content $envFile | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
            $parts = $_ -split '=', 2
            $name = $parts[0].Trim()
            $value = $parts[1].Trim()
            if ($value.Length -ge 2) {
                $first = $value[0]
                $last = $value[$value.Length - 1]
                if (($first -eq [char]39 -and $last -eq [char]39) -or
                    ($first -eq [char]34 -and $last -eq [char]34)) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
            }
            $environmentPath = "Env:" + $name
            if (-not (Test-Path $environmentPath) -or [string]::IsNullOrWhiteSpace((Get-Item $environmentPath).Value)) {
                Set-Item -Path $environmentPath -Value $value
            }
        }
    }

    if ($JdkHome) {
        if (-not (Test-Path -LiteralPath $JdkHome)) { throw "找不到 JDK：$JdkHome" }
        $env:JAVA_HOME = $JdkHome
        $env:Path = "$JdkHome\bin;$env:Path"
    }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '找不到 Maven（mvn）' }
    return $repoRoot
}

function Resolve-RagToolPath {
    param([string]$RepoRoot, [string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Invoke-RagJavaMain {
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string]$MainClass,
        [Parameter(Mandatory)][string[]]$ApplicationArguments
    )

    $argumentLine = ($ApplicationArguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join ' '
    Push-Location (Join-Path $RepoRoot 'server')
    try {
        & mvn -s .mvn\central-settings.xml `
            "-Dspring-boot.run.main-class=$MainClass" `
            "-Dspring-boot.run.arguments=$argumentLine" `
            org.springframework.boot:spring-boot-maven-plugin:run | Out-Host
        $mavenExitCode = $LASTEXITCODE
        return $mavenExitCode
    } finally {
        Pop-Location
    }
}
