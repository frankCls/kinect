#!/bin/bash
# Script to run FrameCaptureDemo with proper library paths
#
# This script must be run in a GUI terminal session (not headless SSH)
# because OpenGL requires display context.
#
# Usage:
#   ./run-demo.sh                          # Console only, OpenGL pipeline (default)
#   ./run-demo.sh --gui                    # With visual window
#   ./run-demo.sh --pipeline CPU           # Use CPU pipeline (safe for frameworks)
#   ./run-demo.sh --pipeline OPENGL        # Use OpenGL pipeline (faster, default)
#   ./run-demo.sh --duration 30            # Custom duration
#   ./run-demo.sh --gui --duration 20 --frames 50

cd "$(dirname "$0")"

# Check if native library exists
if [ ! -f "target/libkinect-jni.dylib" ]; then
    echo "Native library not found. Building..."
    mvn compile -pl kinect-jni
fi

# Check if --gui flag is present
USE_START_ON_FIRST_THREAD=true
for arg in "$@"; do
    if [ "$arg" = "--gui" ]; then
        USE_START_ON_FIRST_THREAD=false
        break
    fi
done

# Run with proper library path
# IMPORTANT: -XstartOnFirstThread is REQUIRED for OpenGL pipeline but breaks Swing GUI
# When --gui is specified, we DON'T use -XstartOnFirstThread (CPU pipeline will be used automatically)
if [ "$USE_START_ON_FIRST_THREAD" = true ]; then
    java -XstartOnFirstThread \
         -Djava.library.path=target:$HOME/freenect2/lib \
         -cp target/classes \
         com.kinect.jni.FrameCaptureDemo \
         "$@"
else
    java -Djava.library.path=target:$HOME/freenect2/lib \
         -cp target/classes \
         com.kinect.jni.FrameCaptureDemo \
         "$@"
fi
