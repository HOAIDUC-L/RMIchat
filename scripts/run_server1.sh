#!/bin/bash
# Cách dùng:
# 1. Chạy tương tác: ./run_server1.sh
# 2. Hoặc truyền tham số: ./run_server1.sh <IP_CUA_SERVER_2> [IP_CUA_MAY_NAY]
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
    echo "Khởi chạy Server 1 (Cổng 1099), RMI Hostname: $LOCAL_IP, Server 2 tại: $PARTNER_IP:1100"
    java -Djava.rmi.server.hostname=$LOCAL_IP -jar target/rmichat-server.jar 1 1099 $PARTNER_IP 1100 $LOCAL_IP
fi
