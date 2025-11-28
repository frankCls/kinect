# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Maven-based project for integrating Microsoft Kinect V2 with macOS on Apple Silicon (M2 Pro) using JNI bindings to the open-source libfreenect2 driver. The project enables Java/Kotlin applications to access raw Kinect sensor data (Color, Depth, IR) and perform 3D point cloud processing.

**Critical Architecture Constraint**: The Kinect V2 official Microsoft SDK does not work on macOS. This project uses libfreenect2 (open-source C++ driver) which must be manually compiled for ARM64 architecture and accessed via JNI.

## Prerequisites

Before any development work, the following **must** be installed and verified:

### 1. libfreenect2 Native Driver (ARM64)
```bash
# Install dependencies
brew install cmake pkg-config libusb

# Clone and compile libfreenect2 for ARM64
git clone https://github.com/OpenKinect/libfreenect2.git
cd libfreenect2
mkdir build && cd build
cmake .. -DCMAKE_INSTALL_PREFIX=$HOME/freenect2
make
make install

# CRITICAL: Verify installation
$HOME/freenect2/bin/Protonect
```

**Success Criteria**: Protonect must enumerate the Kinect V2 device and stream Color/Depth/IR without `LIBUSB_ERROR_NOT_FOUND` or buffer overflow errors. If this fails, all subsequent work will fail.

### 2. System Dependencies
- Xcode Command Line Tools: `xcode-select --install`
- Java 11+ (for Maven compilation)
- Maven 3.6+ (for build system)
- Homebrew (for dependency management)

## Project Structure

This is a **three-module Maven project**:

```
kinect/
├── pom.xml                  # Parent POM with module declarations
├── kinect-jni/              # JNI layer: Java interfaces + C++ implementation
│   ├── pom.xml              # Includes maven-native-plugin for .dylib compilation
│   └── src/main/
│       ├── java/            # JNI interface classes (Freenect, KinectDevice, Frame, etc.)
│       ├── cpp/             # Native JNI implementation linking to libfreenect2
│       └── resources/       # Native library loading configuration
├── kinect-core/             # Kotlin high-level API
│   ├── pom.xml              # Includes kotlin-maven-plugin
│   └── src/main/kotlin/     # Type-safe wrappers (Kinect2Device, PointCloud, etc.)
└── kinect-app/              # Sample application
    ├── pom.xml
    └── src/main/java/       # Demonstration code
```

## Build Commands

### Full Build
```bash
mvn clean install
```
This will:
1. Compile Java classes in kinect-jni
2. Compile C++ code into `kinect-jni.dylib` (links against `$HOME/freenect2/lib/libfreenect2.dylib`)
3. Compile Kotlin code in kinect-core
4. Build the sample application JAR

### Build Individual Modules
```bash
cd kinect-jni && mvn clean install
cd kinect-core && mvn clean install
cd kinect-app && mvn clean package
```

### Run Sample Application
```bash
java -Djava.library.path=/path/to/kinect-jni/target -jar kinect-app/target/kinect-app.jar
```

**Note**: The `-Djava.library.path` must point to the directory containing `kinect-jni.dylib`, which also must be able to find `libfreenect2.dylib`.


### Run Tests
```bash
mvn test
```


### Development instructions for CLAUDE

- ***IMPORTANT!*** document every step (successes AND fails) in the docs/log.md file
- ***ALWAYS*** use the local-maven-runner agent for running maven commands

## Development Workflow

### Working with JNI Code (kinect-jni)

The JNI module is the most critical and complex component. It bridges Java and the native libfreenect2 C++ library.

**Key Classes**:
- `Freenect.java` - Entry point, creates context
- `FreenectContext.java` - Device enumeration and lifecycle
- `KinectDevice.java` - Device operations (open/close, streaming)
- `Frame.java` - Raw frame data with ByteBuffer access
- `Registration.java` - Depth-to-color alignment API
- `Calibration.java` - Camera intrinsic parameters

**Native Implementation Guidelines**:
- All C++ code in `src/main/cpp/` must link against libfreenect2
- Use `ByteBuffer.allocateDirect()` for zero-copy data transfer
- Implement strict lifecycle management to prevent memory leaks
- Throw `RuntimeException` from JNI for libfreenect2 errors
- Thread-safety: libfreenect2 callbacks execute on background threads

**Compilation Notes**:
- The maven-native-plugin must be configured with:
  - Include path: `$HOME/freenect2/include`
  - Library path: `$HOME/freenect2/lib`
  - Link flags: `-lfreenect2 -lusb-1.0`
  - Target architecture: ARM64 (Apple Silicon)

### Working with Kotlin Code (kinect-core)

High-level, type-safe API for application developers.

**Key Classes**:
- `Kinect2Device.kt` - Main device wrapper with resource management
- `FrameTypes.kt` - Type-safe frame wrappers (DepthFrame, ColorFrame, InfraredFrame)
- `Geometry3D.kt` - 3D data types (Point3D, PointCloud)
- `RegistrationPipeline.kt` - Manages depth-color alignment
- `CameraCalibration.kt` - Unprojection algorithm (2D depth pixel → 3D coordinate)

**Design Principles**:
- Null safety for all frame operations
- Implement `AutoCloseable` for resource management
- Direct ByteBuffer access for performance-critical paths
- Minimize JVM heap allocations in hot loops
- Target: <10ms overhead vs. raw JNI

### Performance Considerations

**Critical Performance Requirements**:
- Frame rate: ≥25 FPS (target: 30 FPS)
- End-to-end latency: <100ms
- Memory stable over extended runs (no leaks)

**Optimization Strategies**:
- Use `ByteBuffer.allocateDirect()` to avoid JNI data copying
- Pool frame buffers to reduce GC pressure
- Process frames on background threads
- Avoid converting entire depth arrays to JVM objects unless necessary

## Architecture and Data Flow

### Runtime Data Flow
```
Kinect V2 Hardware
    ↓ (USB 3.0 - isochronous transfer)
libfreenect2.dylib (native C++)
    ↓ (JNI boundary)
kinect-jni.dylib (JNI wrapper)
    ↓ (Java objects)
kinect-jni JAR (Java interfaces)
    ↓ (Kotlin wrapper)
kinect-core JAR (Kotlin API)
    ↓
Application Code
```

### Stream Types and Resolution
- **Color Stream**: 1920x1080, RGB, 30 FPS
- **Depth Stream**: 512x424, 16-bit depth values (mm), 30 FPS
- **Infrared Stream**: 512x424, 16-bit IR intensity, 30 FPS

### Registration Pipeline
The Kinect V2 depth and color cameras are physically separate. To generate accurate colorized point clouds:
1. Capture both depth and color frames
2. Use libfreenect2's registration pipeline (via JNI) to align them
3. Apply camera calibration parameters for 3D unprojection
4. Result: Each 3D point has accurate (X, Y, Z) + RGB color

## Known Limitations

### What libfreenect2 Does NOT Provide
- **Skeletal Tracking**: Not available. Must be implemented separately using ML frameworks (e.g., Mediapipe on the color stream)
- **Face Tracking**: Not available
- **Audio Processing**: Unreliable/non-functional on macOS
- **Firmware Updates**: Requires Windows machine with official SDK

### Hardware Requirements
- **USB 3.0 Controller**: Must support high-bandwidth isochronous transfers
- **Known Good**: Intel, NEC controllers
- **Known Bad**: ASMedia controllers
- **Adapter**: Use high-quality USB 3.0 adapter; avoid USB hubs

## Troubleshooting

### Common Build Errors

**Error**: `UnsatisfiedLinkError: no kinect-jni in java.library.path`
- **Cause**: Runtime cannot find the native .dylib
- **Fix**: Set `-Djava.library.path` to the directory containing `kinect-jni.dylib`

**Error**: `dyld: Library not loaded: libfreenect2.dylib`
- **Cause**: kinect-jni.dylib cannot find libfreenect2.dylib
- **Fix**: Add `$HOME/freenect2/lib` to `DYLD_LIBRARY_PATH` or use `install_name_tool` to fix rpath

**Error**: `LIBUSB_ERROR_NOT_FOUND` in Protonect
- **Cause**: libusb or libfreenect2 installation corrupted
- **Fix**: Reinstall libusb: `brew reinstall libusb`, then recompile libfreenect2

**Error**: Maven native compilation fails
- **Cause**: Incorrect paths to libfreenect2 headers/libs in pom.xml
- **Fix**: Verify `$HOME/freenect2` exists and contains `include/` and `lib/` directories

### Runtime Errors

**Error**: `buffer overflow!` in logs
- **Cause**: USB 3.0 bandwidth saturation or controller incompatibility
- **Fix**:
  - Disconnect other USB 3.0 devices
  - Use different USB 3.0 port/adapter
  - Check USB controller chipset compatibility

**Error**: Device enumeration fails
- **Cause**: Protonect not working, foundation is broken
- **Fix**: Return to Phase 0 prerequisites; verify Protonect works standalone

**Error**: Memory leak over extended runs
- **Cause**: Frame buffers not released in JNI
- **Fix**: Verify all `delete` calls in C++ match `new` allocations; use Instruments to profile

## Testing Strategy

### Unit Tests
- Mock JNI layer for kinect-core tests
- Test Kotlin API without requiring physical device

### Integration Tests
- Require physical Kinect V2 device
- Test full pipeline: device open → frame capture → 3D processing → device close
- Validate frame rates and memory stability

### Performance Tests
- Measure frame processing latency
- Monitor memory usage over 30-minute runs
- Profile JNI boundary overhead

## Documentation References

- `docs/problem.md` - Complete technical analysis of Kinect V2 on M2 Pro architecture
- `docs/plan.md` - Detailed implementation plan with phases and success criteria
- libfreenect2: https://github.com/OpenKinect/libfreenect2
- libfreenect2 API docs: See source code comments in libfreenect2/include/

## Future Work (Out of Scope)

- Skeletal tracking via Mediapipe integration
- Multi-device support (multiple Kinects)
- GPU-accelerated point cloud processing
- Real-time visualization tools
- ROS integration