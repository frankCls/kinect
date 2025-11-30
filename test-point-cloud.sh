#!/bin/bash
set -e

echo "=== Step 1: Building kinect-jni ==="
cd /Users/frank.claes/dev-private/kinect/kinect-jni
mvn -B -q clean install -DskipTests 2>&1 | tail -20
if [ $? -ne 0 ]; then
    echo "ERROR: kinect-jni build failed!"
    exit 1
fi
echo "kinect-jni build: SUCCESS"

echo ""
echo "=== Step 2: Building kinect-openrndr ==="
cd /Users/frank.claes/dev-private/kinect/kinect-openrndr
mvn -B -q clean install -DskipTests 2>&1 | tail -20
if [ $? -ne 0 ]; then
    echo "ERROR: kinect-openrndr build failed!"
    exit 1
fi
echo "kinect-openrndr build: SUCCESS"

echo ""
echo "=== Step 3: Running point cloud example ==="
cd /Users/frank.claes/dev-private/kinect/kinect-openrndr
./run-example.sh pointcloud

echo ""
echo "=== Test complete ==="
