#!/bin/bash
#
# Setup Python virtual environment for Air Drums hand tracking.
# Creates a venv in kinect-openrndr/scripts/.venv and installs dependencies.
#
# Usage: ./setup_python.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"

echo "=== Air Drums Python Setup ==="

# Check Python version
if ! command -v python3 &>/dev/null; then
	echo "ERROR: python3 not found. Please install Python 3.9+."
	exit 1
fi

PY_VERSION=$(python3 -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
echo "Python version: $PY_VERSION"

# Check minimum version (3.9 required for mediapipe on Apple Silicon)
PY_MAJOR=$(echo "$PY_VERSION" | cut -d. -f1)
PY_MINOR=$(echo "$PY_VERSION" | cut -d. -f2)
if [ "$PY_MAJOR" -lt 3 ] || ([ "$PY_MAJOR" -eq 3 ] && [ "$PY_MINOR" -lt 9 ]); then
	echo "ERROR: Python 3.9+ required (found $PY_VERSION)"
	exit 1
fi

# Create virtual environment
if [ -d "$VENV_DIR" ]; then
	echo "Virtual environment already exists at $VENV_DIR"
	echo "To recreate, delete it first: rm -rf $VENV_DIR"
else
	echo "Creating virtual environment at $VENV_DIR..."
	python3 -m venv "$VENV_DIR"
	echo "Virtual environment created."
fi

# Activate and install dependencies
echo ""
echo "Installing dependencies..."
"$VENV_DIR/bin/pip" install --upgrade pip -q
"$VENV_DIR/bin/pip" install -r "$SCRIPT_DIR/requirements.txt" -q

# Download MediaPipe hand landmarker model if not present
MODEL_FILE="$SCRIPT_DIR/hand_landmarker.task"
MODEL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

if [ -f "$MODEL_FILE" ]; then
	echo ""
	echo "Hand landmarker model already exists ($(du -h "$MODEL_FILE" | cut -f1) )"
else
	echo ""
	echo "Downloading MediaPipe hand landmarker model..."
	if curl -L -o "$MODEL_FILE" "$MODEL_URL" 2>/dev/null; then
		echo "Model downloaded ($(du -h "$MODEL_FILE" | cut -f1) )"
	else
		echo "WARNING: Failed to download model. Hand tracking will not work."
		echo "Download manually from: $MODEL_URL"
		echo "Save to: $MODEL_FILE"
	fi
fi

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Installed packages:"
"$VENV_DIR/bin/pip" list --format=columns | grep -E "mediapipe|numpy"
echo ""
echo "The Air Drums app will automatically use this venv."
echo "To test manually: $VENV_DIR/bin/python $SCRIPT_DIR/hand_tracker_service.py"
