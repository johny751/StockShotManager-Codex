@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem StockShotManager - Build, verify and copy all four signed release APKs.
rem Double-click pauses on completion. Options: clean, nopause (any order).
cd /d "%~dp0"
set "SSM_PAUSE=1"
set "GRADLE_TASKS=assembleRelease"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="nopause" (
    set "SSM_PAUSE=0"
    shift
    goto parse_args
)
if /I "%~1"=="clean" (
    set "GRADLE_TASKS=clean assembleRelease"
    shift
    goto parse_args
)
echo [FAIL] Unknown option: %~1
echo Usage: build-apk.bat [clean] [nopause]
goto failed

:args_done
rem Use the repository's JDK 17; PATH may point at an incompatible Java 8.
rem setlocal keeps this change inside the builder and its child processes.
set "JAVA_HOME=D:\Program\Projects\Android\jdk"
set "SDK_DIR=D:\Program\Projects\Android\sdk"
set "BUILD_TOOLS=%SDK_DIR%\build-tools\34.0.0"
set "APKSIGNER=%BUILD_TOOLS%\apksigner.bat"
set "OUT_A=app\build\outputs\apk\alpha\release\app-alpha-release.apk"
set "OUT_B=app\build\outputs\apk\beta\release\app-beta-release.apk"
set "OUT_C=app\build\outputs\apk\gamma\release\app-gamma-release.apk"
set "OUT_D=app\build\outputs\apk\delta\release\app-delta-release.apk"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [FAIL] JDK 17 not found: %JAVA_HOME%
    goto failed
)
if not exist "%APKSIGNER%" (
    echo [FAIL] apksigner not found: %APKSIGNER%
    goto failed
)

echo(
echo ============================================================
echo   StockShotManager - RELEASE APKs A + B + C + D
echo ============================================================
echo(
echo [1/3] Compiling ^(gradlew %GRADLE_TASKS%^) ...
call gradlew.bat %GRADLE_TASKS% --max-workers=2 --console=plain
if errorlevel 1 (
    echo [FAIL] Build failed. Check the Gradle error output above.
    goto failed
)

for %%E in (A B C D) do (
    if not exist "!OUT_%%E!" (
        echo [FAIL] Edition %%E APK not found: !OUT_%%E!
        goto failed
    )
)

echo(
echo [2/3] Verifying package names, versions and all four signatures ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0verify-release.ps1" -BuildTools "%BUILD_TOOLS%"
if errorlevel 1 (
    echo [FAIL] Release verification failed.
    goto failed
)

echo(
echo [3/3] Copying verified APKs to the project root ...
rem All four artifacts use one locale-independent timestamp.
set "STAMP="
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "STAMP=%%i"
if not defined STAMP (
    echo [FAIL] Could not generate an artifact timestamp.
    goto failed
)
for %%E in (A B C D) do (
    copy /Y "!OUT_%%E!" "StockShotManager-%%E-release-%STAMP%.apk" >nul
    if errorlevel 1 (
        echo [FAIL] Could not copy edition %%E timestamped APK.
        goto failed
    )
    copy /Y "!OUT_%%E!" "StockShotManager-%%E-release-latest.apk" >nul
    if errorlevel 1 (
        echo [FAIL] Could not copy edition %%E latest APK.
        goto failed
    )
)

echo(
echo ============================================================
echo   BUILD COMPLETE - all four identities and signatures verified
echo ------------------------------------------------------------
for %%E in (A B C D) do (
    echo   Edition %%E : %CD%\StockShotManager-%%E-release-%STAMP%.apk
    echo   Edition %%E : %CD%\StockShotManager-%%E-release-latest.apk
)
echo ------------------------------------------------------------
echo   A default folder: Downloads\StockScreenshots
echo   B default folder: Downloads\StockScreenshotsB
echo   C default folder: Downloads\StockScreenshotsC
echo   D default folder: Downloads\StockScreenshotsD
echo ============================================================
set "SSM_EXIT_CODE=0"
goto finished

:failed
set "SSM_EXIT_CODE=1"

:finished
echo(
if "%SSM_PAUSE%"=="1" pause
exit /b %SSM_EXIT_CODE%
