param([switch]$PolicyOnly)
$ErrorActionPreference = 'Stop'
# Pairing code is read by adb itself, never passed in a process argument or saved here.
function Read-Endpoint([string]$Prompt) {
    $value = (Read-Host $Prompt).Trim()
    if ($value -notmatch '^[a-zA-Z0-9][a-zA-Z0-9.:-]*:[0-9]{1,5}$') { throw 'Enter the address and port shown on the watch, such as 192.168.1.20:37123.' }
    $port = [int]($value.Substring($value.LastIndexOf(':') + 1))
    if ($port -lt 1 -or $port -gt 65535) { throw 'The port must be 1 to 65535.' }
    return $value
}
function Invoke-Adb([string[]]$Arguments) {
    & $script:adbPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'The watch did not complete that step. Check the address, watch prompt, and that the network allows devices to reach each other. Run this wizard again to retry.' }
}
if ($PolicyOnly) { return }
try {
    Write-Host "InkSheets watch - install or update`n" -ForegroundColor Cyan
    Write-Host 'Use the same private Wi-Fi/hotspot on the watch and computer for setup.'
    $apkPath = Join-Path $PSScriptRoot 'InkSheets-Watch.apk'
    if (!(Test-Path -LiteralPath $apkPath -PathType Leaf)) { throw 'The matching watch APK is missing. Download the complete watch ZIP again.' }
    $candidates = @()
    $known = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($known) { $candidates += $known.Source }
    foreach ($sdkRoot in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
        if ($sdkRoot) { $candidates += Join-Path $sdkRoot 'platform-tools\adb.exe' }
    }
    $toolRoot = Join-Path $env:LOCALAPPDATA 'InkSheets\watch-tools'
    $candidates += Join-Path $toolRoot 'platform-tools\adb.exe'
    $script:adbPath = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (!$script:adbPath) {
        Write-Host 'Android platform-tools are needed. Official license and download: https://developer.android.com/tools/releases/platform-tools'
        if ((Read-Host 'Download from Google and accept the linked Android SDK terms? [y/N]') -notmatch '^[yY]$') { throw 'Nothing installed. Run again after installing Android platform-tools.' }
        New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
        $archive = Join-Path $toolRoot 'google-platform-tools.zip'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $archive
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($archive)
        try {
            $targetRoot = [IO.Path]::GetFullPath($toolRoot) + [IO.Path]::DirectorySeparatorChar
            foreach ($entry in $zip.Entries) {
                $target = [IO.Path]::GetFullPath((Join-Path $toolRoot $entry.FullName))
                if (!$target.StartsWith($targetRoot, [StringComparison]::OrdinalIgnoreCase) -or $entry.Length -gt 100MB) { throw 'The platform-tools archive contains an unsafe path or oversized file.' }
            }
        } finally { $zip.Dispose() }
        Expand-Archive -LiteralPath $archive -DestinationPath $toolRoot -Force
        $script:adbPath = Join-Path $toolRoot 'platform-tools\adb.exe'
        if (!(Test-Path -LiteralPath $script:adbPath)) { throw 'Google platform-tools did not contain adb.exe.' }
        $signature = Get-AuthenticodeSignature -LiteralPath $script:adbPath
        if ($signature.Status -notin @('Valid','NotSigned')) { throw 'adb.exe has an invalid digital signature. Installation stopped.' }
    }
    Write-Host "`nOn the watch: Developer options > ADB debugging > Wireless debugging."
    Write-Host 'Wear OS 4+: select Pair new device. Older Debug over Wi-Fi: skip pairing.'
    if ((Read-Host 'Pair a new computer? [y/N]') -match '^[yY]$') {
        $pairEndpoint = Read-Endpoint 'Pairing IP:port (from Pair new device)'
        Invoke-Adb -Arguments @('pair', $pairEndpoint)
    }
    $watchEndpoint = Read-Endpoint 'Connection IP:port (main Wireless debugging screen, NOT pairing port)'
    Invoke-Adb -Arguments @('connect', $watchEndpoint)
    Invoke-Adb -Arguments @('-s', $watchEndpoint, 'get-state')
    Write-Host 'Installing this watch release; your existing app data and calibrations are kept.'
    Invoke-Adb -Arguments @('-s', $watchEndpoint, 'install', '-r', $apkPath)
    Write-Host "`nInstalled. Open InkSheets on the watch once, then enable Watch on your paired Android phone." -ForegroundColor Green
    Write-Host 'Use the phone app from this same release. Turn off Wireless debugging when finished.'
} catch { Write-Host $_.Exception.Message -ForegroundColor Red; exit 1 }
