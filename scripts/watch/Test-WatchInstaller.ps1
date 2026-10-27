# Silent policy/command tests. No network, watch, adb server, pairing code, or audio is used.
. "$PSScriptRoot/Install-Watch.ps1" -PolicyOnly
function Read-Host { return $script:answer }
$script:answer = '192.168.4.22:37123'
if ((Read-Endpoint 'test') -ne $script:answer) { throw 'Endpoint not preserved' }
foreach ($bad in @('example:0','example:65536','--help','host:12;evil','host:$(bad)')) {
    $script:answer = $bad; $rejected = $false
    try { Read-Endpoint 'test' | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw "Unsafe endpoint accepted: $bad" }
}
$script:adbPath = { $script:seen = @($args); $global:LASTEXITCODE = 0 }
Invoke-Adb -Arguments @('-s', '192.168.4.22:37123', 'install', '-r', 'C:\folder with spaces\InkSheets-Watch.apk')
if (($script:seen -join '|') -ne '-s|192.168.4.22:37123|install|-r|C:\folder with spaces\InkSheets-Watch.apk') { throw 'ADB arguments were changed or reinterpreted' }
$script:adbPath = { $global:LASTEXITCODE = 1 }
$rejected = $false
try { Invoke-Adb -Arguments @('get-state') } catch { $rejected = $true }
if (!$rejected) { throw 'Failed adb operation was accepted' }
Write-Host 'Watch installer policy tests passed (fake adb only).'
