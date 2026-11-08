#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

echo "=== KHỞI CHẠY SERVER 1 (CỔNG 1099) - KẾT NỐI SERVER 2 TẠI 127.0.0.1:1100 ==="
java -Djava.rmi.server.hostname=127.0.0.1 -jar target/rmichat-server.jar 1 1099 127.0.0.1 1100 127.0.0.1
