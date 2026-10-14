param(
    [string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$Sdk -or !$Jdk) { throw '请设置 ANDROID_HOME（或 ANDROID_SDK_ROOT）与 JAVA_HOME，或传入 -Sdk 和 -Jdk。' }
$env:JAVA_HOME = $Jdk
$source = Join-Path $PSScriptRoot 'fixtures\share-receiver'
$output = Join-Path $PSScriptRoot '..\build\qa-sharing'
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
$platform = Join-Path $Sdk 'platforms\android-36\android.jar'
$buildTools = Join-Path $Sdk 'build-tools\36.1.0'
New-Item -ItemType Directory -Force $output, $classes, $dex | Out-Null
function Assert-Success {
    if ($LASTEXITCODE -ne 0) { throw "分享验证 APK 构建失败：$LASTEXITCODE" }
}
$javaFiles = @(Get-ChildItem -LiteralPath $source -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $Jdk 'bin\javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $platform -d $classes @javaFiles
Assert-Success
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $buildTools 'd8.bat') --lib $platform --min-api 25 --output $dex @classFiles
Assert-Success
$unsigned = Join-Path $output 'unsigned.apk'
& (Join-Path $buildTools 'aapt2.exe') link -I $platform --manifest (Join-Path $source 'AndroidManifest.xml') -o $unsigned
Assert-Success
& (Join-Path $Jdk 'bin\jar.exe') uf $unsigned -C $dex classes.dex
Assert-Success
$key = Join-Path $output 'qa.keystore'
if (!(Test-Path -LiteralPath $key)) {
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias qa -dname 'CN=Aowu Share QA' -keyalg RSA -validity 3650
    Assert-Success
}
$apk = Join-Path $output 'share-receiver.apk'
& (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-pass pass:android --key-pass pass:android --out $apk $unsigned
Assert-Success
Write-Output $apk
