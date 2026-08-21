param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [int]$MaximumMinSdk = 21
)

$ErrorActionPreference = "Stop"

$resolvedApkPath = (Resolve-Path -LiteralPath $ApkPath).Path
$androidSdkPath = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($androidSdkPath) -or
    -not (Test-Path -LiteralPath (Join-Path $androidSdkPath "build-tools"))) {
    $androidSdkPath = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($androidSdkPath) -or
    -not (Test-Path -LiteralPath (Join-Path $androidSdkPath "build-tools"))) {
    $localPropertiesPath = Join-Path $PSScriptRoot "..\local.properties"
    if (Test-Path -LiteralPath $localPropertiesPath) {
        $sdkDirLine = Get-Content -LiteralPath $localPropertiesPath |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkDirLine) {
            $androidSdkPath = ($sdkDirLine -replace '^sdk\.dir=', '')
            $androidSdkPath = $androidSdkPath -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
}
if ([string]::IsNullOrWhiteSpace($androidSdkPath) -or
    -not (Test-Path -LiteralPath (Join-Path $androidSdkPath "build-tools"))) {
    throw "Android SDK path is unavailable"
}

$buildToolsPath = Get-ChildItem -LiteralPath (Join-Path $androidSdkPath "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$aaptPath = Join-Path $buildToolsPath "aapt.exe"
$apksignerPath = Join-Path $buildToolsPath "apksigner.bat"
$zipalignPath = Join-Path $buildToolsPath "zipalign.exe"

$badging = & $aaptPath dump badging $resolvedApkPath
if ($LASTEXITCODE -ne 0) {
    throw "aapt failed to parse APK: $resolvedApkPath"
}

$sdkMatch = [regex]::Match(($badging -join "`n"), "sdkVersion:'(\d+)'")
if (-not $sdkMatch.Success) {
    throw "APK does not declare sdkVersion"
}
$minimumSdk = [int]$sdkMatch.Groups[1].Value
if ($minimumSdk -gt $MaximumMinSdk) {
    throw "APK minSdk is $minimumSdk; expected at most $MaximumMinSdk"
}

$nativeCodeLine = $badging | Where-Object { $_ -like "native-code:*" } | Select-Object -First 1
if ($nativeCodeLine -and $nativeCodeLine -notmatch "'armeabi-v7a'") {
    throw "APK contains native libraries but does not include armeabi-v7a"
}

$signatureOutput = & $apksignerPath verify --verbose $resolvedApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed:`n$($signatureOutput -join "`n")"
}
if (($signatureOutput -join "`n") -notmatch 'Verified using v1 scheme \(JAR signing\): true') {
    throw "APK does not include a v1/JAR signature"
}

& $zipalignPath -c -P 16 4 $resolvedApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK failed zipalign verification"
}

Write-Output "Legacy APK verification passed: minSdk=$minimumSdk, armeabi-v7a=present-or-not-required, v1-signature=true, zipalign=true"
