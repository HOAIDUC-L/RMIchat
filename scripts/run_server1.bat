@echo off
chcp 65001 > nul
cd /d "%~dp0\.."

set PARTNER_IP=%1
set LOCAL_IP=%2

if "%PARTNER_IP%"=="" (
    java -jar target\rmichat-server.jar
) else (
    echo Khởi chạy Server 1 (Cổng 1099), Server 2 tại %PARTNER_IP%:1100
    java -jar target\rmichat-server.jar 1 1099 %PARTNER_IP% 1100 %LOCAL_IP%
)
pause
