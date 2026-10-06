#!/usr/bin/env bash
# Compila todas las fuentes Java a la carpeta out/
set -e
cd "$(dirname "$0")"
find src/main/java -name "*.java" > sources.txt
javac -d out @sources.txt
rm -f sources.txt
echo "Compilacion OK -> out/"
