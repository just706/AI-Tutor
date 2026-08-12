@echo off
setlocal

set "BASE_DIR=%~dp0"
set "WRAPPER_DIR=%BASE_DIR%.mvn\wrapper"
set "MAVEN_VERSION=3.9.9"
set "MAVEN_HOME=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_BIN=%MAVEN_HOME%\bin\mvn.cmd"
set "DISTRIBUTION_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "ARCHIVE_FILE=%WRAPPER_DIR%\apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_BIN%" (
    echo Downloading Apache Maven %MAVEN_VERSION%...
    if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%DISTRIBUTION_URL%' -OutFile '%ARCHIVE_FILE%'; Expand-Archive -LiteralPath '%ARCHIVE_FILE%' -DestinationPath '%WRAPPER_DIR%' -Force"
    if errorlevel 1 exit /b 1
)

if not exist "%MAVEN_BIN%" (
    echo Maven executable was not found after download: %MAVEN_BIN%
    exit /b 1
)

if not defined JAVA_HOME (
    for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)
if not defined JAVA_HOME (
    for /d %%J in ("C:\Program Files\Java\jdk-*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)

if not defined MAVEN_OPTS (
    set "MAVEN_OPTS=-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k"
)

set "AI_TUTOR_MAVEN_HOME=%BASE_DIR%.m2"
if not defined AI_TUTOR_MAVEN_REPO (
    set "AI_TUTOR_MAVEN_REPO=%AI_TUTOR_MAVEN_HOME%\repository"
)
if not exist "%AI_TUTOR_MAVEN_HOME%" mkdir "%AI_TUTOR_MAVEN_HOME%"
if not exist "%AI_TUTOR_MAVEN_REPO%" mkdir "%AI_TUTOR_MAVEN_REPO%"

call "%MAVEN_BIN%" "-Dmaven.repo.local=%AI_TUTOR_MAVEN_REPO%" %*
