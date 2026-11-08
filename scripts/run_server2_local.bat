@echo off
chcp 65001 > nul
cd /d "%~dp0\.."

echo === KHỞI CHẠY SERVER 2 (CỔNG 1100) - KẾT NỐI SERVER 1 TẠI 127.0.0.1:1099 ===
java -Djava.rmi.server.hostname=127.0.0.1 -jar target\rmichat-server.jar 2 1100 127.0.0.1 1099 127.0.0.1
pause
