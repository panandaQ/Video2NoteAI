# 一次性工具：清理 MinIO media bucket，只保留 media 62 的资源。
# 保留：video-import/content/7780b0edcdf062403bc0903a705ab444/** 与 media 62 checkpoint 引用的 evidence-frames/*.jpg
# 删除：其余全部对象（分页列出，POST ?delete 批量删除，每批最多 1000 个 key）。
# 用法: pwsh -File scripts\minio-cleanup-keep-62.ps1
param(
    [string]$KeepHash = '7780b0edcdf062403bc0903a705ab444',
    [string]$KeepFramesFile = 'D:\Java\video\.cleanup\keep-frames.txt'
)

$ErrorActionPreference = 'Stop'
$accessKey = 'dovideo'
$secretKey = 'change-me-now'
$bucket = 'media'
$endpoint = 'localhost:9000'
$region = 'us-east-1'
$service = 's3'

function Hex([byte[]]$bytes) { return -join ($bytes | ForEach-Object { $_.ToString('x2') }) }
function HmacSha256([byte[]]$key, [string]$data) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($key)
    return $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($data))
}
function Sha256Hex([string]$data) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    return Hex($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($data)))
}
function Md5B64([string]$data) {
    $md5 = [System.Security.Cryptography.MD5]::Create()
    return [Convert]::ToBase64String($md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($data)))
}
function GetAuthorization([string]$amzDate, [string]$canonicalRequest, [string]$signedHeaders) {
    $dateStamp = $amzDate.Substring(0, 8)
    $stringToSign = "AWS4-HMAC-SHA256`n$amzDate`n$dateStamp/$region/$service/aws4_request`n" + (Sha256Hex $canonicalRequest)
    $kDate = HmacSha256 ([System.Text.Encoding]::UTF8.GetBytes("AWS4$secretKey")) $dateStamp
    $kRegion = HmacSha256 $kDate $region
    $kService = HmacSha256 $kRegion $service
    $kSigning = HmacSha256 $kService 'aws4_request'
    $signature = Hex (HmacSha256 $kSigning $stringToSign)
    return "AWS4-HMAC-SHA256 Credential=$accessKey/$dateStamp/$region/$service/aws4_request, SignedHeaders=$signedHeaders, Signature=$signature"
}
function ListPage([string]$canonicalQuery) {
    $amzDate = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $payloadHash = 'UNSIGNED-PAYLOAD'
    $uri = "/$bucket"
    # MinIO 按 key 排序重建 canonical query，签名与发送都用排序后的形式
    $sorted = ($canonicalQuery -split '&' | Sort-Object) -join '&'
    $signedHeaders = 'host;x-amz-content-sha256;x-amz-date'
    $canonicalRequest = "GET`n$uri`n$sorted`nhost:$endpoint`nx-amz-content-sha256:$payloadHash`nx-amz-date:$amzDate`n`n$signedHeaders`n$payloadHash"
    $authorization = GetAuthorization $amzDate $canonicalRequest $signedHeaders
    $out = & curl.exe -s -i -X GET "http://$endpoint$uri`?$sorted" `
        -H "Host: $endpoint" `
        -H "x-amz-date: $amzDate" `
        -H "x-amz-content-sha256: $payloadHash" `
        -H "Authorization: $authorization"
    $text = $out -join "`n"
    $bodyStart = $text.IndexOf("`n`n")
    if ($bodyStart -lt 0) { throw "list 响应无 body: $($text.Substring(0, [Math]::Min(300, $text.Length)))" }
    return $text.Substring($bodyStart + 4)
}
function DeleteBatch([string[]]$keys) {
    $amzDate = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $objectsXml = ($keys | ForEach-Object { "<Object><Key>$_</Key></Object>" }) -join ''
    $body = "<?xml version=`"1.0`" encoding=`"UTF-8`"?><Delete>$objectsXml<Quiet>true</Quiet></Delete>"
    $payloadHash = Sha256Hex $body
    $contentMd5 = Md5B64 $body
    $uri = "/$bucket"
    $canonicalQuery = 'delete='
    $signedHeaders = 'content-md5;host;x-amz-content-sha256;x-amz-date'
    $canonicalRequest = "POST`n$uri`n$canonicalQuery`ncontent-md5:$contentMd5`nhost:$endpoint`nx-amz-content-sha256:$payloadHash`nx-amz-date:$amzDate`n`n$signedHeaders`n$payloadHash"
    $authorization = GetAuthorization $amzDate $canonicalRequest $signedHeaders
    $out = & curl.exe -s -i -X POST "http://$endpoint$uri`?delete" `
        -H "Host: $endpoint" `
        -H "x-amz-date: $amzDate" `
        -H "x-amz-content-sha256: $payloadHash" `
        -H "content-md5: $contentMd5" `
        -H "Authorization: $authorization" `
        --data-binary $body
    $text = $out -join "`n"
    $bodyStart = $text.IndexOf("`n`n")
    if ($bodyStart -lt 0) { throw "delete 响应无 body: $($text.Substring(0, [Math]::Min(300, $text.Length)))" }
    return $text.Substring($bodyStart + 4)
}

$keepFrames = @{}
if (Test-Path $KeepFramesFile) {
    Get-Content $KeepFramesFile | ForEach-Object { if ($_.Trim()) { $keepFrames["evidence-frames/$($_.Trim()).jpg"] = $true } }
}
Write-Host "keep frames loaded: $($keepFrames.Count)"

# ---- 枚举顶层前缀 ----
$text = ListPage 'list-type=2&delimiter=%2F&max-keys=1000'
$prefixes = [regex]::Matches($text, '<Prefix>([^<]+)</Prefix>') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
Write-Host ("top prefixes: " + ($prefixes -join ', '))
$rootKeys = [regex]::Matches($text, '<Key>([^<]+)</Key>') | ForEach-Object { $_.Groups[1].Value }
Write-Host ("root-level keys: " + $rootKeys.Count)

# ---- 逐前缀列出全部对象（start-after 分页）----
$allKeys = [System.Collections.Generic.List[string]]::new()
foreach ($k in $rootKeys) { $allKeys.Add($k) }
foreach ($prefix in $prefixes) {
    if ($prefix -eq '') { continue }
    $startAfter = ''
    $page = 0
    do {
        $page++
        $encPrefix = [Uri]::EscapeDataString($prefix)
        $q = "list-type=2&max-keys=1000&prefix=$encPrefix"
        if ($startAfter -ne '') { $q += '&start-after=' + [Uri]::EscapeDataString($startAfter) }
        $text = ListPage $q
        $pageKeys = [regex]::Matches($text, '<Key>([^<]+)</Key>') | ForEach-Object { $_.Groups[1].Value }
        foreach ($k in $pageKeys) { $allKeys.Add($k) }
        $truncated = ([regex]::Match($text, '<IsTruncated>([^<]+)</IsTruncated>')).Groups[1].Value -eq 'true'
        if ($pageKeys.Count -gt 0) { $startAfter = $pageKeys[$pageKeys.Count - 1] }
        Write-Host "prefix [$prefix] page $page : +$($pageKeys.Count) truncated=$truncated"
    } while ($truncated -and $pageKeys.Count -gt 0)
}

Write-Host "total objects: $($allKeys.Count)"

# ---- 分区：保留 vs 删除 ----
$keep = [System.Collections.Generic.List[string]]::new()
$delete = [System.Collections.Generic.List[string]]::new()
foreach ($k in $allKeys) {
    if ($k -like "video-import/content/$KeepHash/*" -or $keepFrames.ContainsKey($k)) { $keep.Add($k) }
    else { $delete.Add($k) }
}
Write-Host "keep: $($keep.Count)  delete: $($delete.Count)"
$keep | Set-Content -Path '.cleanup\minio-kept-keys.txt' -Encoding utf8

# ---- 批量删除（POST ?delete，每批 300，避免超出 Windows 单参数 32KB 限制）----
# Quiet=true 时成功删除不回显 <Deleted>，以无 <Error> 视为整批成功
$deleted = 0
for ($i = 0; $i -lt $delete.Count; $i += 300) {
    $batch = $delete.GetRange($i, [Math]::Min(300, $delete.Count - $i))
    $resp = DeleteBatch $batch
    $errCount = [regex]::Matches($resp, '<Error>').Count
    if ($errCount -gt 0) {
        $errMsg = ([regex]::Match($resp, '<Message>([^<]+)</Message>')).Groups[1].Value
        Write-Host "batch $($i/300): $errCount errors: $errMsg"
        $deleted += ($batch.Count - $errCount)
    } else {
        Write-Host "batch $($i/300): deleted $($batch.Count)"
        $deleted += $batch.Count
    }
}
Write-Host "deleted total: $deleted (kept $($keep.Count))"
