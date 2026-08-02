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
    for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$paths = @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java'); Get-ChildItem -Path $paths -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"`) do set "JAVA_HOME=%%J"
)

call "%MAVEN_BIN%" %*
