@echo off
rem Runs the Gradle wrapper with the local toolchain (JDK 17 + Android SDK) unless already configured.
rem Usage: scripts\gradle.cmd :core:test   |   scripts\gradle.cmd :app:assembleDebug
setlocal
if "%JAVA_HOME%"=="" if exist "%USERPROFILE%\Android\jdk\jdk-17.0.21.1+2" set "JAVA_HOME=%USERPROFILE%\Android\jdk\jdk-17.0.21.1+2"
if "%ANDROID_HOME%"=="" if exist "%USERPROFILE%\Android\Sdk" set "ANDROID_HOME=%USERPROFILE%\Android\Sdk"
call "%~dp0..\gradlew.bat" %*
exit /b %ERRORLEVEL%
