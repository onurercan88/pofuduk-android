# Creates the Google Play upload key outside the repo and writes keystore.properties (git-ignored).
# The password is typed by you (hidden) and never printed. Refuses to overwrite an existing key or properties file.
#   .\scripts\create-upload-key.ps1                      (default: C:\Source\pofuduk-keys\pofuduk-upload.jks)
param([string]$KeyStore = 'C:\Source\pofuduk-keys\pofuduk-upload.jks')
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$props = Join-Path $root 'keystore.properties'
if (Test-Path $KeyStore) { throw "$KeyStore already exists - not overwriting (losing an upload key needs a Play reset)." }
if (Test-Path $props) { throw "$props already exists - not overwriting." }

$keytool = Join-Path $env:USERPROFILE 'Android\jdk\jdk-17.0.21.1+2\bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool not found: $keytool" }

function Read-Plain([string]$Prompt) {
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$pass = Read-Plain 'Upload key password (min 8 characters)'
if ($pass.Length -lt 8) { throw 'Password too short.' }
if ($pass -match '[\\\r\n]') { throw 'Please avoid backslashes in the password (keystore.properties escaping).' }
if ((Read-Plain 'Repeat password') -ne $pass) { throw 'Passwords do not match.' }

New-Item -ItemType Directory -Force -Path (Split-Path $KeyStore -Parent) | Out-Null
$env:POFUDUK_UPLOAD_PASS = $pass
try {
    & $keytool -genkeypair -keystore $KeyStore -storetype PKCS12 -alias pofuduk-upload -keyalg RSA -keysize 4096 `
        -validity 10000 -dname 'CN=Pofuduk, O=Pofuduk, C=TR' `
        -storepass:env POFUDUK_UPLOAD_PASS -keypass:env POFUDUK_UPLOAD_PASS
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }
} finally {
    Remove-Item Env:POFUDUK_UPLOAD_PASS -ErrorAction SilentlyContinue
}

$store = $KeyStore.Replace('\', '/')
Set-Content -Path $props -Encoding ASCII -Value @(
    "storeFile=$store",
    "storePassword=$pass",
    'keyAlias=pofuduk-upload',
    "keyPassword=$pass"
)
Write-Host "Done: $KeyStore created, keystore.properties written (password not shown). Back up the .jks and the password."
