#!/usr/bin/env bash
# Compila y ejecuta el servidor. Uso: ./run.sh [puerto] [webroot] [imagen.h2k|imagen.zip]
set -e
cd "$(dirname "$0")"
./compile.sh
PORT="${1:-8080}"
WEBROOT="${2:-public}"
IMAGE="${3:-images/sample.h2k}"
java -cp out com.cc8.server.Main "$PORT" "$WEBROOT" "$IMAGE"
