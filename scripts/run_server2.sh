#!/bin/bash
# Cách dùng:
# 1. Chạy tương tác: ./run_server2.sh
# 2. Hoặc truyền tham số: ./run_server2.sh <IP_CUA_SERVER_1> [IP_CUA_MAY_NAY]
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

PARTNER_IP=$1
LOCAL_IP=$2

if [ -z "$PARTNER_IP" ]; then
    java -jar target/rmichat-server.jar
else
    if [ -z "$LOCAL_IP" ]; then
        LOCAL_IP=$(hostname -I | awk '{print $1}')
    fi
    echo "Khởi chạy Server 2 (Cổng 1100), RMI Hostname: $LOCAL_IP, Server 1 tại: $PARTNER_IP:1099"
    java -Djava.rmi.server.hostname=$LOCAL_IP -jar target/rmichat-server.jar 2 1100 $PARTNER_IP 1099 $LOCAL_IP
fi
