@echo off
rem ---------------------------------------------------------------------------
rem  Builds the Drunken Maksim Sim APK.
rem
rem  Usage:  build.cmd [debug^|release] [--install] [--clean]
rem
rem    debug     (default) APK signed with the local debug key - installable
rem    release   unsigned APK; sign it yourself before installing
rem    --install adb install -r the APK onto the single attached device
rem    --clean   run a clean build
rem
rem  The toolchain is resolved in this order:
rem    1. .\tools\jdk and .\tools\android-sdk  (put there by download-tools.cmd)
rem    2. %JAVA_HOME% / %ANDROID_HOME% (or %ANDROID_SDK_ROOT%)
rem    3. whatever local.properties and the system java already point at
rem
rem  Run download-tools.cmd first if you have none of those.
rem ---------------------------------------------------------------------------
setlocal EnableExtensions

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
pushd "%ROOT%"

set "BUILD_TYPE=debug"
set "DO_INSTALL="
set "DO_CLEAN="

:parse
if "%~1"=="" goto :parsed
if /I "%~1"=="debug"     (set "BUILD_TYPE=debug"   & shift & goto :parse)
if /I "%~1"=="release"   (set "BUILD_TYPE=release" & shift & goto :parse)
if /I "%~1"=="--install" (set "DO_INSTALL=1"       & shift & goto :parse)
if /I "%~1"=="--clean"   (set "DO_CLEAN=1"         & shift & goto :parse)
echo Unknown argument: %~1
echo Usage: %~nx0 [debug^|release] [--install] [--clean]
popd & exit /b 2
:parsed

rem ------------------------------------------------------------------ toolchain
set "JDK="
if exist "%ROOT%\tools\jdk\bin\java.exe" (
    set "JDK=%ROOT%\tools\jdk"
) else if exist "%JAVA_HOME%\bin\java.exe" (
    set "JDK=%JAVA_HOME%"
) else (
    where java >nul 2>&1
    if errorlevel 1 (
        echo No JDK found. Run download-tools.cmd first.
        popd & exit /b 1
    )
    echo No JDK in .\tools and no JAVA_HOME - falling back to the system java.
)

set "SDK="
if exist "%ROOT%\tools\android-sdk\platforms" (
    set "SDK=%ROOT%\tools\android-sdk"
) else if exist "%ANDROID_HOME%\platforms" (
    set "SDK=%ANDROID_HOME%"
) else if exist "%ANDROID_SDK_ROOT%\platforms" (
    set "SDK=%ANDROID_SDK_ROOT%"
) else if not exist "%ROOT%\local.properties" (
    echo No Android SDK found and no local.properties. Run download-tools.cmd first.
    popd & exit /b 1
)

rem local.properties is machine-local and git-ignored; keep it in step with
rem whichever SDK we just resolved.
setlocal EnableDelayedExpansion
if defined SDK (
    set "SDK_FWD=!SDK:\=/!"
    > "%ROOT%\local.properties" echo sdk.dir=!SDK_FWD!
)
endlocal

set "GRADLE_ARGS="
if defined JDK (
    set "JAVA_HOME=%JDK%"
    set "GRADLE_ARGS=-Dorg.gradle.java.home=%JDK%"
)

rem ---------------------------------------------------------------------- build
if /I "%BUILD_TYPE%"=="release" (
    set "TASK=assembleRelease"
    set "APK=app\build\outputs\apk\release\app-release-unsigned.apk"
    set "OUT=dist\drunken-maksim-sim-release-unsigned.apk"
) else (
    set "TASK=assembleDebug"
    set "APK=app\build\outputs\apk\debug\app-debug.apk"
    set "OUT=dist\drunken-maksim-sim-debug.apk"
)

if defined DO_CLEAN (
    echo Cleaning...
    call "%ROOT%\gradlew.bat" %GRADLE_ARGS% clean || (popd & exit /b 1)
)

echo Building %BUILD_TYPE%...
call "%ROOT%\gradlew.bat" %GRADLE_ARGS% %TASK% || (popd & exit /b 1)

if not exist "%APK%" (
    echo Expected APK not found at %APK%
    popd & exit /b 1
)

if not exist dist mkdir dist
copy /y "%APK%" "%OUT%" >nul || (popd & exit /b 1)

for %%F in ("%OUT%") do set /a SIZE_KB=%%~zF/1024
echo.
echo APK:  %OUT%  (%SIZE_KB% KB)
if /I "%BUILD_TYPE%"=="release" (
    echo NOTE: this release APK is UNSIGNED - sign it with apksigner before installing.
)

rem -------------------------------------------------------------------- install
if not defined DO_INSTALL goto :done

set "ADB=adb"
if defined SDK if exist "%SDK%\platform-tools\adb.exe" set "ADB=%SDK%\platform-tools\adb.exe"
echo.
echo Installing on the attached device...
"%ADB%" install -r "%OUT%" || (popd & exit /b 1)

:done
popd
exit /b 0
