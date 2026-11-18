# Gatehouse's tests, run before each publish (R29). tools/build_pack.py refuses a program file these haven't passed.
#
#   powershell -ExecutionPolicy Bypass -File tools\run_tests.ps1           (all, with the update check at the end)
#   powershell -ExecutionPolicy Bypass -File tools\run_tests.ps1 -Quick    (all but the update check)
#
# What they try, each against stand-ins in a folder of their own (nothing on this machine is touched; nothing real is
# started; a throwaway signing key is made and wiped):
#   1. signatures: a good file passes, a changed one is refused, a missing signature is "unsigned"
#   2. notes and icons: boxes, the what's-new summary, a list opening at its top, the shortcut icon
#   3. a frozen Gatekeeper: one made to lock up the way it did on 2026-11-17 is noticed and restarted by its watchdog
#   4. a busy Gatekeeper: many calls at once for a while, and it still answers, with no freeze noticed
#   4b. its doors: requests from web pages (Origin, Sec-Fetch-*, another host name) are refused, oversized and deeply
#       nested requests do no harm, and Gatekeeper's own window still gets in
#   5. the update check (tools/update_check.ps1): old versions still update themselves to this build
# On success the program file's checksum goes in tools/tests/passed.txt.
param([switch]$Quick)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$jar = Join-Path $repo 'host\oj-host.jar'
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
$work = Join-Path ([IO.Path]::GetTempPath()) ('gatehouse-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work, "$work\c", "$work\key", "$work\served" | Out-Null
$failed = @()
$started = @()
function Result($name, $ok) { if (-not $ok) { $script:failed += $name } }
try {
    & "$jdk\javac.exe" -cp $jar -d "$work\c" (Get-ChildItem (Join-Path $PSScriptRoot 'tests\src') -Recurse -Filter *.java | ForEach-Object FullName)
    if ($LASTEXITCODE) { throw 'the tests did not compile' }

    # 1. signatures, with a throwaway key (it stands in for the release key only on this class path)
    & $openssl genpkey -algorithm ed25519 -out "$work\key\test.pem" 2>$null
    & $openssl pkey -in "$work\key\test.pem" -pubout -out "$work\key\release-key.pub.pem"
    Copy-Item $jar "$work\sample.jar"
    $sha = (Get-FileHash "$work\sample.jar").Hash.ToLower()
    & $openssl pkeyutl -sign -inkey "$work\key\test.pem" -rawin -in "$work\sample.jar" -out "$work\served\$sha.sig"
    $bytes = (Get-Item "$work\key\test.pem").Length; [IO.File]::WriteAllBytes("$work\key\test.pem", (New-Object byte[] $bytes)); Remove-Item "$work\key\test.pem"
    $web = Start-Process -PassThru -WindowStyle Hidden "$jdk\jwebserver.exe" -ArgumentList '-b', '127.0.0.1', '-p', '8497', '-d', "`"$work\served`""
    $started += $web.Id
    Start-Sleep 2
    & "$jdk\java.exe" -cp "$work\key;$jar;$work\c" dev.gatehouse.TestSigned $work $sha 'http://127.0.0.1:8497/'
    Result 'signatures' ($LASTEXITCODE -eq 0)

    # 2. notes and icons
    & "$jdk\java.exe" -cp "$jar;$work\c" dev.gatehouse.TestLook
    Result 'notes and icons' ($LASTEXITCODE -eq 0)

    # 3 and 4: a stand-in Gatekeeper
    function Keeper($port, $extra) {
        $h = "$work\keeper-$port"; New-Item -ItemType Directory -Force $h | Out-Null
        @{ port = $port; bind = '127.0.0.9'; require_current = 'false'; name = 'Test stand-in'; servers = @() } | ConvertTo-Json | Set-Content -Encoding ascii "$h\host.json"
        $env:OJ_HOST_DIR = $h; foreach ($k in $extra.Keys) { Set-Item "Env:$k" $extra[$k] }
        $p = Start-Process -PassThru -WindowStyle Hidden "$jdk\java.exe" -ArgumentList '-jar', "`"$jar`"", '--serve'
        Remove-Item Env:OJ_HOST_DIR; foreach ($k in $extra.Keys) { Remove-Item "Env:$k" }
        return @($p, $h)
    }
    function Alive($port) { try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 "http://127.0.0.1:$port/v1/alive").StatusCode -eq 200 } catch { $false } }
    function Ours { Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like "*$jar*--serve*" } }

    Write-Host 'a frozen Gatekeeper:'
    $k3 = Keeper 25591 @{ OJ_WATCHDOG_MS = '1500'; OJ_WATCHDOG_TEST_FREEZE = '1' }
    $started += $k3[0].Id
    $t0 = Get-Date; while (((Get-Date) - $t0).TotalSeconds -lt 60 -and -not $k3[0].HasExited) { Start-Sleep 1 }
    Start-Sleep 14
    $noticed = (Get-Content "$($k3[1])\host.log" -ErrorAction SilentlyContinue) -match 'watchdog: Gatekeeper froze'
    $ok3 = $k3[0].HasExited -and [bool]$noticed -and (Alive 25591)
    Write-Host ("  " + $(if ($ok3) { 'ok      ' } else { 'FAILED  ' }) + 'noticed, ended and back up answering')
    Result 'a frozen Gatekeeper' $ok3
    Ours | Where-Object { $_.CommandLine -notlike '*25592*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

    Write-Host 'a busy Gatekeeper:'
    $k4 = Keeper 25592 @{ OJ_WATCHDOG_MS = '1000' }
    $started += $k4[0].Id
    Start-Sleep 5
    $jobs = foreach ($n in 1..8) { Start-Job -ArgumentList 25592 -ScriptBlock { param($port) $end = (Get-Date).AddSeconds(20); $c = 0; while ((Get-Date) -lt $end) { foreach ($u in '/admin/update?1', '/admin/program', '/admin/overview', '/v1/alive', '/admin/auto', '/v1/program/gatekeeper.json') { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 "http://127.0.0.1:$port$u" | Out-Null; $c++ } catch {} } }; $c } }
    $calls = ($jobs | Wait-Job | Receive-Job | Measure-Object -Sum).Sum; $jobs | Remove-Job
    $froze = (Get-Content "$($k4[1])\host.log" -ErrorAction SilentlyContinue) -match 'watchdog'
    $ok4 = (Alive 25592) -and -not $froze -and -not $k4[0].HasExited
    Write-Host ("  " + $(if ($ok4) { 'ok      ' } else { 'FAILED  ' }) + "still answering after $calls calls at once, no freeze noticed")
    Result 'a busy Gatekeeper' $ok4

    # (R36: what a web page, or an oversized or malformed request, can't do)
    Write-Host 'its doors:'
    function Code($url, $method, $headers, $body) {
        try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 -Method $method -Uri $url -Headers $headers -Body $body -ContentType 'text/plain'; return [int]$r.StatusCode }
        catch { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return -1 } }
    }
    function CodeWithHost($url, $hostName) {
        $q = [Net.HttpWebRequest]::Create($url); $q.Host = $hostName; $q.Timeout = 15000
        try { $r = $q.GetResponse(); $c = [int]$r.StatusCode; $r.Close(); return $c }
        catch [Net.WebException] { if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode } else { return -1 } }
    }
    $base = 'http://127.0.0.1:25592'
    # (each condition in brackets: in PowerShell a comma binds tighter than -eq)
    $script:ok6 = $true
    function Check($name, [bool]$ok) { Write-Host ("  " + $(if ($ok) { 'ok      ' } else { 'FAILED  ' }) + $name); if (-not $ok) { $script:ok6 = $false } }
    Check 'a web page (Origin) is refused' ((Code "$base/admin/notify" 'POST' @{ Origin = 'https://example.org' } '{"on":true}') -eq 403)
    Check 'a web page (Sec-Fetch) is refused' ((Code "$base/admin/program" 'POST' @{ 'Sec-Fetch-Site' = 'cross-site'; 'Sec-Fetch-Mode' = 'no-cors' } 'x') -eq 403)
    Check 'a web page on the launchers'' door is refused' ((Code "$base/v1/alive" 'GET' @{ Origin = 'null' } $null) -eq 403)
    Check 'another name for this machine (DNS rebinding) is refused' ((CodeWithHost "$base/admin/overview" 'evil.example') -eq 403)
    Check 'Gatekeeper''s own window still gets in' ((Code "$base/admin/notify" 'GET' @{} $null) -eq 200)
    Check 'nothing was changed by the refused ones' (-not ((Invoke-RestMethod "$base/admin/notify").on))
    Check 'an oversized request is refused' ((Code "$base/admin/notify" 'POST' @{} ('{"x":"' + ('a' * 1100000) + '"}')) -ge 400)
    Check 'deeply nested JSON does no harm' (((Code "$base/admin/notify" 'POST' @{} (('[' * 20000) + (']' * 20000))) -gt 0) -and (Alive 25592))
    Result 'its doors' $script:ok6
} finally {
    foreach ($id in $started) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like "*$jar*--serve*" -or $_.CommandLine -like "*$work*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if (-not $Quick -and -not $failed) {
    Write-Host 'the update check:'
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'update_check.ps1') 2>&1 | Out-String
    $ok5 = $out -match 'All \d+ got to'
    Write-Host ("  " + $(if ($ok5) { 'ok      ' } else { 'FAILED  ' }) + (($out -split "`n" | Where-Object { $_ -match 'got to|FAIL|failed' }) -join ' / ').Trim())
    Result 'the update check' $ok5
}
Write-Host ''
if ($failed) { Write-Host "FAILED: $($failed -join ', ')"; exit 1 }
$sum = (Get-FileHash $jar).Hash.ToLower()
if (-not $Quick) { Set-Content -Encoding ascii (Join-Path $PSScriptRoot 'tests\passed.txt') $sum }
Write-Host "All passed$(if ($Quick) { ' (quick: the update check was left out, so nothing is recorded)' } else { '. Recorded: ' + $sum })"
