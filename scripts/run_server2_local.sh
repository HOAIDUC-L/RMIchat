#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

echo "=== KHỞI CHẠY SERVER 2 (CỔNG 1100) - KẾT NỐI SERVER 1 TẠI 127.0.0.1:1099 ==="
java -Djava.rmi.server.hostname=127.0.0.1 -jar target/rmichat-server.jar 2 1100 127.0.0.1 1099 127.0.0.1
