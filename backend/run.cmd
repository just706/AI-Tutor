@echo off
setlocal

set "BASE_DIR=%~dp0"
cd /d "%BASE_DIR%"

if not defined JAVA_HOME (
    for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)
if not defined JAVA_HOME (
    for /d %%J in ("C:\Program Files\Java\jdk-*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)
if not defined JAVA_HOME (
    echo JAVA_HOME is not set and no JDK was found under common install paths.
    exit /b 1
)

call "%BASE_DIR%mvnw.cmd" package -DskipTests
if errorlevel 1 exit /b 1

set "APP_JAR=%BASE_DIR%target\ai-tutor-backend-0.0.1-SNAPSHOT.jar"
if not exist "%APP_JAR%" (
    echo Application jar was not found: %APP_JAR%
    exit /b 1
)

set "JAVA_OPTS=-Xms32m -Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Xss512k"
call "%JAVA_HOME%\bin\java.exe" %JAVA_OPTS% -jar "%APP_JAR%" %*
