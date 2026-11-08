@echo off
chcp 65001 > nul
cd /d "%~dp0\.."

set PARTNER_IP=%1
set LOCAL_IP=%2

if "%PARTNER_IP%"=="" (
    java -jar target\rmichat-server.jar
) else (
    echo Khởi chạy Server 2 (Cổng 1100), Server 1 tại %PARTNER_IP%:1099
    java -jar target\rmichat-server.jar 2 1100 %PARTNER_IP% 1099 %LOCAL_IP%
)
pause
