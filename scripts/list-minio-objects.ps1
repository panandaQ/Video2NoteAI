# 一次性工具：列出 MinIO media bucket 中的对象（SigV4 GET list-type=2）。
# 用法: pwsh -File scripts\list-minio-objects.ps1 [-Prefix 'video-import/']
param(
    [string]$Prefix = '',
    [int]$MaxKeys = 1000
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

$now = [DateTime]::UtcNow
$amzDate = $now.ToString('yyyyMMddTHHmmssZ')
$dateStamp = $now.ToString('yyyyMMdd')
$payloadHash = 'UNSIGNED-PAYLOAD'
$uri = "/$bucket"

# 规范化查询串：按 key 排序并 URI 编码
$queryParts = [System.Collections.Generic.List[string]]::new()
if ($Prefix -ne '') { $queryParts.Add([Uri]::EscapeDataString('prefix') + '=' + [Uri]::EscapeDataString($Prefix)) }
$queryParts.Add([Uri]::EscapeDataString('list-type') + '=' + [Uri]::EscapeDataString('2'))
$queryParts.Add([Uri]::EscapeDataString('max-keys') + '=' + [Uri]::EscapeDataString([string]$MaxKeys))
$queryParts.Sort()
$canonicalQuery = ($queryParts -join '&')
$rawQuery = $canonicalQuery

$canonicalRequest = "GET`n$uri`n$canonicalQuery`nhost:$endpoint`nx-amz-content-sha256:$payloadHash`nx-amz-date:$amzDate`n`nhost;x-amz-content-sha256;x-amz-date`n$payloadHash"
$stringToSign = "AWS4-HMAC-SHA256`n$amzDate`n$dateStamp/$region/$service/aws4_request`n" + (Sha256Hex $canonicalRequest)
$kDate = HmacSha256 ([System.Text.Encoding]::UTF8.GetBytes("AWS4$secretKey")) $dateStamp
$kRegion = HmacSha256 $kDate $region
$kService = HmacSha256 $kRegion $service
$kSigning = HmacSha256 $kService 'aws4_request'
$signature = Hex (HmacSha256 $kSigning $stringToSign)

$authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$dateStamp/$region/$service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=$signature"

& curl.exe -s "http://$endpoint$uri`?$rawQuery" `
    -H "Host: $endpoint" `
    -H "x-amz-date: $amzDate" `
    -H "x-amz-content-sha256: $payloadHash" `
    -H "Authorization: $authorization"
