#!/bin/bash

# Run Air Drums application
# Launches Java directly (not via mvn exec:exec) so Ctrl+C cleanly
# terminates the JVM and OPENRNDR window.

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

# Build
echo "Building kinect-openrndr module..."
cd "$SCRIPT_DIR"
mvn -q compile
echo "Build complete"
echo ""

# Resolve classpath via Maven
# Use dependency:build-classpath (reliable) instead of exec:exec echo %classpath (broken)
echo "Resolving classpath..."
DEP_CP=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null)

if [ -z "$DEP_CP" ]; then
	echo "ERROR: Could not resolve dependency classpath"
	exit 1
fi

# Prepend compiled classes directory
CLASSPATH="$SCRIPT_DIR/target/classes:$DEP_CP"

# Verify classes directory exists
if [ ! -d "$SCRIPT_DIR/target/classes" ]; then
	echo "ERROR: target/classes not found. Build may have failed."
	exit 1
fi

# Run application directly (not via mvn exec:exec)
# This ensures Ctrl+C sends SIGINT directly to the JVM, which triggers
# the shutdown hook and cleanly exits OPENRNDR + Python subprocess.
echo "Starting Air Drums..."
echo ""
echo "Controls:"
echo "  1/2: Load Standard/Minimal drum kit"
echo "  Space: Toggle depth/color view"
echo "  D: Toggle debug info"
echo "  T: Toggle MediaPipe/depth-only tracking"
echo "  R: Reset hit detector"
echo "  M: List MIDI devices"
echo "  Arrows: Calibrate offset  0: Reset calibration"
echo "  ESC: Quit"
echo ""

exec java \
	-XstartOnFirstThread \
	-Djava.library.path="$PROJECT_ROOT/kinect-jni/target:$HOME/freenect2/lib" \
	-classpath "$CLASSPATH" \
	org.openrndr.kinect2.airdrums.AirDrumsAppKt
