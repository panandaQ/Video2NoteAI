# 一次性工具：删除 MinIO 中的分析输入 manifest，让补充资产阶段带 Cookie 重探测。
# 用法: pwsh -File scripts\delete-minio-object.ps1 -Object 'video-import/content/7780b0edcdf062403bc0903a705ab444/analysis-input/v2/manifest.json'
param(
    [Parameter(Mandatory = $true)]
    [string]$Object
)

$ErrorActionPreference = 'Stop'
$accessKey = 'dovideo'
$secretKey = 'change-me-now'
$bucket = 'media'
$endpoint = 'localhost:9000'
$region = 'us-east-1'
$service = 's3'

$now = [DateTime]::UtcNow
$amzDate = $now.ToString('yyyyMMddTHHmmssZ')
$dateStamp = $now.ToString('yyyyMMdd')
$payloadHash = 'UNSIGNED-PAYLOAD'
$uri = "/$bucket/$Object"

function Hex([byte[]]$bytes) { return -join ($bytes | ForEach-Object { $_.ToString('x2') }) }
function HmacSha256([byte[]]$key, [string]$data) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($key)
    return $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($data))
}
function Sha256Hex([string]$data) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    return Hex($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($data)))
}

$canonicalRequest = "DELETE`n$uri`n`nhost:$endpoint`nx-amz-content-sha256:$payloadHash`nx-amz-date:$amzDate`n`nhost;x-amz-content-sha256;x-amz-date`n$payloadHash"
$stringToSign = "AWS4-HMAC-SHA256`n$amzDate`n$dateStamp/$region/$service/aws4_request`n" + (Sha256Hex $canonicalRequest)
$kDate = HmacSha256 ([System.Text.Encoding]::UTF8.GetBytes("AWS4$secretKey")) $dateStamp
$kRegion = HmacSha256 $kDate $region
$kService = HmacSha256 $kRegion $service
$kSigning = HmacSha256 $kService 'aws4_request'
$signature = Hex (HmacSha256 $kSigning $stringToSign)

$authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$dateStamp/$region/$service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature"

# PowerShell 的 Invoke-WebRequest 会拒绝带分号/逗号的 Header 值，改由 curl 发请求（MinIO 只校验签名，不挑客户端）
$curlOutput = & curl.exe -s -i -X DELETE "http://$endpoint$uri" `
    -H "Host: $endpoint" `
    -H "x-amz-date: $amzDate" `
    -H "x-amz-content-sha256: $payloadHash" `
    -H "Authorization: $authorization"
$curlOutput | Select-Object -First 5
$statusLine = $curlOutput | Select-Object -First 1
if ($statusLine -notmatch '20[04]') {
    throw "MinIO DELETE 失败: $statusLine"
}
Write-Host "OK: $Object 已删除"
