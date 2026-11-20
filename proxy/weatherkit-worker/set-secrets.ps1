# Uploads the Worker secrets and points the Android build at the proxy. Prints no secret values.
#   .\set-secrets.ps1 -TeamId ABCDE12345 -KeyId XYZ9876543 -ServiceId com.pofuduk.weather `
#       -KeyFile C:\path\AuthKey_XYZ9876543.p8 -ProxyUrl https://pofuduk-weatherkit.<account>.workers.dev
# A new random APP_KEY is generated on every run and written to the Worker and to ..\..\local.properties (git-ignored).
param(
    [Parameter(Mandatory = $true)][string]$TeamId,
    [Parameter(Mandatory = $true)][string]$KeyId,
    [Parameter(Mandatory = $true)][string]$ServiceId,
    [Parameter(Mandatory = $true)][string]$KeyFile,
    [Parameter(Mandatory = $true)][string]$ProxyUrl
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path $KeyFile)) { throw "Key file not found: $KeyFile" }
$pem = (Get-Content $KeyFile -Raw).Trim()
if ($pem -notmatch '-----BEGIN PRIVATE KEY-----') { throw 'The key file does not look like a WeatherKit .p8 key.' }

$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$appKey = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')

function Set-WorkerSecret([string]$Name, [string]$Value) {
    Write-Host "Setting $Name ..."
    $Value | npx --yes wrangler@4 secret put $Name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "wrangler secret put $Name failed" }
}

Set-WorkerSecret 'TEAM_ID' $TeamId.Trim()
Set-WorkerSecret 'KEY_ID' $KeyId.Trim()
Set-WorkerSecret 'SERVICE_ID' $ServiceId.Trim()
Set-WorkerSecret 'PRIVATE_KEY' $pem
Set-WorkerSecret 'APP_KEY' $appKey

$props = Join-Path $PSScriptRoot '..\..\local.properties'
$lines = @()
if (Test-Path $props) { $lines = @(Get-Content $props | Where-Object { $_ -notmatch '^pofuduk\.weatherkit(ProxyUrl|AppKey)=' }) }
$lines += "pofuduk.weatherkitProxyUrl=$($ProxyUrl.Trim().TrimEnd('/'))"
$lines += "pofuduk.weatherkitAppKey=$appKey"
Set-Content -Path $props -Value $lines -Encoding ASCII

Write-Host 'Done: 5 secrets uploaded, local.properties updated (APP_KEY not shown).'
