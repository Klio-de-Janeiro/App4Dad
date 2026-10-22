param([string]$WixRoot = $env:WIX, [string]$JdkRoot = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JdkRoot) { $JdkRoot = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source) }
if (!$WixRoot -or !(Test-Path -LiteralPath (Join-Path $WixRoot 'candle.exe'))) {
    throw 'Укажите -WixRoot с путём к candle.exe и light.exe из WiX Toolset 3.14'
}
$appImage = Join-Path $projectRoot 'dist\App4Dad'
if (!(Test-Path -LiteralPath (Join-Path $appImage 'App4Dad.exe'))) { throw 'Сначала выполните scripts/build.ps1' }
$previousPath = $env:PATH
try {
    $env:PATH = "$WixRoot;$previousPath"
    & "$JdkRoot\bin\jpackage.exe" --type exe --name App4Dad --app-image $appImage --app-version 1.0.0 --vendor App4Dad --dest (Join-Path $projectRoot 'dist') --win-per-user-install --win-menu --win-shortcut --win-dir-chooser --win-menu-group App4Dad --win-upgrade-uuid 'bafdb3d1-8311-4e71-95a7-8179c02c2c23'
    if ($LASTEXITCODE -ne 0) { throw "jpackage завершился с кодом $LASTEXITCODE" }
} finally { $env:PATH = $previousPath }
Get-Item -LiteralPath (Join-Path $projectRoot 'dist\App4Dad-1.0.0.exe') | Select-Object Name,Length
