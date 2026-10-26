# Windows version of theme.sh: push a theme folder (config.json and optionally wallpaper.jpg) to the
# launcher on the TV and restart Home. See themes/README.md for the keys.
#
# Usage: tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
#        tools\theme.ps1 -TvHost 192.168.1.50 -Reset
param(
    [Parameter(Position = 0)][string]$Theme,
    [switch]$Reset,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot
$py = 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
$pkg = 'com.synseria.synema'
$dir = "/sdcard/Android/data/$pkg/files"
# What tools/restart.sh does: the remote-key service off around the force-stop, which would leave it crashed.
$restart = 's=$(settings get secure enabled_accessibility_services); others=$(echo "$s" | tr ":" "\n" | grep -v "^' + $pkg + '/" | paste -sd: -); ' +
    '[ "$others" = "$s" ] || { settings put secure enabled_accessibility_services "${others:-null}"; sleep 2; }; am force-stop ' + $pkg + '; ' +
    '[ "$others" = "$s" ] || settings put secure enabled_accessibility_services "$s"; input keyevent 3'

if ($Reset) {
    & $py -I tv.py ("rm -f $dir/config.json $dir/wallpaper.jpg; " + $restart + "; echo reset")
    exit 0
}
if (-not $Theme) { throw 'usage: theme.ps1 <theme folder> | -Reset' }
$config = Join-Path $Theme 'config.json'
if (-not (Test-Path $config)) { throw "no config.json in $Theme" }
try { Get-Content $config -Raw | ConvertFrom-Json | Out-Null } catch { throw "$config is not valid JSON" }

& $py -I tv.py "mkdir -p $dir" | Out-Null
& $py -I tv.py --push $config "$dir/config.json"
$wall = Join-Path $Theme 'wallpaper.jpg'
if (Test-Path $wall) { & $py -I b64push.py $wall "$dir/wallpaper.jpg" } else { & $py -I tv.py "rm -f $dir/wallpaper.jpg" | Out-Null }
& $py -I tv.py ($restart + "; echo applied")
Write-Host "Theme $(Split-Path $Theme -Leaf) is on the TV. Undo with: tools\theme.ps1 -TvHost $TvHost -Reset"
