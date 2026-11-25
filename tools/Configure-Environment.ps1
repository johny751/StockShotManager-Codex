<#
.SYNOPSIS
Configure only this StockShotManager copy for the receiving Windows computer.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$SdkHome,
    [string]$ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'StockShotManager'
}

function Resolve-Directory([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label directory does not exist: $Path"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).ProviderPath
    if ($resolved -match '[%!\r\n"]') {
        throw "$Label path cannot contain %, !, a quote, or a newline because build-apk.bat uses batch expansion. Spaces are supported."
    }
    return $resolved.TrimEnd([IO.Path]::DirectorySeparatorChar)
}

function Require-File([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label is missing: $Path"
    }
}

function Escape-PropertyValue([string]$Value) {
    return $Value.Replace('\', '\\').Replace(':', '\:')
}

function Replace-UniqueLine([string]$Text, [string]$Pattern, [string]$Value, [string]$Label) {
    $expression = [regex]::new($Pattern)
    $matches = $expression.Matches($Text)
    if ($matches.Count -ne 1) {
        throw "$Label must have exactly one matching configuration line; found $($matches.Count). No files have been changed."
    }
    $replacementValue = $Value
    $evaluator = [System.Text.RegularExpressions.MatchEvaluator]{
        param($match)
        return $match.Groups['indent'].Value + $replacementValue
    }.GetNewClosure()
    return $expression.Replace($Text, $evaluator, 1)
}

$ssmProject = Resolve-Directory $ProjectRoot 'Project'
$ssmJdk = Resolve-Directory $JdkHome 'JDK'
$ssmSdk = Resolve-Directory $SdkHome 'Android SDK'
$ssmJava = Join-Path $ssmJdk 'bin\java.exe'
Require-File $ssmJava 'JDK java.exe'
Require-File (Join-Path $ssmJdk 'bin\javac.exe') 'JDK javac.exe'
Require-File (Join-Path $ssmSdk 'platforms\android-34\android.jar') 'Android API 34 platform'
Require-File (Join-Path $ssmSdk 'build-tools\34.0.0\aapt.exe') 'Build Tools 34.0.0 aapt.exe'
Require-File (Join-Path $ssmSdk 'build-tools\34.0.0\apksigner.bat') 'Build Tools 34.0.0 apksigner.bat'

$ssmJavaStart = New-Object Diagnostics.ProcessStartInfo
$ssmJavaStart.FileName = $ssmJava
$ssmJavaStart.Arguments = '-version'
$ssmJavaStart.UseShellExecute = $false
$ssmJavaStart.CreateNoWindow = $true
$ssmJavaStart.RedirectStandardOutput = $true
$ssmJavaStart.RedirectStandardError = $true
$ssmJavaProcess = New-Object Diagnostics.Process
$ssmJavaProcess.StartInfo = $ssmJavaStart
try {
    [void]$ssmJavaProcess.Start()
    $ssmJavaVersion = $ssmJavaProcess.StandardOutput.ReadToEnd() + $ssmJavaProcess.StandardError.ReadToEnd()
    $ssmJavaProcess.WaitForExit()
    if ($ssmJavaProcess.ExitCode -ne 0 -or $ssmJavaVersion -notmatch '(?m)\bversion\s+"17(?:\.|\")') {
        throw 'The selected Java executable is not a working JDK 17. Select a JDK 17 installation.'
    }
} finally {
    $ssmJavaProcess.Dispose()
}

$ssmGradlePath = Join-Path $ssmProject 'gradle.properties'
$ssmLocalPath = Join-Path $ssmProject 'local.properties'
$ssmBuilderPath = Join-Path $ssmProject 'build-apk.bat'
Require-File $ssmGradlePath 'gradle.properties'
Require-File $ssmBuilderPath 'build-apk.bat'
Require-File (Join-Path $ssmProject 'app\build.gradle') 'StockShotManager app/build.gradle'
Require-File (Join-Path $ssmProject 'gradlew.bat') 'Gradle Windows wrapper'

$ssmGradleText = [IO.File]::ReadAllText($ssmGradlePath)
$ssmBuilderText = [IO.File]::ReadAllText($ssmBuilderPath)
$ssmGradleNew = Replace-UniqueLine $ssmGradleText '(?m)^(?<indent>[ \t]*)org\.gradle\.java\.home[ \t]*=[^\r\n]*' ('org.gradle.java.home=' + (Escape-PropertyValue $ssmJdk)) 'gradle.properties org.gradle.java.home'
$ssmBuilderNew = Replace-UniqueLine $ssmBuilderText '(?im)^(?<indent>[ \t]*)set[ \t]+"JAVA_HOME=[^"\r\n]*"[ \t]*(?=\r?$)' ('set "JAVA_HOME=' + $ssmJdk + '"') 'build-apk.bat JAVA_HOME'
$ssmBuilderNew = Replace-UniqueLine $ssmBuilderNew '(?im)^(?<indent>[ \t]*)set[ \t]+"SDK_DIR=[^"\r\n]*"[ \t]*(?=\r?$)' ('set "SDK_DIR=' + $ssmSdk + '"') 'build-apk.bat SDK_DIR'

$ssmLocalExists = Test-Path -LiteralPath $ssmLocalPath -PathType Leaf
$ssmLocalText = $null
if ($ssmLocalExists) {
    $ssmLocalText = [IO.File]::ReadAllText($ssmLocalPath)
    $ssmLocalNew = Replace-UniqueLine $ssmLocalText '(?m)^(?<indent>[ \t]*)sdk\.dir[ \t]*=[^\r\n]*' ('sdk.dir=' + (Escape-PropertyValue $ssmSdk)) 'local.properties sdk.dir'
} else {
    $ssmLocalNew = 'sdk.dir=' + (Escape-PropertyValue $ssmSdk) + [Environment]::NewLine
}

$ssmChanges = @(
    [pscustomobject]@{ Name = 'gradle.properties'; Path = $ssmGradlePath; Existed = $true; Text = $ssmGradleNew },
    [pscustomobject]@{ Name = 'local.properties'; Path = $ssmLocalPath; Existed = $ssmLocalExists; Text = $ssmLocalNew },
    [pscustomobject]@{ Name = 'build-apk.bat'; Path = $ssmBuilderPath; Existed = $true; Text = $ssmBuilderNew }
)

if (-not $PSCmdlet.ShouldProcess($ssmProject, 'Back up and configure JDK 17 / Android SDK 34 paths')) {
    [pscustomobject]@{ ProjectRoot = $ssmProject; JdkHome = $ssmJdk; SdkHome = $ssmSdk; Changed = $false; BackupDirectory = $null }
    return
}

$ssmBackupId = (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + [Guid]::NewGuid().ToString('N')
$ssmBackupRoot = Join-Path $ssmProject ('build\migration-config-backup\' + $ssmBackupId)
[void][IO.Directory]::CreateDirectory($ssmBackupRoot)
$ssmOriginals = @{}
foreach ($ssmChange in $ssmChanges) {
    if ($ssmChange.Existed) {
        $ssmOriginals[$ssmChange.Name] = [IO.File]::ReadAllBytes($ssmChange.Path)
        [IO.File]::WriteAllBytes((Join-Path $ssmBackupRoot $ssmChange.Name), $ssmOriginals[$ssmChange.Name])
    }
}
$ssmEncoding = New-Object Text.UTF8Encoding($false)
$ssmBackupManifest = @($ssmChanges | Select-Object Name, Existed) | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $ssmBackupRoot 'backup-manifest.json'), $ssmBackupManifest, $ssmEncoding)

$ssmWritten = @()
try {
    foreach ($ssmChange in $ssmChanges) {
        $ssmWritten += $ssmChange
        [IO.File]::WriteAllText($ssmChange.Path, $ssmChange.Text, $ssmEncoding)
    }
} catch {
    $ssmWriteError = $_
    foreach ($ssmChange in $ssmWritten) {
        if ($ssmChange.Existed) {
            [IO.File]::WriteAllBytes($ssmChange.Path, $ssmOriginals[$ssmChange.Name])
        } elseif ([IO.File]::Exists($ssmChange.Path)) {
            [IO.File]::Delete($ssmChange.Path)
        }
    }
    throw "Configuration write failed and original files were restored. Backup: $ssmBackupRoot. $($ssmWriteError.Exception.Message)"
}

[pscustomobject]@{ ProjectRoot = $ssmProject; JdkHome = $ssmJdk; SdkHome = $ssmSdk; Changed = $true; BackupDirectory = $ssmBackupRoot }
