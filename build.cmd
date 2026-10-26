@echo off
chcp 65001 >nul
REM This project requires JDK 17; the terminal default JAVA_HOME points to JDK 8.
REM usage: build.cmd              -> package
REM        build.cmd test         -> run tests
REM        build.cmd clean package
set "JAVA_HOME=D:\soft\jdk-17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if "%~1"=="" (
    mvn -B -Dfile.encoding=UTF-8 package
) else (
    mvn -B -Dfile.encoding=UTF-8 %*
)
