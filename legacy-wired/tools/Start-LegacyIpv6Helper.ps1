#requires -Version 7.0
# SPDX-License-Identifier: GPL-3.0-only
[CmdletBinding()]
param(
    [string]$AdbPath = 'adb',
    [string]$Serial,
    [ValidateRange(1, 240)][int]$MaxMinutes = 30
)
$ErrorActionPreference = 'Stop'
$package = 'com.shihab.diplay.legacy'
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }

# Avoid a local cmd.exe or PowerShell quoting pass. ADB's remote shell gets
# only fixed commands and validated numeric fields below.
function New-AdbProcess([string[]]$AdbArguments) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $AdbPath
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in $AdbArguments) { $info.ArgumentList.Add($arg) }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    [void]$process.Start()
    return $process
}
function Invoke-Adb([string[]]$AdbArguments, [string]$InputText = '') {
    $process = New-AdbProcess ($deviceArgs + $AdbArguments)
    try {
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if ($InputText) { $process.StandardInput.Write($InputText) }
        $process.StandardInput.Close()
        if (!$process.WaitForExit(20000)) { $process.Kill(); throw 'ADB command timed out.' }
        return @{ Code = $process.ExitCode; Text = $output.Result.Trim(); Error = $errors.Result.Trim() }
    } finally { $process.Dispose() }
}
$state = Invoke-Adb @('get-state')
if ($state.Code -ne 0 -or $state.Text -ne 'device') {
    throw 'Connect the head unit with ADB and authorize this computer. Use -Serial if more than one device is attached.'
}
$api = Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')
if ($api.Text -ne '19') { throw 'This helper is scoped to Android API 19.' }
$identity = Invoke-Adb @('shell', 'run-as', $package, '/system/bin/id')
$uidMatch = [regex]::Match($identity.Text, '^uid=(\d{5,10})(?:\(|\s)')
if ($identity.Code -ne 0 -or !$uidMatch.Success -or [long]$uidMatch.Groups[1].Value -lt 10000 -or [long]$uidMatch.Groups[1].Value -gt 2147483647) {
    throw 'Cannot run-as DiPlay. Install the wired legacy debug APK first; release APKs are unsupported.'
}
$appUid = $uidMatch.Groups[1].Value
$rootStyle = $null
foreach ($style in @('positional', 'command')) {
    $probeArgs = if ($style -eq 'positional') { @('shell', 'su', '0', '/system/bin/id') }
        else { @('shell', 'su', '-c', "'/system/bin/id'") }
    $root = Invoke-Adb $probeArgs
    if ($root.Code -eq 0 -and $root.Text -match '^uid=0(?:\(|\s)') { $rootStyle = $style; break }
}
if (!$rootStyle) { throw 'The head unit did not grant ADB shell Root. This helper cannot change that authorization.' }
$script = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'adb-root-ipv6.sh')).Replace(([string][char]13 + [char]10), [string][char]10)
$install = Invoke-Adb @('shell', 'run-as', $package, '/system/bin/sh', '-c', "'cat > files/adb-root-ipv6.sh'") $script
if ($install.Code -ne 0) { throw 'Cannot write helper into the debug app private directory.' }
$remoteScript = "/data/data/$package/files/adb-root-ipv6.sh"
$seconds = $MaxMinutes * 60
$launchArgs = if ($rootStyle -eq 'positional') { @('shell', '-T', 'su', '0', '/system/bin/sh', $remoteScript, $appUid, "$seconds") }
    else { @('shell', '-T', 'su', '-c', "'/system/bin/sh $remoteScript $appUid $seconds'") }
$helper = New-AdbProcess ($deviceArgs + $launchArgs)
try {
    Write-Host "Temporary helper: UID=$appUid, maximum $MaxMinutes minutes. Keep this window and ADB connected."
    Write-Host 'In DiPlay choose Network compatibility > ADB temporary helper, then run the network self-test.'
    Write-Host 'Press Ctrl+C to stop. Only a scoped lease is changed; no Root authorization or global firewall settings are modified.'
    $errorTask = $helper.StandardError.ReadToEndAsync()
    while ($true) {
        $line = $helper.StandardOutput.ReadLineAsync()
        while (!$line.Wait(200)) { }
        if ($null -eq $line.Result) { break }
        Write-Host $line.Result
    }
    $helper.WaitForExit()
    if ($helper.ExitCode -ne 0) { throw "Helper exited with code $($helper.ExitCode). Check its diagnostic marker above." }
} finally {
    # EOF trips the remote shell trap. Give exact rule deletion time to finish.
    $helper.StandardInput.Close()
    if (!$helper.WaitForExit(5000)) { $helper.Kill() }
    $helper.Dispose()
}
