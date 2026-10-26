@echo off
chcp 65001 >nul
REM Start the app with JDK 17 on port 8080.
set "JAVA_HOME=D:\soft\jdk-17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

mvn -B -Dfile.encoding=UTF-8 spring-boot:run
