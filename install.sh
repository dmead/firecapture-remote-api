#!/bin/bash
# Copy the built plugin into FireCapture's plugin folder. Restart FireCapture to load it.
set -e
FC=${FC_HOME:-/opt/FireCapture_v2.7}
H=$(dirname "$(readlink -f "$0")")
[ -f "$H/build/RemoteApi.jar" ] || "$H/build.sh"
mkdir -p "$FC/plugins/x64/RemoteApi"
cp "$H/build/RemoteApi.jar" "$FC/plugins/x64/RemoteApi/"
echo "installed to $FC/plugins/x64/RemoteApi/ - restart FireCapture"
