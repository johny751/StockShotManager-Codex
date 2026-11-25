[CmdletBinding()]
param([string]$PackageRoot)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($PackageRoot)) {
    $PackageRoot = Split-Path -Parent $PSScriptRoot
}
$taskRoot = [IO.Path]::GetFullPath($PackageRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$taskPrefix = $taskRoot + [IO.Path]::DirectorySeparatorChar
$taskSums = Join-Path $taskRoot 'SHA256SUMS.txt'
if (-not [IO.File]::Exists($taskSums)) { throw 'SHA256SUMS.txt was not found. Extract the complete package first.' }
$taskSeen = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$taskCount = 0
foreach ($taskLine in [IO.File]::ReadAllLines($taskSums, [Text.Encoding]::UTF8)) {
    if ([string]::IsNullOrWhiteSpace($taskLine)) { continue }
    $taskMatch = [regex]::Match($taskLine, '^([0-9a-fA-F]{64})  (.+)$')
    if (-not $taskMatch.Success) { throw 'Invalid checksum entry.' }
    $taskRelative = $taskMatch.Groups[2].Value
    if ([IO.Path]::IsPathRooted($taskRelative)) { throw 'Checksum paths must be relative.' }
    $taskPath = [IO.Path]::GetFullPath((Join-Path $taskRoot $taskRelative))
    if (-not $taskPath.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Checksum path escapes the package.' }
    if (-not $taskSeen.Add($taskRelative)) { throw ('Duplicate checksum entry: ' + $taskRelative) }
    if (-not [IO.File]::Exists($taskPath)) { throw ('Missing file: ' + $taskRelative) }
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    $taskStream = [IO.File]::OpenRead($taskPath)
    try { $taskDigest = [BitConverter]::ToString($taskHasher.ComputeHash($taskStream)).Replace('-', '').ToLowerInvariant() }
    finally { $taskStream.Dispose(); $taskHasher.Dispose() }
    if ($taskDigest -ne $taskMatch.Groups[1].Value.ToLowerInvariant()) { throw ('SHA-256 mismatch: ' + $taskRelative) }
    $taskCount += 1
}
if (-not $taskSeen.Contains('PACKAGE_MANIFEST.json')) { throw 'Manifest checksum is missing.' }
$taskManifest = [IO.File]::ReadAllText((Join-Path $taskRoot 'PACKAGE_MANIFEST.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
foreach ($taskItem in $taskManifest.files) {
    if (-not $taskSeen.Contains([string]$taskItem.path)) { throw ('Manifest file has no checksum: ' + $taskItem.path) }
    $taskPath = [IO.Path]::GetFullPath((Join-Path $taskRoot ([string]$taskItem.path)))
    if (-not $taskPath.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Manifest path escapes the package.' }
    if (([IO.FileInfo]$taskPath).Length -ne [long]$taskItem.bytes) { throw ('Size mismatch: ' + $taskItem.path) }
}
$taskExtra = @([IO.Directory]::EnumerateFiles($taskRoot, '*', [IO.SearchOption]::AllDirectories) | Where-Object {
    $taskName = $_.Substring($taskPrefix.Length).Replace('\', '/')
    $taskName -ne 'SHA256SUMS.txt' -and -not $taskSeen.Contains($taskName)
})
Write-Output ('Verified ' + $taskCount + ' original files; every checksum and manifest size matched.')
if ($taskExtra.Count -gt 0) { Write-Output ('Additional files after extraction: ' + $taskExtra.Count) }
Write-Output 'Run this check before Configure-Environment.ps1, which deliberately changes machine-specific paths.'