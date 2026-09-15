@echo off
rem ---------------------------------------------------------------------------
rem  Downloads every tool needed to build Drunken Maksim Sim into .\tools:
rem
rem    tools\jdk          Eclipse Temurin JDK 17 (Gradle and AGP run on it)
rem    tools\android-sdk  Android SDK: cmdline-tools, platform-tools,
rem                       platform android-35, build-tools 35.0.0
rem
rem  It also accepts the SDK licences, writes local.properties and warms the
rem  Gradle wrapper, so a fresh clone can go straight to build.cmd with
rem  nothing installed system-wide.
rem
rem  Usage:  download-tools.cmd [--force]
rem
rem    --force   re-download components that are already in .\tools
rem
rem  Needs: PowerShell 5+ (ships with Windows 10/11).
rem ---------------------------------------------------------------------------
setlocal EnableExtensions EnableDelayedExpansion

set "JDK_MAJOR=17"
set "CMDLINE_TOOLS_BUILD=13114758"
set "SDK_PLATFORM=platforms;android-35"
set "SDK_BUILD_TOOLS=build-tools;35.0.0"

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "TOOLS=%ROOT%\tools"
set "JDK_DIR=%TOOLS%\jdk"
set "SDK_DIR=%TOOLS%\android-sdk"
set "DL=%TOOLS%\downloads"

set "FORCE="
if /I "%~1"=="--force" set "FORCE=1"
if not "%~1"=="" if not defined FORCE (
    echo Usage: %~nx0 [--force]
    exit /b 2
)

rem ------------------------------------------------------------- host details
set "ARCH=x64"
if /I "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"
echo Host: windows/%ARCH%   target: %TOOLS%

if not exist "%DL%" mkdir "%DL%" || goto :fail

rem ----------------------------------------------------------------------- JDK
if defined FORCE if exist "%JDK_DIR%" rmdir /s /q "%JDK_DIR%"

if exist "%JDK_DIR%\bin\java.exe" (
    echo.
    echo == JDK already present: %JDK_DIR%
    goto :jdk_ready
)

echo.
echo == Downloading Eclipse Temurin JDK %JDK_MAJOR% ^(windows/%ARCH%^)
set "JDK_URL=https://api.adoptium.net/v3/binary/latest/%JDK_MAJOR%/ga/windows/%ARCH%/jdk/hotspot/normal/eclipse"
call :fetch "%JDK_URL%" "%DL%\jdk.zip" || goto :fail

echo.
echo == Unpacking JDK
if exist "%DL%\jdk-stage" rmdir /s /q "%DL%\jdk-stage"
call :unzip "%DL%\jdk.zip" "%DL%\jdk-stage" || goto :fail

rem The archive holds a single versioned top-level directory.
set "JDK_TOP="
for /d %%D in ("%DL%\jdk-stage\*") do if not defined JDK_TOP set "JDK_TOP=%%~fD"
if not defined JDK_TOP (
    echo ERROR: unexpected JDK archive layout.
    goto :fail
)
move "%JDK_TOP%" "%JDK_DIR%" >nul || goto :fail
rmdir /s /q "%DL%\jdk-stage" 2>nul
del /q "%DL%\jdk.zip" 2>nul

:jdk_ready
if not exist "%JDK_DIR%\bin\java.exe" (
    echo ERROR: JDK unpack failed: no java.exe under %JDK_DIR%\bin
    goto :fail
)
"%JDK_DIR%\bin\java.exe" -version
set "JAVA_HOME=%JDK_DIR%"

rem -------------------------------------------------------------- Android SDK
set "SDKMANAGER=%SDK_DIR%\cmdline-tools\latest\bin\sdkmanager.bat"

if defined FORCE if exist "%SDK_DIR%\cmdline-tools" rmdir /s /q "%SDK_DIR%\cmdline-tools"

if exist "%SDKMANAGER%" (
    echo.
    echo == Android command-line tools already present
    goto :ct_ready
)

echo.
echo == Downloading Android command-line tools ^(build %CMDLINE_TOOLS_BUILD%^)
call :fetch "https://dl.google.com/android/repository/commandlinetools-win-%CMDLINE_TOOLS_BUILD%_latest.zip" "%DL%\cmdline-tools.zip" || goto :fail

echo.
echo == Unpacking command-line tools
if exist "%DL%\ct-stage" rmdir /s /q "%DL%\ct-stage"
call :unzip "%DL%\cmdline-tools.zip" "%DL%\ct-stage" || goto :fail
if not exist "%SDK_DIR%\cmdline-tools" mkdir "%SDK_DIR%\cmdline-tools"
if exist "%SDK_DIR%\cmdline-tools\latest" rmdir /s /q "%SDK_DIR%\cmdline-tools\latest"
move "%DL%\ct-stage\cmdline-tools" "%SDK_DIR%\cmdline-tools\latest" >nul || goto :fail
rmdir /s /q "%DL%\ct-stage" 2>nul
del /q "%DL%\cmdline-tools.zip" 2>nul

:ct_ready
echo.
echo == Accepting Android SDK licences
rem sdkmanager asks once per unaccepted licence; feed it a stream of y.
> "%DL%\yes.txt" (for /L %%i in (1,1,64) do @echo y)
call "%SDKMANAGER%" --sdk_root="%SDK_DIR%" --licenses < "%DL%\yes.txt" >nul
del /q "%DL%\yes.txt" 2>nul

echo.
echo == Installing SDK packages
call "%SDKMANAGER%" --sdk_root="%SDK_DIR%" "platform-tools" "%SDK_PLATFORM%" "%SDK_BUILD_TOOLS%" || goto :fail

rem ---------------------------------------------------------- local.properties
echo.
echo == Writing local.properties
set "SDK_FWD=%SDK_DIR:\=/%"
> "%ROOT%\local.properties" echo sdk.dir=%SDK_FWD%
type "%ROOT%\local.properties"

rem ----------------------------------------------------------- Gradle wrapper
echo.
echo == Downloading the Gradle distribution ^(wrapper warm-up^)
pushd "%ROOT%"
call "%ROOT%\gradlew.bat" --version || (popd & goto :fail)
popd

echo.
echo == Done. Build with:  build.cmd
exit /b 0

rem ------------------------------------------------------------- subroutines
rem Both helpers hand their arguments to PowerShell through the environment,
rem so paths with spaces or odd characters survive cmd's quoting rules.

:fetch
set "PS_SRC=%~1"
set "PS_DST=%~2"
echo    %PS_SRC%
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
  "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "Invoke-WebRequest -Uri $env:PS_SRC -OutFile $env:PS_DST -UseBasicParsing"
if errorlevel 1 (
    echo ERROR: download failed: %PS_SRC%
    exit /b 1
)
exit /b 0

:unzip
set "PS_SRC=%~1"
set "PS_DST=%~2"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "Expand-Archive -Force -LiteralPath $env:PS_SRC -DestinationPath $env:PS_DST"
if errorlevel 1 (
    echo ERROR: could not unpack %PS_SRC%
    exit /b 1
)
exit /b 0

:fail
echo.
echo Tool download FAILED.
exit /b 1
