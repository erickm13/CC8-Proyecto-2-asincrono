#!/usr/bin/env bash
# Compila y ejecuta TODAS las pruebas del proyecto (unitarias, integración y
# end-to-end sobre el servidor real). No requiere internet.
set -e
cd "$(dirname "$0")"
mkdir -p images demo

./compile.sh

run() { echo "▶ $*"; java -cp out "$@"; }

echo "=== Preparar artefactos de prueba ==="
run com.cc8.server.image.SampleGen images/sample.png 1200 800
run com.cc8.server.image.Preprocessor images/sample.png images/sample.h2k

echo
echo "=== Pruebas unitarias e integración (Java) ==="
run com.cc8.server.image.DwtSelfTest
run com.cc8.server.image.BitPlaneSelfTest
run com.cc8.server.image.RoundTripTest
run com.cc8.server.image.PngIngestTest images/sample.png
run com.cc8.server.protocol.HilbertSelfTest
run com.cc8.server.ws.WebSocketSelfTest
run com.cc8.server.protocol.TransportSelfTest
run com.cc8.server.protocol.IntegrationSelfTest
run com.cc8.server.protocol.SchedulerForgetTest images/sample.h2k

echo
echo "=== Pruebas end-to-end sobre el servidor real (WebSocket) ==="
run com.cc8.server.image.DumpPpm images/sample.h2k demo/ref.ppm
java -cp out com.cc8.server.Main 8099 public images/sample.h2k &
SRV=$!
trap "kill $SRV 2>/dev/null || true" EXIT
sleep 2
java -cp out com.cc8.server.protocol.RapidClientTest http://localhost:8099 images/sample.png
if command -v node >/dev/null 2>&1; then
    node test/js_verify.mjs http://localhost:8099 demo/ref.ppm
    node test/forget_verify.mjs http://localhost:8099
else
    echo "(node no disponible: se omiten las pruebas del cliente JS)"
fi
kill $SRV 2>/dev/null || true
trap - EXIT

echo
echo "✓ TODAS LAS PRUEBAS PASARON"
