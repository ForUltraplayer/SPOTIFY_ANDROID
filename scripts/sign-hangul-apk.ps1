# @file sign-hangul-apk.ps1
# @author: ForUltraplayer
# @brief Sign the Gradle release APK with the personal release key and confirm it matches the installed app's signer.
param([string]$Apk = (Join-Path $PSScriptRoot '..\base\app\build\outputs\apk\release\app-release.apk'),
      [string]$SigningDirectory = '(signing folder)')
$ErrorActionPreference = 'Stop'
$keystore = Join-Path $SigningDirectory 'hangul-release.p12'
# One line holding the keystore password, kept beside the key and outside the repository.
# Without it apksigner asks for the password as before.
$passwordFile = Join-Path $SigningDirectory 'keystore-password.txt'
# Certificate of every published hangul build; a different signer cannot update the installed app.
$expected = 'b56cf5007389de3d98c4064cf448c37c9c393118bdff9d52d037cf33452034c6'

# Claude Desktop virtualizes %LOCALAPPDATA%\Android into its package folder; accept either location.
$android = @((Join-Path $env:LOCALAPPDATA 'Android'),
             (Join-Path $env:LOCALAPPDATA 'Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\Android')) |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ 'Sdk\build-tools\36.0.0\apksigner.bat') } | Select-Object -First 1
if (-not $android) { throw 'Android build-tools 36.0.0 were not found.' }
$env:JAVA_HOME = Join-Path $android 'jdk-21'
$tools = Join-Path $android 'Sdk\build-tools\36.0.0'

$badging = & (Join-Path $tools 'aapt2.exe') dump badging $Apk | Select-Object -First 1
if ($badging -notmatch "versionName='([^']+)'") { throw 'The APK has no versionName.' }
$version = $Matches[1]
$output = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\dist')) ('Myspoti-Hangul-' + $version + '.apk')
$password = if (Test-Path -LiteralPath $passwordFile) { @('--ks-pass', ('file:' + $passwordFile)) } else { @() }

& (Join-Path $tools 'apksigner.bat') sign --ks $keystore --ks-type PKCS12 @password `
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --out $output $Apk
if ($LASTEXITCODE -ne 0) { throw 'Signing failed.' }
$certificates = & (Join-Path $tools 'apksigner.bat') verify --print-certs $output
if (-not ($certificates -match ('SHA-256 digest: ' + $expected))) {
    Remove-Item -LiteralPath $output
    throw 'The signer differs from the installed app; the APK was removed.'
}
'Signed : ' + $output
'Version: ' + $version
'SHA-256: ' + (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
