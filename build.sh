#!/bin/bash
# Build RemoteApi.jar against the INSTALLED FireCapture. Its jars are only on the compile classpath, never copied:
# -implicit:none + -sourcepath src stop javac from compiling sources it finds inside Plugin-1.1.jar into our output.
set -e
FC=${FC_HOME:-/opt/FireCapture_v2.7}
H=$(dirname "$(readlink -f "$0")"); cd "$H"
rm -rf build && mkdir -p build/classes
# FireCapture's classes are Java 12 (major 56); javac 11 reads them fine but warns per class - filter that noise.
noise='major version 56|compiler be upgraded|^[0-9]+ warnings?$'
if ! out=$(javac --release 11 -Xlint:-options -implicit:none -sourcepath src -d build/classes \
        -cp "$FC/plugins/Plugin-1.1.jar:$FC/lib/Firecapture.jar" src/remoteapi/*.java 2>&1); then
    echo "$out" | grep -vE "$noise"; exit 1
fi
echo "$out" | grep -vE "$noise" || true
jar --create --file build/RemoteApi.jar -C build/classes .
if jar tf build/RemoteApi.jar | grep -q '^de/'; then echo "ERROR: FireCapture classes leaked into the jar"; exit 1; fi
echo "built build/RemoteApi.jar"
