#!/usr/bin/env bash
# Verifica un ZIP real de libvips con los tres modos RAPID.
set -euo pipefail
cd "$(dirname "$0")"

work=$(mktemp -d)
server_pid=
cleanup() {
    if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
    rm -rf -- "$work"
}
trap cleanup EXIT

./compile.sh
javac -cp out -d out test/ZipPyramidIntegrationTest.java
java -Djava.awt.headless=true -cp out com.cc8.server.image.SampleGen "$work/sample.png" 1200 800
./preprocess_vips.sh "$work/sample.png" "$work/sample.zip"

port=${VIPS_TEST_PORT:-8102}
java -Djava.awt.headless=true -cp out com.cc8.server.Main "$port" public "$work/sample.zip" &
server_pid=$!
sleep 2
java -Djava.awt.headless=true -cp out com.cc8.server.protocol.ZipPyramidIntegrationTest \
    "http://localhost:$port" "$work/sample.png" "$work/sample.zip"
if command -v node >/dev/null 2>&1; then
    node test/zip_client_test.mjs
fi
echo '✓ PRUEBAS ZIP LIBVIPS PASARON'
