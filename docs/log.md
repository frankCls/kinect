# Kinect V2 Integration Development Log

## 2025-11-23: Phase 0 - Environment Setup & libfreenect2 Compilation

### Prerequisites Installation

**Status**: COMPLETED ✓

All system prerequisites were verified/installed:
- Xcode Command Line Tools: Already installed at `/Library/Developer/CommandLineTools`
- Homebrew: Already installed at `/opt/homebrew/bin/brew`
- CMake: Already installed at `/opt/homebrew/bin/cmake`
- pkg-config: Already installed (pkgconf 2.4.3)
- libusb: Already installed (libusb 1.0.27 via Homebrew)

### libfreenect2 Compilation for ARM64

**Status**: COMPLETED ✓

Successfully compiled libfreenect2 for Apple Silicon (ARM64):

1. Cloned repository from https://github.com/OpenKinect/libfreenect2.git
2. Configured with CMake targeting `$HOME/freenect2` installation prefix
3. Build configuration detected:
   - Compiler: AppleClang 17.0.0.17000013
   - Architecture: ARM64 (native)
   - OpenCL: yes
   - OpenGL: yes
   - TurboJPEG: yes (via Homebrew)
   - VideoToolbox: yes (Apple hardware acceleration)
   - CUDA: no (not available on macOS)

4. Compiled successfully using all available CPU cores
5. Installed to `/Users/frank.claes/freenect2/`:
   - Library: `lib/libfreenect2.0.2.0.dylib` (498K)
   - Headers: `include/libfreenect2/`
   - CMake config: `lib/cmake/freenect2/`
   - pkg-config: `lib/pkgconfig/freenect2.pc`

6. Copied Protonect test binary to `bin/Protonect`

### Verification Test

**Status**: SUCCESS ✓

Executed `/Users/frank.claes/freenect2/bin/Protonect`:
- Window opened successfully
- Kinect V2 device detected and enumerated
- Live camera feed displayed (confirmed by user)
- USB 3.0 communication working correctly

**Key Success Indicators**:
- No `LIBUSB_ERROR_NOT_FOUND` errors
- No buffer overflow warnings
- Real-time video streaming functional

### Installation Paths

```
$HOME/freenect2/
├── bin/
│   └── Protonect
├── include/
│   └── libfreenect2/
│       ├── libfreenect2.hpp
│       ├── frame_listener.hpp
│       ├── registration.h
│       └── ... (other headers)
└── lib/
    ├── libfreenect2.0.2.0.dylib
    ├── libfreenect2.0.2.dylib -> libfreenect2.0.2.0.dylib
    ├── libfreenect2.dylib -> libfreenect2.0.2.dylib
    ├── cmake/freenect2/
    └── pkgconfig/freenect2.pc
```

### Next Steps

Phase 0 is complete. Ready to proceed with Phase 1: Maven Project Structure.

---

## 2025-11-23: Phase 1 - Maven Project Structure

**Status**: COMPLETED ✓

### Parent POM Configuration

Created `pom.xml` at project root with:
- **Multi-module structure**: kinect-jni, kinect-core, kinect-app
- **Java 11** target for compatibility
- **Kotlin 1.9.21** with coroutines support
- **Dependency management** for SLF4J, JUnit, Kotlin stdlib
- **Plugin management** for Java, Kotlin, native compilation, assembly
- **Build properties**:
  - Native platform: darwin (macOS)
  - Native architecture: arm64 (Apple Silicon)
  - libfreenect2 paths: `$HOME/freenect2/`
- **Platform profiles** for macOS ARM64 and x86_64

### Module 1: kinect-jni (JNI Bindings)

Created `kinect-jni/pom.xml` with:
- **Native Maven Plugin** configuration for ARM64 JNI compilation
- **Build phases**:
  1. `javah`: Generate JNI headers from Java classes
  2. `compile-native`: Compile C++ with clang++ targeting ARM64
  3. `link-native`: Link against libfreenect2.dylib and libusb
- **Compiler flags**:
  - `-std=c++11 -stdlib=libc++`
  - `-arch arm64 -fPIC`
  - Include paths: `$JAVA_HOME/include`, `$HOME/freenect2/include`
- **Linker flags**:
  - `-dynamiclib -arch arm64`
  - `-L$HOME/freenect2/lib -lfreenect2`
  - `-L/opt/homebrew/lib -lusb-1.0`
  - Install name: `@rpath/libkinect-jni.dylib`
- **Output**: `libkinect-jni.dylib` alongside JAR

**Directory structure**:
```
kinect-jni/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/kinect/jni/
    │   ├── cpp/
    │   └── resources/
    └── test/java/com/kinect/jni/
```

### Module 2: kinect-core (Kotlin Core API)

Created `kinect-core/pom.xml` with:
- **Kotlin Maven Plugin** for Kotlin compilation
- **Dependency** on kinect-jni module
- **Kotlin coroutines** for async streaming
- **Mixed Java/Kotlin** compilation support
- **Source directories**: `src/main/kotlin`, `src/test/kotlin`
- **JVM target**: Java 11

**Directory structure**:
```
kinect-core/
├── pom.xml
└── src/
    ├── main/
    │   ├── kotlin/com/kinect/core/
    │   └── resources/
    └── test/kotlin/com/kinect/core/
```

### Module 3: kinect-app (Sample Application)

Created `kinect-app/pom.xml` with:
- **Dependency** on kinect-core module
- **Main class**: `com.kinect.app.KinectSampleApp`
- **JAR Plugin** with manifest entries
- **Assembly Plugin** for executable JAR with dependencies
- **Surefire Plugin** configured with native library path
- **SLF4J Simple** logger for runtime

**Directory structure**:
```
kinect-app/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/kinect/app/
    │   └── resources/
    └── test/java/com/kinect/app/
```

### Maven Validation

Executed `mvn validate`:
```
[INFO] Reactor Build Order:
[INFO]
[INFO] Kinect V2 Integration Parent                 [pom]
[INFO] Kinect JNI Bindings                          [jar]
[INFO] Kinect Core API                              [jar]
[INFO] Kinect Sample Application                    [jar]
[INFO]
[INFO] Reactor Summary:
[INFO] Kinect V2 Integration Parent ....... SUCCESS
[INFO] Kinect JNI Bindings ................. SUCCESS
[INFO] Kinect Core API ..................... SUCCESS
[INFO] Kinect Sample Application ........... SUCCESS
[INFO] BUILD SUCCESS
```

All modules validated successfully. Maven recognizes the three-module structure and dependency chain.

### Project Structure Summary

```
kinect/
├── pom.xml (parent)
├── kinect-jni/
│   ├── pom.xml
│   └── src/main/{java,cpp,resources}
├── kinect-core/
│   ├── pom.xml
│   └── src/main/{kotlin,resources}
├── kinect-app/
│   ├── pom.xml
│   └── src/main/{java,resources}
└── docs/
    ├── problem.md
    ├── plan.md
    └── log.md
```

### Next Steps

Phase 1 is complete. Ready to proceed with Phase 2: JNI Wrapper Implementation.

---

## 2025-11-23: Phase 2 - JNI Wrapper Implementation

**Status**: COMPLETED ✓

### Java JNI Interface Classes

Created 7 Java classes in `kinect-jni/src/main/java/com/kinect/jni/`:

1. **Freenect.java** (Entry point)
   - Static factory: `createContext()`
   - Native library loading with error handling
   - Version API: `getVersion()`

2. **FreenectContext.java** (Device enumeration)
   - Implements `AutoCloseable` for resource management
   - Methods:
     - `getDeviceCount()` - enumerate connected devices
     - `getDeviceSerial(int index)` - get serial by index
     - `getDefaultDeviceSerial()` - get first device serial
     - `openDevice(String serial)` - open specific device
     - `openDefaultDevice()` - open first device
   - Native handle management with lifecycle tracking

3. **FrameType.java** (Frame type enumeration)
   - `COLOR` (1920x1080 RGB, 30 FPS)
   - `DEPTH` (512x424, 16-bit depth in mm, 30 FPS)
   - `IR` (512x424, 16-bit IR intensity, 30 FPS)
   - Bidirectional native value mapping

4. **Frame.java** (Frame data container)
   - Implements `AutoCloseable`
   - Direct ByteBuffer access for zero-copy performance
   - Metadata: width, height, bytesPerPixel, timestamp, sequence
   - Methods: `getData()`, `getType()`, `getDataSize()`

5. **KinectDevice.java** (Device operations)
   - Implements `AutoCloseable`
   - Streaming control:
     - `start()` / `start(FrameType...)` - begin streaming
     - `stop()` - end streaming
     - `isStreaming()` - check status
   - Frame retrieval: `getNextFrame(FrameType, timeoutMs)`
   - Device info: `getSerial()`, `getFirmwareVersion()`

6. **Calibration.java** (Camera parameters)
   - Nested `CameraParams` class with intrinsic parameters:
     - Focal lengths (fx, fy)
     - Principal point (cx, cy)
     - Distortion coefficients (k1, k2, k3, p1, p2)
   - Separate params for color and depth cameras

7. **Registration.java** (Coordinate mapping - placeholder)
   - Interface for depth-to-color alignment
   - 3D unprojection methods (to be fully implemented)

### C++ JNI Implementation

Created `kinect-jni/src/main/cpp/Freenect2JNI.cpp` with native methods:

**Implemented (Fully Functional)**:
- `Freenect.getVersion()` - return libfreenect2 version
- `FreenectContext.nativeCreateContext()` - create Freenect2 instance
- `FreenectContext.nativeDestroyContext()` - cleanup context
- `FreenectContext.nativeGetDeviceCount()` - enumerate devices via USB
- `FreenectContext.nativeGetDeviceSerial()` - get serial by index
- `FreenectContext.nativeGetDefaultDeviceSerial()` - get first device
- `KinectDevice.nativeOpenDevice()` - open device with OpenCL pipeline
- `KinectDevice.nativeCloseDevice()` - close device
- `KinectDevice.nativeGetFirmwareVersion()` - query device firmware
- `KinectDevice.nativeStart()` - start streaming (all frame types)
- `KinectDevice.nativeStop()` - stop streaming

**Placeholder (Partial Implementation)**:
- `KinectDevice.nativeGetNextFrame()` - requires frame listener management
- `Frame.nativeGetFrameData()` - requires frame memory handling
- `Frame.nativeReleaseFrame()` - requires frame lifecycle
- `Registration.nativeDestroyRegistration()` - basic cleanup only

**Technical Details**:
- Uses libfreenect2 OpenCL packet pipeline (optimal for macOS)
- JNI exception handling via `throwRuntimeException()`
- Pointer management: `jlong` handles for native objects
- String conversions: UTF-8 JNI strings ↔ C++ std::string

### Build System Configuration

**Problem Solved**: Java 11+ removed `javah` tool
- **Solution**: Use `javac -h` to generate JNI headers
- Configured maven-compiler-plugin with `-h` flag

**POM Changes** (`kinect-jni/pom.xml`):
1. Replaced `native-maven-plugin` javah execution with compiler args
2. Switched from native-maven-plugin to **maven-antrun-plugin** for C++ compilation
3. Added **build-helper-maven-plugin** to attach native library

**Build Plugins**:
- **maven-compiler-plugin**: Java compilation + JNI header generation
- **maven-antrun-plugin**: Direct clang++ invocation for C++
- **maven-jar-plugin**: Package Java classes
- **build-helper-maven-plugin**: Attach `libkinect-jni.dylib` with classifier `natives-osx-arm64`

**Compiler Flags**:
```
clang++ -std=c++11 -stdlib=libc++ -arch arm64 -fPIC
-I$JAVA_HOME/include
-I$JAVA_HOME/include/darwin
-I$HOME/freenect2/include
-I<target>/native/include
```

**Linker Flags**:
```
clang++ -dynamiclib -arch arm64
-L$HOME/freenect2/lib -lfreenect2
-L/opt/homebrew/lib -lusb-1.0
-install_name @rpath/libkinect-jni.dylib
```

### Build Results

**Build Command**: `mvn install` (via maven-runner agent)

**Output Artifacts**:

Local Build Directory (`kinect-jni/target/`):
- `kinect-jni-1.0-SNAPSHOT.jar` (13 KB) - Java classes
- `libkinect-jni.dylib` (44 KB) - Native ARM64 library
- `objs/Freenect2JNI.o` - Compiled object file
- `native/include/*.h` - Generated JNI headers (5 files)

Maven Repository (`~/.m2/repository/com/kinect/kinect-jni/1.0-SNAPSHOT/`):
- `kinect-jni-1.0-SNAPSHOT.jar` - Main artifact
- `kinect-jni-1.0-SNAPSHOT-natives-osx-arm64.dylib` - Platform-specific native library
- `kinect-jni-1.0-SNAPSHOT.pom` - Project descriptor

**Library Dependencies** (verified with `otool -L`):
```
@rpath/libkinect-jni.dylib
@rpath/libfreenect2.0.2.dylib
/opt/homebrew/opt/libusb/lib/libusb-1.0.0.dylib
/usr/lib/libc++.1.dylib
/usr/lib/libSystem.B.dylib
```

**Build Status**: ✅ BUILD SUCCESS
- Java compilation: ✅ 7 classes compiled
- JNI headers: ✅ 5 headers generated
- C++ compilation: ✅ 1 object file created
- Native linking: ✅ libkinect-jni.dylib (44 KB)
- Installation: ✅ Artifacts in local Maven repository

### Warnings (Non-Critical)

1. **System modules path**: Java 11 compatibility warning (cosmetic)
2. **Deprecated API**: `finalize()` method in Frame/Context/Device (will refactor to use Cleaner API in future)

### Implementation Status

**Core Functionality**: 70% Complete
- ✅ Context creation and device enumeration
- ✅ Device open/close and firmware queries
- ✅ Streaming start/stop
- ⚠️ Frame capture (needs frame listener implementation)
- ⚠️ Registration and 3D unprojection (placeholders)

**What Works Now**:
- Library loading and initialization
- Device discovery via USB
- Device open/close lifecycle
- Streaming control (start/stop)

**What Needs Completion** (Phase 3):
- Frame listener management for actual frame capture
- Direct ByteBuffer mapping for frame data
- Frame memory lifecycle (allocation/release)
- Registration pipeline integration
- 3D coordinate unprojection

### Technical Achievements

1. **Native Compilation on ARM64**: Successfully compiled JNI library for Apple Silicon
2. **libfreenect2 Integration**: Linked against native libfreenect2 (0.2.0)
3. **Zero-Copy Architecture**: Designed for direct ByteBuffer access (implementation pending)
4. **Resource Management**: Java AutoCloseable pattern with native handle tracking
5. **Maven Integration**: Clean multi-module build with native artifact attachment

### Next Steps

Phase 2 JNI layer is structurally complete. Ready to proceed with:
- **Phase 3**: Complete frame capture implementation
- **Phase 4**: Kotlin Core API (kinect-core module)
- **Phase 5**: Sample application (kinect-app module)

### Environment

- Java: Amazon Corretto 17.0.9 (via SDKMAN)
- Maven: System installation
- Compiler: Apple clang version 15.0.0 (from Xcode Command Line Tools)
- Target: ARM64 macOS on Apple Silicon (M2 Pro)
- libfreenect2: Installed at ~/freenect2

---
