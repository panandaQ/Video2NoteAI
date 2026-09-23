<#
.SYNOPSIS
  单视频连续追问的纯接口验收脚本（D-092）：只用登录 + 五个 HTTP 接口 + SSE（curl），不依赖前端。

.DESCRIPTION
  覆盖 runbook §16 中可用接口验证的项：首问、指代/省略/对比追问、无答案拒答、幂等回放、
  同会话并发 CAS、跨用户 404、会话头与历史页重建、SSE 回放与实时终态、401/400/422/409 负例。
  "执行中杀 JVM → 僵尸收敛 → 重试" 的故障注入需要重启服务器，在 §7 手工执行，不在本脚本内。

  全程约 6~9 分钟（每问含真实模型调用）；单次控制台执行受超时限制时可分段续跑：
  先跑 1~3（记录输出里的 conversationId/turnId），再用
  `-ResumeFrom 4 -ConversationId <id> -FirstTurnId <id>` 续跑 4~7（3.4 会一起执行）。

.EXAMPLE
  pwsh -File scripts/test-knowledge-api.ps1 -BaseUrl http://127.0.0.1:9094 -MediaId 62
  pwsh -File scripts/test-knowledge-api.ps1 -BaseUrl http://127.0.0.1:9094 -ResumeFrom 4 -ConversationId 2 -FirstTurnId 5
#>
param(
    [string]$BaseUrl = "http://127.0.0.1:9094",
    [long]$MediaId = 62,
    [string]$Username = "test1",
    [string]$Password = "12345678",
    [int]$ResumeFrom = 1,
    [long]$ConversationId = 0,
    [long]$FirstTurnId = 0
)

$ErrorActionPreference = 'Stop'
$script:failures = 0

function Pass([string]$name) { Write-Host ("PASS  " + $name) -ForegroundColor Green }
function Fail([string]$name, [string]$detail) {
    $script:failures++
    Write-Host ("FAIL  " + $name + "  =>  " + $detail) -ForegroundColor Red
}
function Check([string]$name, [bool]$condition, [string]$detail = '') {
    if ($condition) { Pass $name } else { Fail $name $detail }
}

function Login([string]$user, [string]$pass) {
    $body = @{ username = $user; password = $pass } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/login" -ContentType 'application/json' -Body $body
    return $r.data.token
}
function Headers([string]$token) { @{ Authorization = "Bearer $token" } }

function Post-Question([string]$token, [hashtable]$body) {
    return Invoke-WebRequest -Method Post -Uri "$BaseUrl/knowledge/questions" `
        -Headers (Headers $token) -ContentType 'application/json' `
        -Body ($body | ConvertTo-Json -Compress) -SkipHttpErrorCheck
}
function Get-Turn([string]$token, [long]$conversationId, [long]$turnId) {
    return (Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId/turns/$turnId" `
        -Headers (Headers $token)).data
}
function Wait-Terminal([string]$token, [long]$conversationId, [long]$turnId, [int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $turn = Get-Turn $token $conversationId $turnId
        if ($turn.status -ne 'PROCESSING') { return $turn }
        Start-Sleep -Seconds 2
    }
    throw "轮次 $turnId 在 $timeoutSec 秒内未到终态"
}
function New-RequestId { [guid]::NewGuid().ToString() }

Write-Host "=== 0 登录（$Username）==="
$token = Login $Username $Password
Check '0.1 登录取得 token' ($token -and $token.Length -gt 0)

if ($ResumeFrom -le 1) {
    Write-Host "=== 1 首问（POST 202 → SSE → GET 单轮）==="
    $req1 = New-RequestId
    $r = Post-Question $token @{ requestId = $req1; question = '视频中提到了哪些武侠的新载体？'; scope = @{ type = 'SINGLE_VIDEO'; mediaId = $MediaId } }
    Check '1.1 首问返回 202' ($r.StatusCode -eq 202) ("HTTP " + $r.StatusCode + " " + $r.Content)
    $d1 = ($r.Content | ConvertFrom-Json).data
    Check '1.2 受理响应字段齐全' ($d1.conversationId -and $d1.turnId -and $d1.turnNo -eq 1 -and $d1.status -eq 'PROCESSING' -and $d1.reused -eq $false)
    Check '1.3 eventsUrl 正确' ($d1.eventsUrl -eq "/knowledge/conversations/$($d1.conversationId)/turns/$($d1.turnId)/events")
    $conversationId = [long]$d1.conversationId
    $turnId1 = [long]$d1.turnId
    Write-Host ("  conversationId=" + $conversationId + " turnId1=" + $turnId1 + " requestId=" + $req1)

    $sseJob = Start-Job -ScriptBlock {
        param($url, $auth)
        curl.exe -s -N --max-time 240 -H "Authorization: Bearer $auth" $url
    } -ArgumentList "$BaseUrl/knowledge/conversations/$conversationId/turns/$turnId1/events", $token

    $t1 = Wait-Terminal $token $conversationId $turnId1
    $sse = (Receive-Job $sseJob -Wait -AutoRemoveJob) -join "`n"
    Check '1.4 SSE 回放 PROCESSING' ($sse -match '"state":"PROCESSING"')
    Check '1.5 SSE 实时推送终态' ($sse -match ('"state":"' + $t1.status + '"'))
    Check '1.6 首问到终态' ($t1.status -eq 'COMPLETED') ("status=" + $t1.status + " errorCode=" + $t1.errorCode)
    if ($t1.evidence) {
        Check '1.7 引用证据结构完整' (($t1.evidence | Where-Object { $_.rank -and $_.mediaId -and $_.title -and ($_.endMs -gt $_.startMs) -and $_.source -and $_.snippet }).Count -eq $t1.evidence.Count)
    }
    Write-Host ("  首问结果: answerable=" + $t1.answerable + " cited=" + @($t1.evidence).Count)

    Write-Host "=== 2 幂等回放（同 requestId → 200 原终态）==="
    $r = Post-Question $token @{ requestId = $req1; conversationId = $conversationId; question = '视频中提到了哪些武侠的新载体？' }
    $d = ($r.Content | ConvertFrom-Json).data
    Check '2.1 回放返回 200' ($r.StatusCode -eq 200) ("HTTP " + $r.StatusCode)
    Check '2.2 回放指向同一轮次' ($d.turnId -eq $turnId1 -and $d.reused -eq $true -and $d.status -eq $t1.status)
} else {
    $conversationId = $ConversationId
    $turnId1 = $FirstTurnId
    Check '0.2 续跑参数' ($conversationId -gt 0 -and $turnId1 -gt 0) ("ConversationId=" + $conversationId + " FirstTurnId=" + $turnId1)
}

if ($ResumeFrom -le 3) {
    Write-Host "=== 3 追问：指代 / 省略 / 对比 / 无答案 ==="
    $questions = @(
        @{ key = '3.1 指代追问'; q = '它们相比传统的武侠形式有什么不同？' },
        @{ key = '3.2 省略追问'; q = '还有别的例子吗？' },
        @{ key = '3.3 对比追问'; q = '这些载体里，哪种在视频中被说得最多？' },
        @{ key = '3.4 无答案问题'; q = '视频里讲到了火星移民计划吗？' }
    )
    foreach ($item in $questions) {
        $req = New-RequestId
        $r = Post-Question $token @{ requestId = $req; conversationId = $conversationId; question = $item.q }
        $d = ($r.Content | ConvertFrom-Json).data
        if ($r.StatusCode -ne 202) { Fail $item.key ("受理失败 HTTP " + $r.StatusCode + " " + $r.Content); continue }
        $turn = Wait-Terminal $token $conversationId ([long]$d.turnId)
        Check ($item.key + ' 到达终态') ($turn.status -eq 'COMPLETED') ("status=" + $turn.status + " errorCode=" + $turn.errorCode)
        if ($turn.status -eq 'COMPLETED' -and $turn.answerable -eq $false) {
            Check ($item.key + ' 拒答文案受控') ($turn.answer -eq '视频中没有找到能回答这个问题的内容。')
        }
        if ($turn.rewrittenQuery -and $turn.rewrittenQuery -ne $item.q) {
            Pass ($item.key + ' 查询改写生效: ' + $turn.rewrittenQuery)
        }
        Write-Host ("  " + $item.key + " answerable=" + $turn.answerable)
    }
}

if ($ResumeFrom -le 4) {
    Write-Host "=== 4 同会话并发 CAS（不同 requestId 只有一个赢家）==="
    $reqA = New-RequestId; $reqB = New-RequestId
    $jobA = Start-Job -ScriptBlock {
        param($baseUrl, $token, $conversationId, $reqId)
        $body = @{ requestId = $reqId; conversationId = $conversationId; question = '并发问题A' } | ConvertTo-Json
        try { $r = Invoke-WebRequest -Method Post -Uri "$baseUrl/knowledge/questions" -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $body -SkipHttpErrorCheck; [int]$r.StatusCode }
        catch { 0 }
    } -ArgumentList $BaseUrl, $token, $conversationId, $reqA
    $jobB = Start-Job -ScriptBlock {
        param($baseUrl, $token, $conversationId, $reqId)
        $body = @{ requestId = $reqId; conversationId = $conversationId; question = '并发问题B' } | ConvertTo-Json
        try { $r = Invoke-WebRequest -Method Post -Uri "$baseUrl/knowledge/questions" -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -Body $body -SkipHttpErrorCheck; [int]$r.StatusCode }
        catch { 0 }
    } -ArgumentList $BaseUrl, $token, $conversationId, $reqB
    $codeA = Receive-Job $jobA -Wait -AutoRemoveJob
    $codeB = Receive-Job $jobB -Wait -AutoRemoveJob
    $codes = @($codeA, $codeB) | Sort-Object
    Check '4.1 并发双问：一个 202 一个 409' ($codes -contains 202 -and $codes -contains 409) ("codes=" + ($codes -join ','))
    Start-Sleep -Seconds 2
}

if ($ResumeFrom -le 5) {
    Write-Host "=== 5 跨用户隔离（第二账号全部 404）==="
    $other = 'apitest' + (Get-Random -Minimum 10000 -Maximum 99999)
    $r = Invoke-WebRequest -Method Post -Uri "$BaseUrl/user/register" -ContentType 'application/json' `
        -Body (@{ username = $other; password = '12345678'; nickname = 'api' } | ConvertTo-Json) -SkipHttpErrorCheck
    if ($r.StatusCode -ne 200) { Fail '5.0 注册第二账号' ("HTTP " + $r.StatusCode + " " + $r.Content) }
    $otherToken = Login $other '12345678'
    $checks = @(
        @{ key = '5.1 会话头'; path = "/knowledge/conversations/$conversationId" },
        @{ key = '5.2 历史页'; path = "/knowledge/conversations/$conversationId/turns" },
        @{ key = '5.3 单轮'; path = "/knowledge/conversations/$conversationId/turns/$turnId1" },
        @{ key = '5.4 SSE 订阅'; path = "/knowledge/conversations/$conversationId/turns/$turnId1/events" }
    )
    foreach ($c in $checks) {
        $code = try { (Invoke-WebRequest -Uri ($BaseUrl + $c.path) -Headers (Headers $otherToken) -SkipHttpErrorCheck).StatusCode } catch { 0 }
        Check ($c.key + ' 跨用户 404') ($code -eq 404) ("HTTP " + $code)
    }
    $r = Post-Question $otherToken @{ requestId = (New-RequestId); conversationId = $conversationId; question = '越权追问' }
    Check '5.5 跨用户追问 404' ($r.StatusCode -eq 404) ("HTTP " + $r.StatusCode)
}

if ($ResumeFrom -le 6) {
    Write-Host "=== 6 刷新重建（会话头 + 历史页）==="
    $header = (Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId" -Headers (Headers $token)).data
    Check '6.1 会话头含范围与版本' ($header.scopeType -eq 'SINGLE_VIDEO' -and $header.scopeMediaId -eq $MediaId -and $header.version -ge 1)
    $page = (Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId/turns?limit=50" -Headers (Headers $token)).data
    Check '6.2 历史页正序且包含首问' ($page.Count -ge 2 -and $page[0].turnNo -eq 1 -and $page[0].turnId -eq $turnId1)
    $turnNos = @($page | ForEach-Object { $_.turnNo })
    Check '6.3 turnNo 连续无空洞' (($turnNos | Sort-Object) -join ',' -eq (1..$page.Count -join ',')) ("turnNos=" + ($turnNos -join ','))
}

if ($ResumeFrom -le 7) {
    Write-Host "=== 7 负例（401 / 400 / 422 / 409）==="
    $code = (Invoke-WebRequest -Uri "$BaseUrl/knowledge/conversations/$conversationId" -SkipHttpErrorCheck).StatusCode
    Check '7.1 无 Token 401' ($code -eq 401) ("HTTP " + $code)
    $r = Post-Question $token @{ requestId = 'not-a-uuid'; question = '问题' }
    Check '7.2 非法 requestId 400' ($r.StatusCode -eq 400) ("HTTP " + $r.StatusCode)
    $r = Post-Question $token @{ requestId = (New-RequestId); question = '问题'; scope = @{ type = 'LIBRARY' } }
    Check '7.3 LIBRARY 范围 422' ($r.StatusCode -eq 422) ("HTTP " + $r.StatusCode + " " + $r.Content)
    $r = Post-Question $token @{ requestId = (New-RequestId); conversationId = $conversationId; question = '问题'; scope = @{ type = 'SINGLE_VIDEO'; mediaId = 1 } }
    Check '7.4 scope 不一致 409' ($r.StatusCode -eq 409 -and $r.Content -match 'CONVERSATION_SCOPE_MISMATCH') ("HTTP " + $r.StatusCode + " " + $r.Content)
}

Write-Host ""
$summary = if ($script:failures -eq 0) { "全部通过" } else { "$script:failures 项失败" }
Write-Host ("=== 结果：" + $summary + " ===")
if ($script:failures -gt 0) { exit 1 }
