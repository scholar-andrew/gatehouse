# Gatehouse, for a plain (unmodded) server set up from the launcher's menu: Prism runs this before the game starts.
# There is no pack to update; it only shows the server's launcher window (whether it's on, Request server, the
# whitelist, Server controls) and does what the window says: Play starts the game, Restart shows it again, Quit
# cancels the launch. Anything else (no Java new enough for the launcher, the launcher missing) just starts the game.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$cancel = Join-Path $here 'launch-cancelled'
Remove-Item $cancel -Force -ErrorAction SilentlyContinue
$jar = Join-Path $here 'extras\launch-popup.jar'
if (-not (Test-Path $jar)) { exit 0 }
# A Java 17 or newer for the launcher: the one Prism hands over if it's new enough, else one Prism downloaded.
$java = $null; $best = 0
$found = @($env:INST_JAVA) + @(Get-ChildItem (Join-Path $here '..\..\..\java') -Recurse -Filter javaw.exe -ErrorAction SilentlyContinue | ForEach-Object FullName)
foreach ($c in $found) {
    if (-not $c -or -not (Test-Path $c -PathType Leaf)) { continue }
    $rel = Join-Path (Split-Path -Parent (Split-Path -Parent $c)) 'release'
    if ((Test-Path $rel) -and ((Get-Content $rel -Raw) -match 'JAVA_VERSION="(\d+)')) { $v = [int]$Matches[1]; if ($v -ge 17 -and $v -gt $best) { $java = $c; $best = $v } }
}
if (-not $java) { exit 0 }
$run = Join-Path $here 'launcher-run.jar'
while ($true) {
    # (from a copy: Windows locks a running file, and the menu keeps this launcher up to date)
    try { Copy-Item $jar $run -Force } catch { $run = $jar }
    $p = Start-Process -Wait -PassThru $java -ArgumentList '-jar', "`"$run`"", "`"$here`"", 'ok'
    if ($p.ExitCode -eq 11) { continue }       # Restart
    if ($p.ExitCode -ne 10) { exit 0 }         # Play, or the launcher couldn't run: the game starts
    # Quit: with the launch wrapper set up, leave it a note and end well (Prism sees a launch that ended normally);
    # without it, the only way to stop the launch is to fail this step.
    $icfg = Join-Path (Split-Path $here -Parent) 'instance.cfg'
    if ((Test-Path (Join-Path $here 'launch-wrapper.exe')) -and (Test-Path $icfg) -and (Select-String -LiteralPath $icfg -Pattern '^WrapperCommand=.*launch-wrapper' -Quiet)) {
        Set-Content -Path $cancel -Value 'quit' -Encoding ascii
        exit 0
    }
    exit 1
}
