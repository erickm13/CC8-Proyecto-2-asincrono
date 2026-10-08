#!/usr/bin/env bash
# Compila y ejecuta TODAS las pruebas del proyecto (unitarias, integración y
# end-to-end sobre el servidor real). No requiere internet.
set -e
cd "$(dirname "$0")"
mkdir -p images demo

./compile.sh
javac -cp out -d out test/rapid_integration_test.java
javac -cp out -d out test/PreprocessorParallelTest.java

run() { echo "▶ $*"; java -Djava.awt.headless=true -cp out "$@"; }

echo "=== Preparar artefactos de prueba ==="
run com.cc8.server.image.SampleGen images/sample.png 1200 800
run com.cc8.server.image.Preprocessor images/sample.png images/sample.h2k

echo
echo "=== Pruebas unitarias e integración (Java) ==="
run com.cc8.server.image.DwtSelfTest
run com.cc8.server.image.BitPlaneSelfTest
run com.cc8.server.image.RoundTripTest
run com.cc8.server.image.PreprocessorParallelTest
run com.cc8.server.image.PngIngestTest images/sample.png
run com.cc8.server.protocol.HilbertSelfTest
run com.cc8.server.ws.WebSocketSelfTest
run com.cc8.server.protocol.TransportSelfTest
run com.cc8.server.protocol.FlowControlTest
run com.cc8.server.protocol.IntegrationSelfTest
run com.cc8.server.protocol.SchedulerOrderTest images/sample.h2k
run com.cc8.server.protocol.SchedulerForgetTest images/sample.h2k
run com.cc8.server.protocol.RapidSessionTest
run com.cc8.server.protocol.RapidModesSchedulerTest images/sample.h2k

echo
echo "=== Pruebas end-to-end sobre el servidor real (WebSocket) ==="
run com.cc8.server.image.DumpPpm images/sample.h2k demo/ref.ppm
java -Djava.awt.headless=true -cp out com.cc8.server.Main 8099 public images/sample.h2k &
SRV=$!
trap "kill $SRV 2>/dev/null || true" EXIT
sleep 2
java -Djava.awt.headless=true -cp out com.cc8.server.protocol.RapidClientTest http://localhost:8099 images/sample.png
java -Djava.awt.headless=true -cp out com.cc8.server.protocol.RapidIntegrationTest http://localhost:8099 images/sample.png images/sample.h2k
if command -v node >/dev/null 2>&1; then
    node test/js_verify.mjs http://localhost:8099 demo/ref.ppm
    node test/forget_verify.mjs http://localhost:8099
    node test/client_modes_test.mjs
    node test/viewer_latency_test.mjs
else
    echo "(node no disponible: se omiten las pruebas del cliente JS)"
fi
kill $SRV 2>/dev/null || true
trap - EXIT

if command -v "${VIPS_BIN:-vips}" >/dev/null 2>&1; then
    echo
    echo "=== Pruebas ZIP libvips (tres modos RAPID) ==="
    ./run_vips_tests.sh
else
    echo "(libvips no disponible: ejecuta ./run_vips_tests.sh al instalar libvips-tools)"
fi

echo
echo "✓ TODAS LAS PRUEBAS PASARON"
