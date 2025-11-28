#!/bin/bash
set -e

echo "=== Building kinect-jni ==="
cd /Users/frank.claes/dev-private/kinect/kinect-jni
mvn -B -q clean install -DskipTests

echo ""
echo "=== Building kinect-openrndr ==="
cd /Users/frank.claes/dev-private/kinect/kinect-openrndr
mvn -B -q clean install -DskipTests

echo ""
echo "=== Build complete ==="
