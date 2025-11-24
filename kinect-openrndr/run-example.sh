#!/bin/bash
# Script to run OPENRNDR Kinect2 examples with proper library paths
#
# Usage:
#   ./run-example.sh                              # Run depth example (default)
#   ./run-example.sh depth                        # Run depth example
#   ./run-example.sh full                         # Run full multi-stream example

cd "$(dirname "$0")"

# Determine which example to run
EXAMPLE="${1:-depth}"

case "$EXAMPLE" in
    depth)
        MAIN_CLASS="org.openrndr.kinect2.examples.Kinect2DepthExampleKt"
        echo "Running Depth Camera Example..."
        ;;
    full)
        MAIN_CLASS="org.openrndr.kinect2.examples.Kinect2ExampleKt"
        echo "Running Full Multi-Stream Example..."
        ;;
    *)
        echo "Unknown example: $EXAMPLE"
        echo "Usage: $0 [depth|full]"
        exit 1
        ;;
esac

# Check if kinect-jni is compiled
if [ ! -f "../kinect-jni/target/libkinect-jni.dylib" ]; then
    echo "Native library not found. Building kinect-jni..."
    (cd .. && mvn compile -pl kinect-jni)
fi

# Check if kinect-openrndr is compiled
if [ ! -d "target/classes" ]; then
    echo "Classes not found. Building kinect-openrndr..."
    mvn compile
fi

# Run with exec:exec goal (spawns new JVM with -XstartOnFirstThread)
mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-XstartOnFirstThread -Djava.library.path=../kinect-jni/target:$HOME/freenect2/lib -classpath %classpath $MAIN_CLASS"

echo ""
echo "Example finished."
