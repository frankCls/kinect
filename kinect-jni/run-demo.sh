#!/bin/bash
# Script to run FrameCaptureDemo with proper library paths
#
# This script must be run in a GUI terminal session (not headless SSH)
# because OpenGL requires display context.
#
# Usage:
#   ./run-demo.sh                    # Console only, 10 seconds, 30 frames
#   ./run-demo.sh --gui              # With visual window
#   ./run-demo.sh --duration 30      # Custom duration
#   ./run-demo.sh --gui --duration 20 --frames 50

cd "$(dirname "$0")"

# Check if native library exists
if [ ! -f "target/libkinect-jni.dylib" ]; then
    echo "Native library not found. Building..."
    mvn compile -pl kinect-jni
fi

# Run with proper library path
java -Djava.library.path=target:$HOME/freenect2/lib \
     -cp target/classes \
     com.kinect.jni.FrameCaptureDemo \
     "$@"
