#!/bin/bash

echo "==============================================="
echo "Testing Kinect V2 Logging Levels"
echo "==============================================="

cd kinect-openrndr

echo ""
echo "=== Test 1: INFO Level (default, clean output) ==="
echo "KINECT_JNI_LOG_LEVEL=1"
echo ""
export KINECT_JNI_LOG_LEVEL=1
mvn -q exec:exec 2>&1 | grep -E "\[JNI" | head -15

echo ""
echo "=== Test 2: DEBUG Level (frame diagnostics) ==="
echo "KINECT_JNI_LOG_LEVEL=2"
echo ""
export KINECT_JNI_LOG_LEVEL=2
mvn -q exec:exec 2>&1 | grep -E "\[JNI" | head -20

echo ""
echo "=== Test 3: TRACE Level (verbose with pixel data) ==="
echo "KINECT_JNI_LOG_LEVEL=3"
echo ""
export KINECT_JNI_LOG_LEVEL=3
mvn -q exec:exec 2>&1 | grep -E "\[JNI" | head -25

echo ""
echo "==============================================="
echo "Logging tests complete!"
echo "==============================================="
