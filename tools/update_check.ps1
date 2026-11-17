# Can every version that was ever published still get to the build that is about to be published?
#
# For each old version (taken out of this repository's history: dist/gatekeeper/oj-host.jar and
# dist/main/extras/launch-popup.jar as they were in the commit that published it), in a stand-in folder of its own:
#   Gatekeeper   the old one is started, pointed at a stand-in place that publishes the new build, and told to
#                update. It passes when, within the time allowed, what answers on its port is the new version.
#                That is the old version's own updater fetching, checking, swapping and restarting: the new program
#                only has to start and answer as the old script expects.
#   Launcher     the old one is run with --self-update on a stand-in pack folder whose source is that same place.
#                It passes when the launcher file in that folder is the new build's afterwards.
# Nothing real is touched: every Gatekeeper here runs with OJ_HOST_DIR on its own folder and port, and is stopped
# by the path of that folder at the end. Run it before publishing a change to how updates are published or applied:
#
#     powershell -ExecutionPolicy Bypass -File tools\update_check.ps1                 (a spread of versions)
#     powershell -ExecutionPolicy Bypass -File tools\update_check.ps1 -All            (every version; slower)
#     powershell -ExecutionPolicy Bypass -File tools\update_check.ps1 -Versions 2.2.0,3.5.2
#
# It reads the new build from host\oj-host.jar and source\gatekeeper.json (run tools\build_program.ps1 first).
param([string[]]$Versions, [switch]$All, [string]$Work = (Join-Path $env:TEMP 'gatehouse-update-check'), [int]$Seconds = 120, [string]$Java)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $Java) { $Java = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1 | ForEach-Object { Join-Path $_.FullName 'bin' }) }
if (-not $Java -or -not (Test-Path (Join-Path $Java 'java.exe'))) { throw 'No Java found: pass -Java <its bin folder>.' }
if (-not (Test-Path (Join-Path $Java 'jwebserver.exe'))) { throw 'This needs a JDK of version 18 or newer (for jwebserver).' }
$newJar = Join-Path $repo 'host\oj-host.jar'
$src = Get-Content (Join-Path $repo 'source\gatekeeper.json') -Raw | ConvertFrom-Json
$newVersion = $src.version; $newSha = (Get-FileHash $newJar).Hash.ToLower()

# every version ever published, newest first: version -> commit
$history = [ordered]@{}
foreach ($c in (& git -C $repo log --format=%h -- dist/gatekeeper/gatekeeper.json)) {
    $m = (& git -C $repo show "${c}:dist/gatekeeper/gatekeeper.json") -join '' | ConvertFrom-Json
    if ($m.version -and -not $history.Contains($m.version)) { $history[$m.version] = $c }
}
$known = @($history.Keys | Where-Object { $_ -ne $newVersion })
if ($Versions) { $pick = @($Versions | ForEach-Object { $_ -split ',' } | Where-Object { $_ }) }
elseif ($All) { $pick = $known }
else {   # (the oldest, the newest, and every fourth between: a spread)
    $pick = @(); for ($i = 0; $i -lt $known.Count; $i += 4) { $pick += $known[$i] }
    if ($pick -notcontains $known[-1]) { $pick += $known[-1] }
}
function Mine { @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Work*" -and $_.ProcessId -ne $PID }) }
Mine | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$Work\pub\gatekeeper", "$Work\pub\main\extras" | Out-Null
Copy-Item $newJar "$Work\pub\gatekeeper\oj-host.jar"; Copy-Item $newJar "$Work\pub\main\extras\launch-popup.jar"
@{ version = $newVersion; sha256 = $newSha; size = (Get-Item $newJar).Length; gatehouse = $newVersion; needs = "$($src.needs)" } | ConvertTo-Json | Set-Content -Encoding ascii "$Work\pub\gatekeeper\gatekeeper.json"
@{ version = '0.0.0'; gatehouse = $newVersion; files = @{ 'extras/launch-popup.jar' = @{ hash = $newSha; size = (Get-Item $newJar).Length } } } | ConvertTo-Json -Depth 5 | Set-Content -Encoding ascii "$Work\pub\main\update-sizes.json"
# (the stand-in place that publishes the new build: Java's own small web server, so nothing else is needed)
$web = Start-Process -PassThru -WindowStyle Hidden (Join-Path $Java 'jwebserver.exe') -ArgumentList '-p', '8499', '-b', '127.0.0.1', '-d', "`"$Work\pub`""
$up = $false; for ($i = 0; $i -lt 20 -and -not $up; $i++) { Start-Sleep -Milliseconds 400; try { $up = (Invoke-RestMethod 'http://127.0.0.1:8499/gatekeeper/gatekeeper.json' -TimeoutSec 2).version -eq $newVersion } catch { } }
if (-not $up) { Stop-Process -Id $web.Id -Force -ErrorAction SilentlyContinue; throw 'The stand-in place that publishes the new build did not come up: nothing was checked.' }
"Checking that $($pick.Count) old version(s) can update to $newVersion (of $($known.Count) ever published)"
$results = @(); $port = 25600
try {
    foreach ($v in $pick) {
        $c = $history[$v]; $port++
        if (-not $c) { $results += [pscustomobject]@{ Version = $v; Gatekeeper = 'no such version'; Launcher = '' }; continue }
        $d = "$Work\v$v"; New-Item -ItemType Directory -Force "$d\keeper", "$d\inst\x\minecraft\extras", "$d\run" | Out-Null
        cmd /c "git -C `"$repo`" show ${c}:dist/gatekeeper/oj-host.jar > `"$d\keeper\oj-host.jar`""
        cmd /c "git -C `"$repo`" show ${c}:dist/main/extras/launch-popup.jar > `"$d\run\launcher.jar`" 2>nul"
        # ---- Gatekeeper
        @{ port = $port; name = "Check $v"; require_current = 'false'; update_restart = 'direct'; update_sources = @('http://127.0.0.1:8499/gatekeeper'); tailscale_exe = "$d\none.exe"; servers = @() } | ConvertTo-Json | Set-Content -Encoding ascii "$d\keeper\host.json"
        $env:OJ_HOST_DIR = "$d\keeper"
        Start-Process -WindowStyle Hidden (Join-Path $Java 'javaw.exe') -ArgumentList '-jar', "`"$d\keeper\oj-host.jar`"", '--serve' | Out-Null
        $keeper = 'did not start'; $was = ''
        for ($i = 0; $i -lt 30 -and -not $was; $i++) { Start-Sleep -Milliseconds 500; try { $was = (Invoke-RestMethod "http://127.0.0.1:$port/admin/update" -TimeoutSec 2).own } catch { } }
        if ($was) {
            try { $said = Invoke-RestMethod -Method Post "http://127.0.0.1:$port/admin/update" -Body '{}' -ContentType 'application/json' -TimeoutSec 60 } catch { $said = @{ result = 'no answer'; message = $_.Exception.Message } }
            $now = ''; $t0 = Get-Date
            while (((Get-Date) - $t0).TotalSeconds -lt $Seconds -and $now -ne $newVersion) { Start-Sleep 1; try { $now = (Invoke-RestMethod "http://127.0.0.1:$port/admin/update" -TimeoutSec 2).own } catch { $now = '' } }
            $keeper = if ($now -eq $newVersion) { "ok ($([int]((Get-Date) - $t0).TotalSeconds) s)" } else { "FAILED: is '$now' ($($said.result): $("$($said.message)".Substring(0, [Math]::Min(90, "$($said.message)".Length))))" }
        }
        Remove-Item Env:OJ_HOST_DIR
        Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$d\*" -and $_.ProcessId -ne $PID } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        # ---- launcher
        $launcher = 'no launcher file in that version'
        if ((Test-Path "$d\run\launcher.jar") -and (Get-Item "$d\run\launcher.jar").Length -gt 1000) {
            Copy-Item "$d\run\launcher.jar" "$d\inst\x\minecraft\extras\launch-popup.jar"
            'http://127.0.0.1:8499' | Set-Content -Encoding ascii "$d\inst\x\minecraft\pack-sources.txt"
            $env:OJ_HOME_DRY = '1'
            $o = & (Join-Path $Java 'java.exe') -jar "$d\run\launcher.jar" --self-update "$d\inst" 2>&1
            Remove-Item Env:OJ_HOME_DRY
            $launcher = if ((Get-FileHash "$d\inst\x\minecraft\extras\launch-popup.jar").Hash.ToLower() -eq $newSha) { 'ok' } else { "FAILED: $("$o".Substring(0, [Math]::Min(110, "$o".Length)))" }
        }
        $results += [pscustomobject]@{ Version = $v; Gatekeeper = $keeper; Launcher = $launcher }
        "  $v  Gatekeeper: $keeper   Launcher: $launcher"
    }
} finally { Stop-Process -Id $web.Id -Force -ErrorAction SilentlyContinue; Mine | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } }
$bad = @($results | Where-Object { $_.Gatekeeper -notlike 'ok*' -or ($_.Launcher -ne 'ok' -and $_.Launcher -notlike 'no launcher*') })
""
if ($bad.Count) { "$($bad.Count) of $($results.Count) could NOT get to ${newVersion}: $(($bad | ForEach-Object Version) -join ', ')"; exit 1 }
"All $($results.Count) got to $newVersion."
