<#
.SYNOPSIS
  启动 Video2NoteAI 后端（加载 .env、显式 JDK 21、可选覆盖端口/数据库/Flyway 目标版本）。

.DESCRIPTION
  .env 是 bash 风格，只有 DB_URL 被单引号包裹；直接读会把引号带进 JDBC URL，启动将失败于
  "Driver com.mysql.cj.jdbc.Driver claims to not accept jdbcUrl"。本脚本统一剥离成对引号，
  并提供 V4 旧库迁移验证所需的 DB_URL / 端口 / Flyway target 覆盖。

.EXAMPLE
  # 正常启动（端口与数据库取自 .env）
  pwsh -File scripts/start-server.ps1

.EXAMPLE
  # 只在临时库上应用 V1-V3，用于验证 V4 能在“已有库”上执行
  pwsh -File scripts/start-server.ps1 -DbUrl "jdbc:mysql://localhost:3307/media_db_legacy?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true" -Port 9091 -FlywayTarget 3
#>
param(
    [string]$DbUrl,
    [int]$Port = 0,
    [string]$FlywayTarget,
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot '.env'
if (-not (Test-Path $envFile)) { throw "缺少 $envFile" }

Get-Content $envFile | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $parts = $_ -split '=', 2
    $name = $parts[0].Trim()
    $value = $parts[1].Trim()
    if ($value.Length -ge 2) {
        $first = $value[0]; $last = $value[$value.Length - 1]
        if (($first -eq [char]39 -and $last -eq [char]39) -or ($first -eq [char]34 -and $last -eq [char]34)) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }
    Set-Item -Path ("Env:" + $name) -Value $value
}

if ($DbUrl) { $env:DB_URL = $DbUrl }
if ($Port -gt 0) { $env:SERVER_PORT = "$Port" }
if ($FlywayTarget) { $env:SPRING_FLYWAY_TARGET = $FlywayTarget }

if (-not $JdkHome) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaHomeSetting = & $javaCommand.Source -XshowSettings:properties -version 2>&1 |
            Select-String -Pattern '^\s*java\.home\s*=' |
            Select-Object -First 1
        if ($javaHomeSetting) {
            $JdkHome = ($javaHomeSetting.Line -split '=', 2)[1].Trim()
        }
    }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\javac.exe'))) {
    throw "找不到 JDK 21（设置 JAVA_HOME 或用 -JdkHome 指定）"
}
$env:JAVA_HOME = $JdkHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Write-Output ("server starting: port={0} db={1} flywayTarget={2}" -f $env:SERVER_PORT, ($env:DB_URL -replace '\?.*$', ''), $(if ($FlywayTarget) { $FlywayTarget } else { 'latest' }))
$serverDir = Join-Path $repoRoot 'server'
Set-Location $serverDir
& (Join-Path $serverDir 'mvnw.cmd') -s .mvn\central-settings.xml spring-boot:run
