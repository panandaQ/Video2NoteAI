# 把浏览器复制的整行 Cookie 转成 Netscape 格式（免手工格式化）
#
# 用法（以后换 Cookie 三步搞定）：
#   1) 浏览器登录 bilibili.com → F12 → Network → 刷新页面
#      → 点任意发往 *.bilibili.com 的请求 → Headers → Request Headers
#      → 复制整个 "Cookie: ..." 的值（一整行）
#   2) 把复制的内容粘进 .secrets\cookie-header.txt（纯文本，一行或多行都行）
#   3) 运行：  pwsh -File scripts\convert-bilibili-cookies.ps1
#   4) 重启应用即可生效
#
# 只会写入 4 个必需字段（SESSDATA/bili_jct/buvid3/DedeUserID），
# 其它噪音 Cookie 自动丢弃；生成的文件在 .secrets\（已被 .gitignore 忽略）。
param(
    [string]$SourceFile = ".secrets\cookie-header.txt",
    [string]$OutputFile = ".secrets\bilibili-cookies.txt"
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$source = Join-Path $workspace $SourceFile
$target = Join-Path $workspace $OutputFile

if (-not (Test-Path $source)) {
    throw "找不到 $source ：请先把浏览器复制的整行 Cookie 粘贴到该文件"
}

$raw = Get-Content $source -Raw
$raw = $raw -replace '(?i)^\s*cookie\s*:\s*', ''
$raw = $raw.Trim()

$want = @('SESSDATA', 'bili_jct', 'buvid3', 'DedeUserID')
$pairs = @{}
foreach ($piece in ($raw -split ';')) {
    $p = $piece.Trim()
    if (-not $p.Contains('=')) { continue }
    $name = $p.Substring(0, $p.IndexOf('=')).Trim()
    $value = $p.Substring($p.IndexOf('=') + 1)
    if ($name -eq '' -or $value.Contains("`t")) { continue }
    $pairs[$name] = $value
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# Netscape HTTP Cookie File')
foreach ($name in $want) {
    if ($pairs.ContainsKey($name)) {
        $lines.Add(".bilibili.com`tTRUE`t/`tFALSE`t0`t$name`t$($pairs[$name])")
    }
}

$missing = $want | Where-Object { -not $pairs.ContainsKey($_) }
if ($lines.Count -le 1) {
    throw "没有解析出任何有效 Cookie，请检查粘贴内容是否完整"
}
[System.IO.File]::WriteAllLines($target, $lines, [System.Text.UTF8Encoding]::new($false))

Write-Host "OK: 已写入 $target"
foreach ($name in $want) {
    $mark = if ($pairs.ContainsKey($name)) { 'OK  ' } else { 'MISS' }
    Write-Host "  $mark $name"
}
if ($missing.Count -gt 0) {
    Write-Warning "缺少字段：$($missing -join ', ')。缺 SESSDATA 时字幕/登录态不生效，请确认复制的是登录后页面的 Cookie。"
}
