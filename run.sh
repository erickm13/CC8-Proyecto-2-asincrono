#!/usr/bin/env bash
# Compila y ejecuta el servidor. Uso: ./run.sh [puerto] [webroot]
set -e
cd "$(dirname "$0")"
./compile.sh
PORT="${1:-8080}"
WEBROOT="${2:-public}"
java -cp out com.cc8.server.Main "$PORT" "$WEBROOT"
