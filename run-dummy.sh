#!/bin/bash
# Start FireCapture with its built-in dummy camera (no hardware needed) for testing the plugin.
# start.sh does not forward arguments, so this runs the same java command with "developmentMode=dummy".
FC=${FC_HOME:-/opt/FireCapture_v2.7}
cd "$FC" || exit 1
heap=$(sed -n 's/^heapsize=//p' ~/FireCapture/FireCapture.ini); heap=${heap:-1024}
cp=$(find lib -name "*.jar" -exec printf :{} ';')
exec env DISPLAY=${DISPLAY:-:0} ./jre/bin/FireCapture -Xms${heap}m -Xmx${heap}m -XX:+UseCompressedOops \
    -classpath "$cp" de.wonderplanets.firecapture.gui.FireCapture developmentMode=dummy "$@"
