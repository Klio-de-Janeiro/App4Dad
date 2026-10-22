param([string]$JdkRoot = $env:JAVA_HOME, [string]$SdkRoot = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (!$JdkRoot) { $JdkRoot = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source) }
if (!$SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$toolsRoot = Join-Path $SdkRoot 'build-tools\35.0.0'
if (!(Test-Path -LiteralPath $androidJar)) { throw 'Установите Android SDK Platform 35 и Build Tools 35.0.0' }
$env:JAVA_HOME = $JdkRoot
$buildRoot = Join-Path $projectRoot 'build\android'
$distRoot = Join-Path $projectRoot 'dist'
$localRoot = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force -Path "$buildRoot\classes","$buildRoot\dex",$distRoot,$localRoot | Out-Null
function Checked([string]$Command,[string[]]$ToolArgs) {
    & $Command @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "$Command завершился с кодом $LASTEXITCODE" }
}
Checked "$toolsRoot\aapt2.exe" @('compile','--dir',"$projectRoot\android\res",'-o',"$buildRoot\resources.zip")
Checked "$toolsRoot\aapt2.exe" @('link','-o',"$buildRoot\unsigned.apk",'-I',$androidJar,'--manifest',"$projectRoot\android\AndroidManifest.xml",'--java',"$buildRoot\generated",'--auto-add-overlay',"$buildRoot\resources.zip")
$sources = @(Get-ChildItem -Path android\src,shared\src -Recurse -Filter *.java | ForEach-Object FullName)
Checked "$JdkRoot\bin\javac.exe" (@('-encoding','UTF-8','--release','8','-classpath',$androidJar,'-d',"$buildRoot\classes") + $sources)
Checked "$JdkRoot\bin\jar.exe" @('--create','--file',"$buildRoot\classes.jar",'-C',"$buildRoot\classes",'.')
Checked "$toolsRoot\d8.bat" @('--lib',$androidJar,'--min-api','26','--output',"$buildRoot\dex", "$buildRoot\classes.jar")
Copy-Item -LiteralPath "$buildRoot\unsigned.apk" -Destination "$buildRoot\with-dex.apk" -Force
Checked "$JdkRoot\bin\jar.exe" @('--update','--file',"$buildRoot\with-dex.apk",'-C',"$buildRoot\dex",'classes.dex')
Checked "$toolsRoot\zipalign.exe" @('-f','4',"$buildRoot\with-dex.apk", "$buildRoot\aligned.apk")
# This development signing key stays local and must be retained to install updates.
$keystorePath = Join-Path $localRoot 'android-dev.p12'
if (!(Test-Path -LiteralPath $keystorePath)) {
    Checked "$JdkRoot\bin\keytool.exe" @('-genkeypair','-keystore',$keystorePath,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','app4dad','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=App4Dad Local Development')
}
Checked "$toolsRoot\apksigner.bat" @('sign','--ks',$keystorePath,'--ks-key-alias','app4dad','--ks-pass','pass:android','--key-pass','pass:android','--out',"$distRoot\App4Dad-Android.apk", "$buildRoot\aligned.apk")
Checked "$toolsRoot\apksigner.bat" @('verify','--verbose',"$distRoot\App4Dad-Android.apk")
Write-Output "APK: $distRoot\App4Dad-Android.apk"
