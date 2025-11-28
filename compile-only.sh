#!/bin/bash

echo "=== Compiling kinect-jni ==="
cd /Users/frank.claes/dev-private/kinect/kinect-jni && mvn -B compile -DskipTests

echo ""
echo "=== Compiling kinect-openrndr ==="
cd /Users/frank.claes/dev-private/kinect/kinect-openrndr && mvn -B compile -DskipTests

echo ""
echo "=== Compilation complete ==="
