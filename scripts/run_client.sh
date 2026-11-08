#!/bin/bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

# Khởi chạy giao diện Swing Client
java -jar target/rmichat-client.jar
