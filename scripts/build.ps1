param([switch]$SkipAndroid, [switch]$SkipPackage, [switch]$TestOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$jdkRoot = if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME } else { Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source) }
function Invoke-Jdk([string]$Tool, [string[]]$ToolArgs) {
    & "$jdkRoot\bin\$Tool.exe" @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "$Tool завершился с кодом $LASTEXITCODE" }
}
$buildRoot = Join-Path $projectRoot 'build'
$distRoot = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path "$buildRoot\classes", "$buildRoot\test-classes", "$buildRoot\jar", $distRoot | Out-Null
$sources = @(Get-ChildItem -Path core\src,shared\src,windows\src -Recurse -Filter *.java | ForEach-Object FullName)
Invoke-Jdk 'javac' (@('-encoding','UTF-8','--release','17','-d',"$buildRoot\classes") + $sources)
$tests = @(Get-ChildItem -Path tests -Filter *.java | ForEach-Object FullName)
Invoke-Jdk 'javac' (@('-encoding','UTF-8','--release','17','-cp',"$buildRoot\classes",'-d',"$buildRoot\test-classes") + $tests)
Invoke-Jdk 'java' @('-ea','-cp',"$buildRoot\classes;$buildRoot\test-classes",'ru.app4dad.tests.AllTests')
if ($TestOnly) { return }
Invoke-Jdk 'jar' @('--create','--file',"$buildRoot\jar\App4Dad.jar",'--main-class','ru.app4dad.windows.Main','-C',"$buildRoot\classes",'.')
Copy-Item -LiteralPath "$buildRoot\jar\App4Dad.jar" -Destination "$distRoot\App4Dad.jar"
if (!$SkipPackage) {
    $packagePath = Join-Path $distRoot 'App4Dad'
    if (Test-Path -LiteralPath $packagePath) {
        $resolvedPackage = (Resolve-Path -LiteralPath $packagePath).Path
        if ($resolvedPackage -ne (Join-Path $projectRoot 'dist\App4Dad')) { throw 'Некорректный путь сборки' }
        Remove-Item -LiteralPath $resolvedPackage -Recurse -Force
    }
    Invoke-Jdk 'jpackage' @('--type','app-image','--name','App4Dad','--app-version','1.0.0','--vendor','App4Dad','--input',"$buildRoot\jar",'--main-jar','App4Dad.jar','--main-class','ru.app4dad.windows.Main','--add-modules','java.desktop,jdk.httpserver,java.logging','--dest',$distRoot,'--java-options','-Dfile.encoding=UTF-8')
    Copy-Item -LiteralPath "$projectRoot\README.md" -Destination "$packagePath\Инструкция.md"
    Compress-Archive -Path $packagePath -DestinationPath "$distRoot\App4Dad-Windows-x64.zip" -Force
}
if (!$SkipAndroid) { & "$PSScriptRoot\build-android.ps1" -JdkRoot $jdkRoot }
Get-ChildItem -LiteralPath $distRoot -File | Select-Object Name,Length
