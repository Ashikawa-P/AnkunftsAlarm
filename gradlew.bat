@rem Gradle wrapper startup script for Windows
@echo off
setlocal
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME goto findJavaFromJavaHome
set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% EQU 0 goto execute
echo Java was not found. Set JAVA_HOME or install the JDK bundled with Android Studio.
exit /b 1

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
echo JAVA_HOME points to an invalid directory: %JAVA_HOME%
exit /b 1

:execute
"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal

