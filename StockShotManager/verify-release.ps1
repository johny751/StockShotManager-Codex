param(
    [Parameter(Mandatory = $true)][string]$BuildTools,
    [string]$ExpectedCertificateSha256 = '76a877f2ce63ba6b5891f7c34e2c37b54426c37cd0c8ea21354d7b33f29e1820'
)

$ErrorActionPreference = 'Stop'
$ssmAapt = Join-Path $BuildTools 'aapt.exe'
$ssmSigner = Join-Path $BuildTools 'apksigner.bat'
$ssmGradle = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'app/build.gradle'))
$ssmVersionCode = [regex]::Match($ssmGradle, 'versionCode\s+(\d+)').Groups[1].Value
$ssmVersionName = [regex]::Match($ssmGradle, 'versionName\s+"([^"]+)"').Groups[1].Value
$ssmFlavors = @('alpha', 'beta', 'gamma', 'delta')
$ssmPackages = @('com.stockshot.manager', 'com.stockshot.manager.b', 'com.stockshot.manager.c', 'com.stockshot.manager.d')
$ssmCertificate = $null

try {
    $ssmExpectedCertificate = $ExpectedCertificateSha256.Replace(':', '').Trim().ToLowerInvariant()
    if ($ssmExpectedCertificate -notmatch '^[0-9a-f]{64}$') { throw 'Expected signing certificate SHA-256 must contain 64 hex digits.' }
    $ssmRepository = Split-Path -Parent $PSScriptRoot
    $ssmCommit = (& git -c "safe.directory=$ssmRepository" -C $PSScriptRoot rev-parse --short HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $ssmCommit -notmatch '^[0-9a-f]+$') { throw 'Cannot read the committed Git revision.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (-not $ssmVersionCode -or -not $ssmVersionName) { throw 'Cannot read release version from app/build.gradle.' }
    foreach ($ssmTool in @($ssmAapt, $ssmSigner)) {
        if (-not (Test-Path -LiteralPath $ssmTool -PathType Leaf)) { throw "Tool missing: $ssmTool" }
    }
    for ($ssmIndex = 0; $ssmIndex -lt $ssmFlavors.Count; $ssmIndex++) {
        $ssmFlavor = $ssmFlavors[$ssmIndex]
        $ssmEdition = [char](65 + $ssmIndex)
        $ssmApk = Join-Path $PSScriptRoot "app/build/outputs/apk/$ssmFlavor/release/app-$ssmFlavor-release.apk"
        if (-not (Test-Path -LiteralPath $ssmApk -PathType Leaf)) { throw "APK missing: $ssmApk" }
        $ssmBadging = (& $ssmAapt dump badging $ssmApk | Out-String)
        if ($LASTEXITCODE -ne 0) { throw "Edition $ssmEdition manifest inspection failed." }
        $ssmIdentity = [regex]::Match($ssmBadging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
        if (-not $ssmIdentity.Success -or
            $ssmIdentity.Groups[1].Value -ne $ssmPackages[$ssmIndex] -or
            $ssmIdentity.Groups[2].Value -ne $ssmVersionCode -or
            $ssmIdentity.Groups[3].Value -ne $ssmVersionName) {
            throw "Edition $ssmEdition package or version does not match the release configuration."
        }
        $ssmSignature = (& $ssmSigner verify --verbose --print-certs $ssmApk | Out-String)
        if ($LASTEXITCODE -ne 0) { throw "Edition $ssmEdition signature verification failed." }
        $ssmDigest = [regex]::Match($ssmSignature, 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value
        if (-not $ssmDigest) { throw "Edition $ssmEdition signing certificate missing." }
        if ($ssmDigest.ToLowerInvariant() -ne $ssmExpectedCertificate) { throw "Edition $ssmEdition does not use the expected original signing certificate." }
        if ($null -eq $ssmCertificate) { $ssmCertificate = $ssmDigest }
        elseif ($ssmDigest -ne $ssmCertificate) { throw "Edition $ssmEdition uses a different signing certificate." }
        # BuildConfig constants live in DEX. Check the APK itself for the current revision.
        $ssmArchive = [IO.Compression.ZipFile]::OpenRead($ssmApk)
        $ssmEmbeddedCommit = $false
        try {
            foreach ($ssmDex in $ssmArchive.Entries | Where-Object { $_.FullName -match '^classes[0-9]*\.dex$' }) {
                $ssmDexStream = $ssmDex.Open()
                $ssmMemory = New-Object IO.MemoryStream
                try {
                    $ssmDexStream.CopyTo($ssmMemory)
                    $ssmDexText = [Text.Encoding]::UTF8.GetString($ssmMemory.ToArray())
                    if ($ssmDexText.Contains(([string][char]$ssmCommit.Length) + $ssmCommit + ([string][char]0))) {
                        $ssmEmbeddedCommit = $true
                    }
                } finally {
                    $ssmDexStream.Dispose()
                    $ssmMemory.Dispose()
                }
            }
        } finally {
            $ssmArchive.Dispose()
        }
        if (-not $ssmEmbeddedCommit) { throw "Edition $ssmEdition does not embed current Git commit $ssmCommit." }
        Write-Output "Embedded Git commit: $ssmCommit"
        Write-Output "Edition $ssmEdition verified: $($ssmPackages[$ssmIndex]), version $ssmVersionName ($ssmVersionCode)"
        $ssmHasher = [Security.Cryptography.SHA256]::Create()
        $ssmStream = $null
        try {
            $ssmStream = [IO.File]::OpenRead($ssmApk)
            $ssmHash = [BitConverter]::ToString($ssmHasher.ComputeHash($ssmStream)).Replace('-', '')
        } finally {
            if ($null -ne $ssmStream) { $ssmStream.Dispose() }
            $ssmHasher.Dispose()
        }
        Write-Output "APK SHA-256: $ssmHash"
    }
    Write-Output "Common signing certificate SHA-256: $ssmCertificate"
    exit 0
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
