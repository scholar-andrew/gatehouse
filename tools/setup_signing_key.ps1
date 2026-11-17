# Sets up signing of the published program on GitHub. Run it once, yourself, in a PowerShell window in this
# repository's folder:
#
#     powershell -ExecutionPolicy Bypass -File tools\setup_signing_key.ps1
#
# What it does, in order (it stops at the first thing that fails, and says so):
#   1. Asks GitHub's command-line tool for leave to add a workflow (a browser page opens: you approve it).
#   2. Makes a new Ed25519 key pair with the OpenSSL that came with Git for Windows.
#   3. Stores the private key as this repository's secret RELEASE_SIGNING_KEY. GitHub never shows a secret again,
#      to anyone; only a workflow in this repository can use it.
#   4. Overwrites and deletes the private key file here. It is on this PC only for the seconds between 2 and 4,
#      in a folder only you can read. It is not printed, and nothing else is sent anywhere.
#   5. Writes the public key to source\release-key.pub.pem and the workflow to .github\workflows\sign-release.yml,
#      commits those two files and pushes them.
#
# After that, GitHub signs each published program by itself (tools\sign-release.workflow.yml says how). Nothing
# requires a signature yet. Running this again makes a NEW key, which replaces the old one: only do that on purpose.
param([switch]$Yes)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
if (-not (Test-Path $openssl)) { throw "OpenSSL wasn't found at $openssl (it comes with Git for Windows)." }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "GitHub's command-line tool (gh) wasn't found." }
if ((Test-Path 'source\release-key.pub.pem') -and -not $Yes) { throw "A release key is already set up (source\release-key.pub.pem). To replace it with a new one on purpose, run this again with -Yes." }
if (-not $Yes) {
    Write-Host "This makes a signing key, stores it in this repository's secrets on GitHub, and adds the signing workflow."
    if ((Read-Host "Type yes to go ahead") -ne 'yes') { Write-Host 'Nothing was done.'; exit 0 }
}
Write-Host '1. Asking GitHub for leave to add a workflow (approve it in the browser page that opens)...'
& gh auth refresh -h github.com -s workflow
if ($LASTEXITCODE) { throw 'GitHub did not give that leave. Nothing was changed.' }
$tmp = Join-Path $env:LOCALAPPDATA ("gatehouse-key-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
$key = Join-Path $tmp 'key.pem'
try {
    Write-Host '2. Making the key pair...'
    & $openssl genpkey -algorithm ed25519 -out $key
    if ($LASTEXITCODE -or -not (Test-Path $key)) { throw 'OpenSSL could not make the key.' }
    & $openssl pkey -in $key -pubout -out (Join-Path $repo 'source\release-key.pub.pem')
    if ($LASTEXITCODE) { throw 'OpenSSL could not write the public key.' }
    Write-Host "3. Storing the private key in this repository's secrets on GitHub..."
    Get-Content -Raw $key | & gh secret set RELEASE_SIGNING_KEY
    if ($LASTEXITCODE) { throw 'GitHub did not take the secret.' }
} finally {
    Write-Host '4. Removing the private key from this PC...'
    if (Test-Path $key) { $n = (Get-Item $key).Length; [IO.File]::WriteAllBytes($key, (New-Object byte[] $n)); Remove-Item -Force $key }
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
Write-Host '5. Adding the public key and the workflow to the repository...'
New-Item -ItemType Directory -Force '.github\workflows' | Out-Null
Copy-Item 'tools\sign-release.workflow.yml' '.github\workflows\sign-release.yml' -Force
& git add 'source/release-key.pub.pem' '.github/workflows/sign-release.yml'
& git commit -m 'Release signing: the public key, and the workflow that signs each published program'
if ($LASTEXITCODE) { throw 'Nothing was committed (was there anything to commit?).' }
& git push origin main
if ($LASTEXITCODE) { throw 'The push did not go through. The key is stored; push these two files when you can.' }
Write-Host ''
Write-Host 'Done. From the next publish on, GitHub signs the program and adds the signature to the "signatures" release.'
Write-Host 'To sign the one that is published now: on GitHub, Actions, "Sign the published program", Run workflow.'
