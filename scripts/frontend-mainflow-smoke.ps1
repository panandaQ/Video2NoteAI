<# 新前端主流程冒烟验收脚本（Spec §7.3 的接口级等价实现）
  覆盖：登录 → 视频列表 → 已有笔记加载 → 播放地址 → 首问(新会话) → SSE 终态 → 单轮真源
        → 追问(conversationId) → 刷新恢复数据面(会话头+历史) → 登出
  用法：pwsh -File scripts/frontend-mainflow-smoke.ps1 [-BaseUrl http://localhost:5173] [-MediaId 62]
#>
param(
  [string]$BaseUrl = 'http://localhost:5173',
  [int]$MediaId = 0,
  [string]$Username = 'test1',
  [string]$Password = '12345678'
)

$ErrorActionPreference = 'Stop'
$script:failed = 0

function Pass([string]$name, [string]$detail = '') {
  Write-Output ("[PASS] {0}{1}" -f $name, $(if ($detail) { " - $detail" } else { '' }))
}
function Fail([string]$name, [string]$detail) {
  $script:failed += 1
  Write-Output ("[FAIL] {0} - {1}" -f $name, $detail)
}

# 1. 登录
try {
  $login = Invoke-RestMethod -Uri "$BaseUrl/user/login" -Method Post -ContentType 'application/json' `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) -TimeoutSec 15
  if ($login.code -eq 0) { Pass '登录' $login.data.userInfo.nickname }
  else { Fail '登录' $login.message }
  $token = $login.data.token
} catch { Fail '登录' $_.Exception.Message; exit 1 }

$headers = @{ Authorization = "Bearer $token" }

# 2. 视频列表（可学习视频）
try {
  $list = Invoke-RestMethod -Uri "$BaseUrl/media/list" -Headers $headers -TimeoutSec 15
  if ($list.code -ne 0) { Fail '视频列表' $list.message }
  else {
    $ready = @($list.data) | Where-Object { $_.status -eq 'READY' } | Select-Object -First 1
    if ($MediaId -gt 0) { $target = @($list.data) | Where-Object { $_.id -eq $MediaId } | Select-Object -First 1 }
    else { $target = $ready }
    if ($null -eq $target) { Fail '视频列表' '没有可学习视频' }
    else {
      $MediaId = $target.id
      Pass '视频列表' ("media {0}「{1}」" -f $target.id, $target.title)
    }
  }
} catch { Fail '视频列表' $_.Exception.Message }

# 3. 已有默认笔记加载（用户反馈修复项）
try {
  $note = Invoke-RestMethod -Uri "$BaseUrl/media/$MediaId/note" -Headers $headers -TimeoutSec 15
  if ($note.code -ne 0) { Fail '已有笔记加载' $note.message }
  elseif ($null -eq $note.data.note) { Fail '已有笔记加载' 'note 为 null（视频未生成笔记）' }
  else {
    Pass '已有笔记加载' ("「{0}」章节 {1} 证据 {2}" -f $note.data.note.title, @($note.data.note.sections).Count, @($note.data.note.evidence).Count)
  }
} catch { Fail '已有笔记加载' $_.Exception.Message }

# 4. 播放地址
try {
  $play = Invoke-RestMethod -Uri "$BaseUrl/media/playback?id=$MediaId" -Headers $headers -TimeoutSec 15
  $url = $play.data
  if ($url -is [string] -and $url -like 'http*') { Pass '播放地址' ('预签名 URL 长度 ' + $url.Length) }
  else { Fail '播放地址' '未取得预签名 URL' }
} catch { Fail '播放地址' $_.Exception.Message }

# 5. 首问（新会话，scope 固定媒体）
try {
  $requestId = [guid]::NewGuid().ToString()
  $body = @{ requestId = $requestId; question = '视频里提到的武侠小说四大宗师是谁？'; scope = @{ type = 'SINGLE_VIDEO'; mediaId = $MediaId } } | ConvertTo-Json -Depth 3
  $accepted = Invoke-RestMethod -Uri "$BaseUrl/knowledge/questions" -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 15
  if ($accepted.code -ne 0) { Fail '首问受理' $accepted.message; exit 1 }
  Pass '首问受理' ("conversation {0} turn {1} turnNo {2}" -f $accepted.data.conversationId, $accepted.data.turnId, $accepted.data.turnNo)
  $conversationId = $accepted.data.conversationId
  $turnId = $accepted.data.turnId
  $eventsUrl = $accepted.data.eventsUrl
} catch { Fail '首问受理' $_.Exception.Message; exit 1 }

# 6. SSE 终态
try {
  $http = [System.Net.Http.HttpClient]::new()
  $http.Timeout = [TimeSpan]::FromSeconds(150)
  $http.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
  $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$BaseUrl$eventsUrl")
  $request.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('text/event-stream'))
  $response = $http.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
  if (-not $response.IsSuccessStatusCode) { Fail 'SSE 订阅' ("HTTP {0}" -f [int]$response.StatusCode) }
  else {
    $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream)
    $deadline = (Get-Date).AddSeconds(140)
    $buffer = ''
    $terminal = $null
    while ((Get-Date) -lt $deadline) {
      $line = $reader.ReadLine()
      if ($null -eq $line) { Start-Sleep -Milliseconds 300; continue }
      $buffer += $line + "`n"
      if ($line -eq '') {
        $frame = $buffer
        $buffer = ''
        $dataLines = ($frame -split "`n") | Where-Object { $_ -like 'data:*' } | ForEach-Object { $_.Substring(5).TrimStart() }
        $data = ($dataLines -join "`n")
        if ($data) {
          $event = $data | ConvertFrom-Json
          Write-Output ("  SSE -> {0}" -f $event.state)
          if ($event.state -in @('COMPLETED', 'FAILED')) { $terminal = $event; break }
        }
      }
    }
    $reader.Dispose()
    if ($null -eq $terminal) { Fail 'SSE 终态' '超时未收到终态事件' }
    else { Pass 'SSE 终态' $terminal.state }
  }
  $http.Dispose()
} catch { Fail 'SSE 终态' $_.Exception.Message }

# 7. 单轮真源
try {
  $turn = Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId/turns/$turnId" -Headers $headers -TimeoutSec 15
  if ($turn.code -ne 0) { Fail '单轮真源' $turn.message }
  elseif ($turn.data.status -ne 'COMPLETED') { Fail '单轮真源' ("status={0} errorCode={1}" -f $turn.data.status, $turn.data.errorCode) }
  else { Pass '单轮真源' ("answerable={0} 证据 {1} 条" -f $turn.data.answerable, @($turn.data.evidence).Count) }
} catch { Fail '单轮真源' $_.Exception.Message }

# 8. 追问（conversationId 连续追问）
try {
  $requestId2 = [guid]::NewGuid().ToString()
  $body2 = @{ requestId = $requestId2; conversationId = $conversationId; question = '其中谁的江湖地位最高？' } | ConvertTo-Json
  $accepted2 = Invoke-RestMethod -Uri "$BaseUrl/knowledge/questions" -Method Post -Headers $headers -ContentType 'application/json' -Body $body2 -TimeoutSec 15
  if ($accepted2.code -ne 0) { Fail '追问受理' $accepted2.message }
  else {
    $turnId2 = $accepted2.data.turnId
    Pass '追问受理' ("turn {0} turnNo {1}" -f $turnId2, $accepted2.data.turnNo)
    $final = $null
    for ($i = 0; $i -lt 40; $i++) {
      Start-Sleep -Seconds 3
      $final = Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId/turns/$turnId2" -Headers $headers -TimeoutSec 15
      if ($final.data.status -in @('COMPLETED', 'FAILED')) { break }
    }
    if ($final.data.status -eq 'COMPLETED') { Pass '追问终态' ("rewritten={0}" -f $final.data.rewrittenQuery) }
    else { Fail '追问终态' ("status={0} errorCode={1}" -f $final.data.status, $final.data.errorCode) }
  }
} catch { Fail '追问' $_.Exception.Message }

# 9. 刷新恢复数据面（会话头 + 历史，turnNo 连续无空洞）
try {
  $conv = Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId" -Headers $headers -TimeoutSec 15
  $turns = Invoke-RestMethod -Uri "$BaseUrl/knowledge/conversations/$conversationId/turns" -Headers $headers -TimeoutSec 15
  if ($conv.code -ne 0) { Fail '刷新恢复' $conv.message }
  else {
    $nums = @($turns.data) | ForEach-Object { [int]$_.turnNo } | Sort-Object
    $contiguous = $true
    for ($i = 0; $i -lt $nums.Count; $i++) { if ($nums[$i] -ne $i + 1) { $contiguous = $false } }
    if ($contiguous) { Pass '刷新恢复' ("scopeMediaId={0} 轮次 {1} 连续" -f $conv.data.scopeMediaId, $nums.Count) }
    else { Fail '刷新恢复' ("turnNo 不连续: {0}" -f ($nums -join ',')) }
  }
} catch { Fail '刷新恢复' $_.Exception.Message }

# 10. 登出
try {
  $logout = Invoke-RestMethod -Uri "$BaseUrl/user/logout" -Method Post -Headers $headers -TimeoutSec 15
  if ($logout.code -eq 0) { Pass '登出' }
  else { Fail '登出' $logout.message }
} catch { Fail '登出' $_.Exception.Message }

Write-Output ''
if ($script:failed -eq 0) { Write-Output '== 主流程冒烟验收：全部 PASS ==' }
else { Write-Output ("== 主流程冒烟验收：{0} 项 FAIL ==" -f $script:failed) }
exit $(if ($script:failed -eq 0) { 0 } else { 1 })
