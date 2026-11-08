@echo off
chcp 65001 > nul
echo === ĐANG BIÊN DỊCH DỰ ÁN RMI CHAT ===
call mvn clean package
echo === BIÊN DỊCH HOÀN TẤT. CÁC FILE JAR ĐÃ NẰM TRONG THƯ MỤC target/ ===
pause
