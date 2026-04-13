#!/bin/bash

# Quick Start Guide for Air Drums
# Run this script for an interactive setup walkthrough

set -e

echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         🥁  KINECT V2 AIR DRUMS - QUICK START  🥁              ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""

# Step 1: Check Kinect
echo "Step 1/5: Checking Kinect V2 connection..."
echo ""

if [ ! -f "$HOME/freenect2/bin/Protonect" ]; then
	echo "❌ ERROR: libfreenect2 not found"
	echo "   Please install libfreenect2 first (see README.md Step 2)"
	exit 1
fi

echo "✅ libfreenect2 installed"
echo ""
echo "Testing Kinect connection... (will open Protonect for 5 seconds)"
echo "You should see live camera feeds. Press Ctrl+C if you see errors."
echo ""

timeout 5 "$HOME/freenect2/bin/Protonect" || true

echo ""
echo "Did Protonect show live camera feeds? (y/n)"
read -r kinect_ok

if [ "$kinect_ok" != "y" ]; then
	echo "❌ Kinect not working. Please fix hardware setup first."
	exit 1
fi

echo "✅ Kinect V2 working"
echo ""

# Step 2: Check MIDI
echo "Step 2/5: Checking MIDI setup..."
echo ""
echo "Opening Audio MIDI Setup..."
echo ""
open "/Applications/Utilities/Audio MIDI Setup.app"
echo ""
echo "Please enable IAC Driver:"
echo "  1. Window → Show MIDI Studio (or press Cmd+2)"
echo "  2. Double-click 'IAC Driver' icon"
echo "  3. Check 'Device is online'"
echo "  4. Click 'Apply'"
echo ""
echo "Press ENTER when done..."
read -r

echo "✅ MIDI setup complete"
echo ""

# Step 3: Build project
echo "Step 3/5: Building Air Drums..."
echo ""

cd "$(dirname "$0")"
PROJECT_ROOT="$(cd .. && pwd)"

if [ ! -f "$PROJECT_ROOT/kinect-jni/target/libkinect-jni.dylib" ]; then
	echo "Native library not found. Running full build..."
	cd "$PROJECT_ROOT"
	mvn clean install -DskipTests
fi

cd "$PROJECT_ROOT/kinect-openrndr"
mvn -q compile

echo "✅ Build complete"
echo ""

# Step 4: Open DAW
echo "Step 4/5: Setting up DAW..."
echo ""
echo "Which DAW will you use?"
echo "  1) GarageBand (free, included with macOS)"
echo "  2) Ableton Live"
echo "  3) Logic Pro"
echo "  4) Other / Skip"
echo ""
read -r -p "Choice (1-4): " daw_choice

case $daw_choice in
1)
	echo ""
	echo "Opening GarageBand..."
	open -a "GarageBand"
	echo ""
	echo "In GarageBand:"
	echo "  1. Create new project → Empty Project"
	echo "  2. Add 'Software Instrument' track"
	echo "  3. Choose a drum kit (e.g., 'SoCal' or 'Brooklyn')"
	echo "  4. Click smart controls (B key)"
	echo "  5. Set MIDI Input to 'Bus 1'"
	echo "  6. Ensure track is record-enabled (red button)"
	echo ""
	;;
2)
	echo ""
	echo "Ableton Live setup:"
	echo "  1. Preferences → Link MIDI"
	echo "  2. Enable 'Track' input for 'IAC Driver (Bus 1)'"
	echo "  3. Create MIDI track, add Drum Rack"
	echo "  4. Set input to 'IAC Driver (Bus 1)'"
	echo "  5. Arm track for recording"
	echo ""
	;;
3)
	echo ""
	echo "Logic Pro setup:"
	echo "  1. File → Project Settings → MIDI → Inputs"
	echo "  2. Enable 'IAC Driver (Bus 1)'"
	echo "  3. Create software instrument with drum kit"
	echo "  4. Record-enable the track"
	echo ""
	;;
*)
	echo ""
	echo "Setup your DAW to receive MIDI from 'IAC Driver Bus 1'"
	echo ""
	;;
esac

echo "Press ENTER when DAW is ready..."
read -r

echo "✅ DAW ready"
echo ""

# Step 5: Launch Air Drums
echo "Step 5/5: Launching Air Drums!"
echo ""
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    CONTROLS REMINDER                           ║"
echo "║                                                                ║"
echo "║  1 / 2     : Switch drum kit presets                          ║"
echo "║  Space     : Toggle depth view                                ║"
echo "║  D         : Toggle debug info                                ║"
echo "║  M         : List MIDI devices                                ║"
echo "║  R         : Reset hit detector                               ║"
echo "║  ESC       : Quit                                             ║"
echo "║                                                                ║"
echo "║  Stand 1.5m (5 feet) from Kinect                              ║"
echo "║  Strike quickly into drum zones (colored circles)             ║"
echo "║  Faster hits = louder sound!                                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo ""
echo "Starting in 3 seconds..."
sleep 3

cd "$PROJECT_ROOT/kinect-openrndr"
./run-airdrums.sh
