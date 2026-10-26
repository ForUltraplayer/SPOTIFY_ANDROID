# @file build-hangul.ps1
# @author: ForUltraplayer
# @brief Build and test the Android Hangul fork without requiring a connected phone.
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$TestsOnly,
    [switch]$FullCompatibility
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JDK 17 경로를 지정해 주세요.' }
if (-not @('android-37','android-37.0' | Where-Object { Test-Path -LiteralPath (Join-Path $AndroidSdk ('platforms\' + $_)) }).Count) { throw 'Android SDK 37 경로를 지정해 주세요.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$release = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release.json') -Raw | ConvertFrom-Json
$gradleSource=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'base\app\build.gradle.kts'))
if (-not $gradleSource.Contains(('applicationId = "'+$release.applicationId+'"'))) { throw '앱 식별자가 release.json과 다릅니다.' }
$arguments = @('--no-daemon','--no-configuration-cache',':app:testDebugUnitTest','--tests','com.music.spotui.data.api.HangulReadingEngineTest','--tests','com.music.spotui.data.api.HangulReadingRetryTest','--tests','com.music.spotui.data.api.LyricsPlusResolverTest','--tests','com.music.spotui.data.api.LyricsApiAuditTest','--tests','com.music.spotui.data.api.UpdateCheckerTest')
if ($FullCompatibility) { $arguments += @(':api-spotify:test',':host-android:testDebugUnitTest') }
if (-not $TestsOnly -and -not $env:MYSPOTI_KEYSTORE -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'base\keystore\keystore.properties'))) {
    throw '업데이트용 APK는 기존 release 서명키가 필요합니다. debug 키로 대체하지 않습니다.'
}
if (-not $TestsOnly) { $arguments += ':app:assembleRelease' }
$arguments += '-PappVersionName=' + $release.version
$arguments += '-PappVersionCode=' + $release.versionCode
$arguments += '-PupdateRepo=' + $release.updateRepository
Push-Location (Join-Path $PSScriptRoot 'base')
try {
    & .\gradlew.bat @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Android 빌드 또는 검사에 실패했습니다.' }
} finally { Pop-Location }
if (-not $TestsOnly) {
    $output = Join-Path $PSScriptRoot 'dist'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'base\app\build\outputs\apk\release\app-release.apk') -Destination (Join-Path $output ('Myspoti-Hangul-' + $release.version + '.apk')) -Force
}
