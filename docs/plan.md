# Implementation Plan: Kinect V2 Native JNI Integration (Pathway A)

## Architecture Overview

Three-module Maven project with:
- **kinect-jni**: Low-level JNI bindings (Java + C++) to libfreenect2
- **kinect-core**: High-level Kotlin abstractions for 3D processing
- **kinect-app**: Sample application (Java or Kotlin TBD)

## Implementation Phases

### Phase 0: Environment Setup & libfreenect2 Compilation (Prerequisites)

**Purpose**: Establish the native ARM64 driver foundation that all subsequent work depends on.

**Steps**:
1. Install Xcode Command Line Tools
   ```bash
   xcode-select --install
   ```

2. Install Homebrew dependencies
   ```bash
   brew install cmake pkg-config libusb
   ```

3. Clone and compile libfreenect2 for ARM64
   ```bash
   git clone https://github.com/OpenKinect/libfreenect2.git
   cd libfreenect2
   mkdir build && cd build
   cmake .. -DCMAKE_INSTALL_PREFIX=$HOME/freenect2
   make
   make install
   ```

4. Verify installation with Protonect test
   ```bash
$HOME/freenect2/bin/Protonect
   ```

**Success Criteria**:
- Protonect successfully enumerates Kinect V2 device
- Color, Depth, and IR streams display without USB errors
- No `LIBUSB_ERROR_NOT_FOUND` or buffer overflow errors

---

### Phase 1: Maven Project Structure

**Purpose**: Create the multi-module build system with proper ARM64 native compilation support.

**Deliverables**:
- Parent `pom.xml` with module declarations
- `kinect-jni/pom.xml` with maven-native-plugin configuration
- `kinect-core/pom.xml` with kotlin-maven-plugin
- `kinect-app/pom.xml` stub
- Directory structure for all modules

**Key Configuration**:
- Maven compiler plugin targeting Java 11+
- Native plugin configured for ARM64 architecture
- Library search paths pointing to `$HOME/freenect2/lib`
- Kotlin plugin version 1.9+

**File Structure**:
```
kinect/
├── pom.xml (parent)
├── kinect-jni/
│   ├── pom.xml
│   └── src/main/{java,cpp,resources}
├── kinect-core/
│   ├── pom.xml
│   └── src/main/{kotlin,resources}
└── kinect-app/
    ├── pom.xml
    └── src/main/{java,resources}
```

---

### Phase 2: JNI Wrapper Layer (kinect-jni)

**Purpose**: Create low-level Java/C++ bridge to libfreenect2 native library.

**Java Classes** (`src/main/java/com/kinect/jni/`):
1. **Freenect.java**
   - Static factory: `createContext()`
   - Library loading and initialization

2. **FreenectContext.java**
   - Device enumeration
   - Context lifecycle management

3. **KinectDevice.java**
   - Device open/close
   - Stream start/stop control
   - Frame callbacks

4. **Frame.java**
   - Raw frame data wrappers (Color, Depth, IR)
   - ByteBuffer-based data access
   - Timestamp and metadata

5. **Registration.java**
   - Depth-to-color registration API
   - Coordinate mapping functions

6. **Calibration.java**
   - Camera intrinsic parameters
   - Focal length, principal point, distortion coefficients

**Native Implementation** (`src/main/cpp/`):
- JNI method implementations wrapping libfreenect2 C++ API
- Memory management (ByteBuffer allocation, cleanup)
- Error handling with JNI exceptions
- Thread-safe callback mechanisms

**Build Configuration**:
- Link against `$HOME/freenect2/lib/libfreenect2.dylib`
- Link against system libusb
- Generate `kinect-jni.dylib` alongside JAR

**Success Criteria**:
- `mvn clean install` builds without errors
- Generated .dylib loads successfully at runtime
- Simple test can enumerate devices

---

### Phase 3: Kotlin Core Abstractions (kinect-core)

**Purpose**: Provide high-level, type-safe Kotlin API for 3D processing.

**Kotlin Classes** (`src/main/kotlin/com/kinect/core/`):

1. **Kinect2Device.kt**
   - Wrapper around JNI KinectDevice
   - Kotlin-idiomatic API (use blocks, coroutines)
   - Resource management with AutoCloseable

2. **FrameTypes.kt**
   - `DepthFrame`: Type-safe depth data wrapper
   - `ColorFrame`: RGB frame with metadata
   - `InfraredFrame`: IR stream wrapper
   - Extension functions for data conversion

3. **Geometry3D.kt**
   - `Point3D`: 3D coordinate data class
   - `PointCloud`: Collection of 3D points with colors
   - Vector operations and transformations

4. **RegistrationPipeline.kt**
   - Orchestrates depth-color-IR alignment
   - Manages registration state
   - Provides registered frame access

5. **CameraCalibration.kt**
   - Intrinsic parameter management
   - Unprojection algorithm (2D depth pixel → 3D coordinate)
   - Distortion correction utilities

6. **SkeletalTracker.kt** (placeholder)
   - Interface for future Mediapipe integration
   - Skeletal data types

**Key Features**:
- Null safety for frame handling
- Coroutine support for async streaming
- Flow-based reactive streams (optional)
- Direct ByteBuffer access for performance

**Success Criteria**:
- Type-safe API prevents common errors
- Point cloud generation works correctly
- Performance: <10ms overhead vs raw JNI

---

### Phase 4: Sample Application (kinect-app)

**Purpose**: Demonstrate end-to-end functionality and validate the pipeline.

**Components**:
1. **KinectSampleApp** (Java or Kotlin)
   - Device initialization
   - Frame streaming loop
   - Graceful shutdown

2. **PointCloudExporter**
   - Generate 3D point cloud from depth + color
   - Export to PLY or OBJ format
   - File I/O utilities

**Features**:
- Command-line arguments for configuration
- Real-time frame rate display
- Error handling and diagnostics
- Optional: Simple 3D visualization

**Success Criteria**:
- Application runs without crashes
- Exports valid point cloud files
- Frame rate ≥ 25 FPS
- Memory stable over extended runs

---

## Dependencies

### System Dependencies (Manual Installation)
- Xcode Command Line Tools
- Homebrew
- libfreenect2 (compiled to `$HOME/freenect2`)
- libusb (via Homebrew)
- CMake (via Homebrew)

### Maven Dependencies
- `org.jetbrains.kotlin:kotlin-stdlib` (1.9+)
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` (optional)
- `org.slf4j:slf4j-api` (logging)
- `junit:junit` (testing)

**Note**: libfreenect2 is NOT a Maven artifact; it's a pre-compiled native library linked at build time.

---

## Build & Runtime Flow

### Build Process
```
1. Prerequisites (manual, one-time):
   - Compile libfreenect2 → $HOME/freenect2
   - Verify with Protonect

2. Maven build:
   mvn clean install
   ├── kinect-jni: Java → .class, C++ → .dylib
   ├── kinect-core: Kotlin → .class (depends on kinect-jni)
   └── kinect-app: Java/Kotlin → executable JAR
```

### Runtime Process
```
1. Launch: java -Djava.library.path=<path-to-native-lib> -jar kinect-app.jar
2. JVM loads kinect-jni.dylib
3. kinect-jni.dylib dynamically loads libfreenect2.dylib
4. Data flow: libfreenect2 (C++) → JNI → Java → Kotlin → App
```

---

## Risk Mitigation

| Risk | Severity | Mitigation |
|------|----------|-----------|
| libfreenect2 compilation fails on ARM64 | High | Follow problem.md Section III precisely; validate each step |
| JNI linking errors | High | Explicit library paths in pom.xml; early linking tests |
| Memory leaks in JNI | High | Strict lifecycle management; valgrind/Instruments testing |
| USB 3.0 instability | Critical | Hardware issue; verify USB controller quality per problem.md 2.3 |
| Frame rate performance | Medium | ByteBuffer direct access; minimize JVM heap copies |

---

## Success Criteria (Overall)

### Functional Requirements
- ✓ Device enumeration via libfreenect2
- ✓ Real-time Color (1920x1080), Depth (512x424), IR streaming
- ✓ Depth-to-color registration working
- ✓ 3D point cloud generation with correct coordinates
- ✓ Exported point clouds loadable in standard 3D software

### Performance Requirements
- ✓ Frame rate ≥ 25 FPS (target: 30 FPS)
- ✓ Latency < 100ms end-to-end
- ✓ Memory stable (no leaks over 30-minute run)

### Quality Requirements
- ✓ `mvn clean install` succeeds with zero errors
- ✓ No JNI memory leaks (verified with Instruments)
- ✓ Clean shutdown without crashes
- ✓ Error messages are clear and actionable

---

## Future Enhancements (Out of Scope)

- Skeletal tracking via Mediapipe integration
- Multi-device support
- GPU-accelerated point cloud processing
- Advanced visualization tools
- Audio stream processing (unreliable on macOS per problem.md)

---

## References

- `docs/problem.md` - Detailed technical analysis and architectural constraints
- libfreenect2 repository: https://github.com/OpenKinect/libfreenect2
- Kinect V2 hardware specifications
- Maven Native Plugin documentation
- Kotlin JNI interop best practices
