@echo off
chcp 65001 > nul
cd /d "%~dp0\.."

java -jar target\rmichat-client.jar
