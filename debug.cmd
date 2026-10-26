@echo off
chcp 65001 >nul
REM Start with JDWP debug port 5005 (attach from IDEA: Remote JVM Debug -> localhost:5005).
set "JAVA_HOME=D:\soft\jdk-17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

mvn -B -Dfile.encoding=UTF-8 spring-boot:run -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
