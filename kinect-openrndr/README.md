# kinect-openrndr

OPENRNDR extension for Microsoft Kinect V2 integration.

## Overview

This module provides a seamless integration between Kinect V2 and OPENRNDR creative coding framework. It offers:

- **Extension-based API** - Lifecycle managed by OPENRNDR
- **Reactive Streams** - StateFlow-based frame delivery
- **GPU-Resident Buffers** - ColorBuffer integration for efficient rendering
- **Multi-Stream Support** - Depth, Color, and IR cameras
- **CPU Pipeline** - Software processing to avoid OpenGL context conflicts

## Quick Start

```kotlin
import org.openrndr.application
import org.openrndr.kinect2.Kinect2
import com.kinect.jni.PipelineType

fun main() = application {
    configure {
        width = 512
        height = 424
    }

    program {
        val kinect = extend(Kinect2()) {
            enableDepth = true
            pipelineType = PipelineType.CPU
        }

        extend {
            drawer.image(kinect.depthCamera.currentFrame)
        }
    }
}
```

## Running Examples

```bash
# Using the convenience script
./run-example.sh depth      # Depth camera
./run-example.sh full       # All streams

# Or with Maven directly
mvn exec:java -Dexec.mainClass="org.openrndr.kinect2.examples.Kinect2DepthExampleKt"
```

## API Reference

### Kinect2 Extension

Main extension class that manages device lifecycle.

```kotlin
val kinect = extend(Kinect2()) {
    deviceIndex = 0                     // Device index (default: 0)
    deviceSerial = null                 // Or specify by serial
    enableDepth = true                  // Enable depth stream
    enableColor = true                  // Enable color stream
    enableInfrared = true               // Enable IR stream
    pipelineType = PipelineType.CPU     // CPU or OPENGL
}
```

### Camera Access

Each camera provides:
- `currentFrame`: ColorBuffer - GPU-resident buffer updated each frame
- `frameFlow`: StateFlow<Frame?> - Reactive stream access
- `framesReceived`: Long - Total frames captured
- `lastTimestamp`: Long - Last frame timestamp (microseconds)

```kotlin
// Render camera directly
drawer.image(kinect.depthCamera.currentFrame)

// React to frames
kinect.depthCamera.frameFlow.collect { frame ->
    // Process frame
}

// Check statistics
println("Captured ${kinect.depthCamera.framesReceived} frames")
```

### Device Discovery

```kotlin
import org.openrndr.kinect2.Kinect2Manager

// Check for devices
if (Kinect2Manager.hasDevices()) {
    val devices = Kinect2Manager.getKinectsV2()
    println("Found ${devices.size} device(s)")
}

// Get specific device
val device = Kinect2Manager.getDevice(0)
println("Device serial: ${device?.serial}")
```

## Camera Specifications

| Camera | Resolution | Format | ColorBuffer Type | FPS |
|--------|-----------|--------|------------------|-----|
| Depth  | 512x424   | 16-bit depth (mm) | R/FLOAT16 | 30 |
| Color  | 1920x1080 | BGRX | RGB/UINT8 | 30 |
| IR     | 512x424   | 16-bit intensity | R/FLOAT16 | 30 |

## Pipeline Selection

### CPU Pipeline (Recommended)
- ✅ No OpenGL context conflicts
- ✅ Thread-safe from any context
- ✅ Works with OPENRNDR, Processing, etc.
- ⚠️ Slower (~30 Hz depth processing)

### OpenGL Pipeline
- ✅ GPU-accelerated (~296 Hz)
- ⚠️ May conflict with framework GL contexts
- ⚠️ Requires main-thread initialization

**For OPENRNDR, always use `PipelineType.CPU`** to avoid OpenGL context conflicts.

## Examples

See `src/main/kotlin/org/openrndr/kinect2/examples/`:

- **Kinect2DepthExample.kt** - Minimal depth camera example
- **Kinect2Example.kt** - Full multi-stream example with all three cameras

## Requirements

- **Hardware**: Microsoft Kinect V2 connected via USB 3.0
- **Native Library**: kinect-jni module compiled
- **libfreenect2**: Installed at `~/freenect2`
- **Java**: 11 or higher
- **OPENRNDR**: 0.4.4 (included as dependency)

## Troubleshooting

### "No Kinect V2 devices found"

Check:
1. Kinect is connected to USB 3.0 port
2. Kinect power supply is connected
3. libfreenect2 is installed: `ls ~/freenect2/lib/libfreenect2.dylib`
4. Protonect works: `~/freenect2/bin/Protonect`

### "Native library not found"

The native JNI library path must be set:
```bash
-Djava.library.path=../kinect-jni/target:$HOME/freenect2/lib
```

Use the `run-example.sh` script which handles this automatically.

### Application crashes with GL errors

Make sure you're using `PipelineType.CPU` not `PipelineType.OPENGL` to avoid conflicts with OPENRNDR's OpenGL context.

## Architecture

Based on the proven [orx-kinect-v1](https://github.com/openrndr/orx/tree/master/orx-jvm/orx-kinect-v1) architecture, adapted for Kinect V2 hardware:

- **Dedicated Acquisition Thread**: Non-blocking frame capture
- **Double Buffering**: Front/back buffer swap for thread-safe rendering
- **StateFlow Pipeline**: Reactive frame delivery with coroutines
- **Zero-Copy Upload**: Direct ByteBuffer to ColorBuffer writes

## License

See parent project license.
