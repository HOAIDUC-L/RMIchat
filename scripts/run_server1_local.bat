@echo off
chcp 65001 > nul
cd /d "%~dp0\.."

echo === KHỞI CHẠY SERVER 1 (CỔNG 1099) - KẾT NỐI SERVER 2 TẠI 127.0.0.1:1100 ===
java -Djava.rmi.server.hostname=127.0.0.1 -jar target\rmichat-server.jar 1 1099 127.0.0.1 1100 127.0.0.1
pause
