#!/bin/bash

# Run Air Drums application
# This script configures the correct library paths and JVM arguments

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Resolve Java and Maven via SDKMAN using versions from .sdkmanrc
SDKMAN_DIR="$HOME/.sdkman"

if [ -f "$PROJECT_ROOT/.sdkmanrc" ] && [ -d "$SDKMAN_DIR/candidates" ]; then
	JAVA_VER=$(grep '^java=' "$PROJECT_ROOT/.sdkmanrc" | cut -d= -f2)
	MVN_VER=$(grep '^maven=' "$PROJECT_ROOT/.sdkmanrc" | cut -d= -f2)

	JAVA_DIR="$SDKMAN_DIR/candidates/java/$JAVA_VER"
	MVN_DIR="$SDKMAN_DIR/candidates/maven/$MVN_VER"

	if [ -d "$JAVA_DIR" ] && [ -d "$MVN_DIR" ]; then
		export JAVA_HOME="$JAVA_DIR"
		export PATH="$JAVA_HOME/bin:$MVN_DIR/bin:$PATH"
	fi
fi

# Verify tools
if ! command -v mvn &>/dev/null; then
	echo "ERROR: Maven (mvn) not found in PATH"
	echo "Please install Maven or configure SDKMAN"
	exit 1
fi

if ! command -v java &>/dev/null; then
	echo "ERROR: Java not found in PATH"
	exit 1
fi

echo "=== Air Drums - Kinect V2 ==="
echo "Java: $(java -version 2>&1 | head -1)"
echo ""

# Check if native library exists
if [ ! -f "$PROJECT_ROOT/kinect-jni/target/libkinect-jni.dylib" ]; then
	echo "ERROR: Native library not found at kinect-jni/target/libkinect-jni.dylib"
	echo "Please run 'mvn clean install' from project root first"
	exit 1
fi

# Check if libfreenect2 exists
if [ ! -f "$HOME/freenect2/lib/libfreenect2.dylib" ]; then
	echo "ERROR: libfreenect2 not found at ~/freenect2/lib/libfreenect2.dylib"
	echo "Please install libfreenect2 (see README.md Step 2)"
	exit 1
fi

# Build if needed
echo "Building kinect-openrndr module..."
cd "$SCRIPT_DIR"
mvn -q compile
echo "Build complete"
echo ""

# Run application
echo "Starting Air Drums..."
echo ""
echo "Controls:"
echo "  1: Load Standard drum kit (6 pieces)"
echo "  2: Load Minimal drum kit (4 pieces)"
echo "  Space: Toggle depth view"
echo "  D: Toggle debug info"
echo "  M: List MIDI devices"
echo "  R: Reset hit detector"
echo "  ESC: Quit"
echo ""

mvn exec:exec \
	-Dexec.executable="java" \
	-Dexec.args="-XstartOnFirstThread \
        -Djava.library.path=$PROJECT_ROOT/kinect-jni/target:$HOME/freenect2/lib \
        -classpath %classpath \
        org.openrndr.kinect2.airdrums.AirDrumsAppKt"
