# Kinect V2 for macOS (Apple Silicon)

High-performance Java/Kotlin integration for Microsoft Kinect V2 on macOS using JNI bindings to libfreenect2. Enables full-resolution Color, Depth, and IR frame capture with GPU-accelerated OpenGL processing.

## Features

- **Full Sensor Access**: Color (1920x1080), Depth (512x424), and IR (512x424) streams at 30 FPS
- **GPU Acceleration**: OpenGL-accelerated depth processing (~296Hz)
- **Main-Thread Safe**: macOS-specific threading solution for GLFW/OpenGL
- **Zero-Copy Performance**: Direct ByteBuffer access to frame data
- **Type-Safe API**: Kotlin wrapper with modern idioms
- **Cross-Platform**: JNI layer compatible with macOS, Linux, Windows (via libfreenect2)

## Architecture

```
Kinect V2 Hardware
    ↓ USB 3.0
libfreenect2 (C++ driver)
    ↓ JNI
kinect-jni (Java bindings)
    ↓
kinect-core (Kotlin API)
    ↓
Your Application
```

**Key Components:**
- `kinect-jni`: JNI bridge to libfreenect2 with native C++ implementation
- `kinect-core`: High-level Kotlin API with type-safe wrappers
- `kinect-app`: Sample application demonstrating usage

## Prerequisites

### Hardware Requirements

- **Microsoft Kinect V2** sensor
- **USB 3.0** port with high-bandwidth controller
  - ✅ Recommended: Intel, NEC, Renesas controllers
  - ⚠️ Avoid: ASMedia controllers (known compatibility issues)
  - ⚠️ Avoid: USB hubs (insufficient bandwidth)
- **macOS** machine with **Apple Silicon** (M1/M2/M3) or Intel processor

### Software Requirements

#### 1. Xcode Command Line Tools

```bash
xcode-select --install
```

Verify installation:
```bash
xcode-select -p
# Should output: /Library/Developer/CommandLineTools or /Applications/Xcode.app/Contents/Developer
```

#### 2. Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

#### 3. Java 11 or Higher

```bash
# Check if Java is installed
java -version

# If not installed, install via Homebrew
brew install openjdk@17

# Add to PATH (add to ~/.zshrc or ~/.bash_profile)
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
```

#### 4. Maven 3.6 or Higher

```bash
brew install maven

# Verify installation
mvn -version
```

#### 5. Build Dependencies

```bash
brew install cmake pkg-config libusb glfw
```

## Installation

### Step 1: Clone Repository

```bash
git clone <your-repo-url>
cd kinect
```

### Step 2: Install libfreenect2 (Critical)

libfreenect2 is the open-source driver that communicates with the Kinect V2 hardware. It **must** be compiled from source for ARM64 architecture.

```bash
# Navigate to a temporary directory
cd ~

# Clone libfreenect2
git clone https://github.com/OpenKinect/libfreenect2.git
cd libfreenect2

# Create build directory
mkdir build && cd build

# Configure for ARM64 with installation prefix
cmake .. -DCMAKE_INSTALL_PREFIX=$HOME/freenect2

# Compile (this may take 5-10 minutes)
make

# Install to ~/freenect2
make install
```

**Installation locations:**
- Binaries: `~/freenect2/bin/`
- Libraries: `~/freenect2/lib/`
- Headers: `~/freenect2/include/`

### Step 3: Verify libfreenect2 Installation

**CRITICAL**: Before proceeding, verify that libfreenect2 can communicate with your Kinect:

```bash
# Connect your Kinect V2 to a USB 3.0 port

# Run Protonect test application
~/freenect2/bin/Protonect
```

**Expected output:**
```
[Info] [Freenect2Impl] enumerating devices...
[Info] [Freenect2Impl] found valid Kinect v2 @2:X with serial XXXXXXXXXXXXX
[Info] [Freenect2Impl] found 1 devices
```

You should see live color, depth, and IR streams in separate windows.

**If you see errors:**
- `LIBUSB_ERROR_NOT_FOUND`: USB connection issue, try different port
- `buffer overflow!`: USB bandwidth issue, disconnect other USB 3.0 devices
- No devices found: Check USB cable, ensure Kinect is powered

**⚠️ If Protonect doesn't work, nothing else will work. Fix this first before continuing.**

### Step 4: Build the Project

```bash
# Navigate to project root
cd /path/to/kinect

# Clean build everything
mvn clean install
```

**This will:**
1. Compile Java JNI interface classes
2. Compile C++ native library (`libkinect-jni.dylib`)
3. Link against libfreenect2 and GLFW
4. Compile Kotlin API wrappers
5. Build sample application

**Build time:** ~30-60 seconds

**Output:**
- Native library: `kinect-jni/target/libkinect-jni.dylib`
- Java classes: `kinect-jni/target/classes/`
- Kotlin classes: `kinect-core/target/classes/`

### Verify Build

```bash
# Check native library dependencies
otool -L kinect-jni/target/libkinect-jni.dylib
```

**Expected output should include:**
```
/Users/YOUR_USER/freenect2/lib/libfreenect2.0.2.dylib
/opt/homebrew/opt/glfw/lib/libglfw.3.dylib
```

## Running the Demo Application

The project includes `FrameCaptureDemo` - a standalone test application that captures frames from all three streams.

### Quick Start

```bash
cd kinect-jni
./run-demo.sh
```

**Default behavior:**
- Captures 10 frames from each stream (Color, Depth, IR)
- Runs for 10 seconds maximum
- Console output only (no GUI window)

### Command-Line Options

```bash
# Show help
./run-demo.sh --help

# Capture for 30 seconds
./run-demo.sh --duration 30

# Capture 100 frames per stream
./run-demo.sh --frames 100

# Enable visual window display (requires GUI session)
./run-demo.sh --gui

# Combine options
./run-demo.sh --gui --duration 60 --frames 200
```

### Expected Output

```
=== Kinect V2 Frame Capture Demo ===
Native library loaded: ✓
libfreenect2 version: 0.2.0
Found 1 Kinect device(s)
Device serial: 230921433847
Opening device...
Device opened successfully ✓

Starting frame capture...
[COLOR] Frame 1/10: seq=10, timestamp=27179, 1920x1080x4, 8294400 bytes
[DEPTH] Frame 1/10: seq=26, timestamp=27533, 512x424x4, 868352 bytes
[IR]    Frame 1/10: seq=28, timestamp=28066, 512x424x4, 868352 bytes
...

=== Capture Statistics ===
COLOR frames: 10/10 (100.0%) - 0 timeouts
DEPTH frames: 10/10 (100.0%) - 0 timeouts
IR frames: 10/10 (100.0%) - 0 timeouts
Total frames captured: 30
```

### Running Manually

If you prefer not to use the script:

```bash
cd kinect-jni

# Compile if needed
mvn compile

# Run with explicit library paths
java -Djava.library.path=target:$HOME/freenect2/lib \
     -cp target/classes \
     com.kinect.jni.FrameCaptureDemo \
     --duration 10 --frames 30
```

## Running Tests

```bash
# Run all tests
mvn test

# Run tests for specific module
mvn test -pl kinect-jni
```

**Note:** Hardware-dependent tests are marked with `@Ignore` to allow headless testing. To run hardware tests:

1. Remove `@Ignore` annotations from test classes
2. Ensure Kinect is connected
3. Run in a GUI session (not SSH)

## Frame Data Reference

### Stream Specifications

| Stream | Resolution | Format | Bytes/Pixel | Size/Frame | FPS |
|--------|-----------|--------|-------------|------------|-----|
| COLOR  | 1920x1080 | BGRX   | 4          | 8.3 MB     | 30  |
| DEPTH  | 512x424   | 16-bit | 4          | 868 KB     | 30  |
| IR     | 512x424   | 16-bit | 4          | 868 KB     | 30  |

### Accessing Frame Data

```java
// Get next frame
Frame frame = device.getNextFrame(FrameType.COLOR, 2000);

// Access metadata
int width = frame.getWidth();           // 1920 for color
int height = frame.getHeight();         // 1080 for color
long timestamp = frame.getTimestamp();  // Microseconds
long sequence = frame.getSequence();    // Frame number

// Access raw data (zero-copy)
ByteBuffer data = frame.getData();

// Always release frame when done
frame.close();
```

## Troubleshooting

### Issue: "Native library not found"

**Symptom:**
```
java.lang.UnsatisfiedLinkError: no kinect-jni in java.library.path
```

**Solution:**
```bash
# Ensure you're setting the library path correctly
java -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib ...

# Verify the library exists
ls -la kinect-jni/target/libkinect-jni.dylib
```

### Issue: "LIBUSB_ERROR_NOT_FOUND"

**Symptom:** Protonect or demo app can't find Kinect device

**Solutions:**
1. Check USB connection:
   ```bash
   system_profiler SPUSBDataType | grep -A 10 "Xbox NUI Sensor"
   ```
2. Try different USB 3.0 port
3. Check Kinect power supply is connected
4. Reinstall libusb:
   ```bash
   brew reinstall libusb
   # Then rebuild libfreenect2
   ```

### Issue: "buffer overflow!" errors

**Symptom:** Frame capture fails with buffer overflow messages

**Solutions:**
1. Disconnect other USB 3.0 devices
2. Close bandwidth-heavy applications
3. Use a different USB 3.0 port (preferably direct to motherboard)
4. Check USB controller chipset compatibility

### Issue: Build fails with "cannot find libfreenect2"

**Symptom:**
```
ld: library not found for -lfreenect2
```

**Solution:**
```bash
# Verify libfreenect2 is installed
ls $HOME/freenect2/lib/libfreenect2.dylib

# If missing, reinstall libfreenect2 (see Step 2)

# Verify pom.xml has correct path
grep freenect2.lib kinect-jni/pom.xml
# Should show: <freenect2.lib>${user.home}/freenect2/lib</freenect2.lib>
```

### Issue: "dyld: Library not loaded"

**Symptom:**
```
dyld: Library not loaded: libfreenect2.0.2.dylib
```

**Solution:**
```bash
# Check if libfreenect2 is in the expected location
ls $HOME/freenect2/lib/libfreenect2.0.2.dylib

# Verify library paths
otool -L kinect-jni/target/libkinect-jni.dylib

# If paths are wrong, rebuild:
cd kinect-jni
mvn clean compile
```

### Issue: Application crashes with SIGTRAP

**Symptom:** Crash with `EXC_BREAKPOINT (SIGTRAP)` in GLFW/OpenGL code

**Solution:** This should be fixed in the current version (main-thread OpenGL solution). If you still see this:
1. Ensure you have the latest code from main branch
2. Verify GLFW is linked:
   ```bash
   otool -L kinect-jni/target/libkinect-jni.dylib | grep glfw
   ```
3. Rebuild completely:
   ```bash
   mvn clean install
   ```

## Technical Details

### Main-Thread OpenGL Solution

This project uses a sophisticated threading solution for macOS:

- **Problem**: macOS requires GLFW/OpenGL initialization on the main thread
- **Solution**: Grand Central Dispatch (GCD) to dispatch OpenGL operations to main queue
- **Implementation**: `createOpenGLPipelineOnMainThread()` in `Freenect2JNI.cpp`
- **Performance Impact**: ~1-2ms overhead (only during device open/close)

See `docs/main-thread-opengl-solution.md` for complete technical documentation.

### Performance Characteristics

- **OpenGL Depth Processing**: ~296 Hz (GPU-accelerated)
- **Frame Latency**: <100ms end-to-end
- **Memory**: ~25 MB per frame set (all three streams)
- **CPU Usage**: <10% (GPU offload)

## Project Structure

```
kinect/
├── README.md                           # This file
├── CLAUDE.md                           # Development instructions
├── pom.xml                             # Parent Maven POM
├── docs/
│   ├── main-thread-opengl-solution.md  # Technical deep-dive
│   └── ...
├── kinect-jni/                         # JNI Layer
│   ├── pom.xml
│   ├── run-demo.sh                     # Test script
│   └── src/main/
│       ├── java/com/kinect/jni/        # Java interfaces
│       │   ├── Freenect.java
│       │   ├── FreenectContext.java
│       │   ├── KinectDevice.java
│       │   ├── Frame.java
│       │   ├── FrameType.java
│       │   ├── PipelineType.java       # CPU vs OpenGL selection
│       │   ├── FrameCaptureDemo.java   # Test application
│       │   └── ...
│       └── cpp/
│           └── Freenect2JNI.cpp        # Native implementation
├── kinect-core/                        # Kotlin API
│   └── src/main/kotlin/
├── kinect-openrndr/                    # OPENRNDR Extension
│   ├── pom.xml
│   └── src/main/kotlin/org/openrndr/kinect2/
│       ├── Kinect2.kt                  # Main extension + cameras
│       ├── Kinect2Manager.kt           # Device discovery
│       └── examples/                   # Example programs
└── kinect-app/                         # Sample application
```

## OPENRNDR Integration

The project includes `kinect-openrndr`, an OPENRNDR extension for creative coding with Kinect V2.

### Features

- **Extension-based API**: Integrates seamlessly with OPENRNDR's lifecycle
- **Reactive Streams**: StateFlow-based frame delivery with coroutines
- **GPU-Resident Buffers**: Zero-copy ColorBuffer integration
- **Multi-Stream Support**: Depth (512x424), Color (1920x1080), and IR (512x424)
- **CPU Pipeline**: Uses software pipeline to avoid OpenGL context conflicts

### Quick Start

Add the dependency to your OPENRNDR project:

```xml
<dependency>
    <groupId>com.kinect</groupId>
    <artifactId>kinect-openrndr</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Minimal example showing depth camera:

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
            enableColor = false
            enableInfrared = false
            pipelineType = PipelineType.CPU  // CPU pipeline for OPENRNDR
        }

        extend {
            drawer.image(kinect.depthCamera.currentFrame)
        }
    }
}
```

### Running OPENRNDR Examples

**Option 1: Using the convenience script (easiest)**

```bash
cd kinect-openrndr

# Run depth camera example (default)
./run-example.sh

# Or specify which example
./run-example.sh depth      # Depth camera only
./run-example.sh full       # All three streams
```

**Option 2: Using Maven directly**

```bash
# Run the depth camera example
mvn exec:java -pl kinect-openrndr \
    -Dexec.mainClass="org.openrndr.kinect2.examples.Kinect2DepthExampleKt" \
    -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib

# Run the full example (depth, color, and IR)
mvn exec:java -pl kinect-openrndr \
    -Dexec.mainClass="org.openrndr.kinect2.examples.Kinect2ExampleKt" \
    -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib
```

**Note**: The examples require:
- Kinect V2 hardware connected via USB 3.0
- `kinect-jni` module compiled (contains native library)
- libfreenect2 installed at `~/freenect2`

### Extension Configuration

```kotlin
val kinect = extend(Kinect2()) {
    deviceIndex = 0                     // Device index (default: 0)
    deviceSerial = null                 // Or specify serial number
    enableDepth = true                  // Enable depth stream
    enableColor = true                  // Enable color stream
    enableInfrared = true               // Enable infrared stream
    pipelineType = PipelineType.CPU     // CPU or OPENGL (default: CPU)
}
```

### Camera Access

Each camera provides:
- `currentFrame`: ColorBuffer (GPU-resident, updated each frame)
- `frameFlow`: StateFlow<Frame?> (reactive stream access)
- `framesReceived`: Long (frame counter)
- `lastTimestamp`: Long (last frame timestamp)

```kotlin
// Direct image rendering
drawer.image(kinect.depthCamera.currentFrame)

// Reactive stream processing
kinect.depthCamera.frameFlow.collect { frame ->
    frame?.let {
        // Process frame data
    }
}
```

### Device Discovery

```kotlin
import org.openrndr.kinect2.Kinect2Manager

// Check for devices
if (Kinect2Manager.hasDevices()) {
    val devices = Kinect2Manager.getKinectsV2()
    devices.forEach { device ->
        println("Found device: ${device.serial}")
    }
}

// Get device count
val count = Kinect2Manager.getDeviceCount()

// Print diagnostic info
Kinect2Manager.printDeviceInfo()
```

### Pipeline Selection

**CPU Pipeline** (Recommended for OPENRNDR):
- ✅ No OpenGL context conflicts
- ✅ Thread-safe from any context
- ✅ Works reliably with OPENRNDR, Processing, etc.
- ⚠️ Slower depth processing (~30 Hz)

**OpenGL Pipeline**:
- ✅ GPU-accelerated (~296 Hz)
- ⚠️ May conflict with framework GL contexts
- ⚠️ Requires main-thread initialization on macOS

For OPENRNDR applications, always use `PipelineType.CPU` to avoid conflicts.

### Frame Specifications

| Camera | Resolution | Format | ColorBuffer Type |
|--------|-----------|--------|------------------|
| Depth  | 512x424   | 16-bit depth (mm) | R/FLOAT16 |
| Color  | 1920x1080 | BGRX | RGB/UINT8 |
| IR     | 512x424   | 16-bit intensity | R/FLOAT16 |

### Examples

See `kinect-openrndr/src/main/kotlin/org/openrndr/kinect2/examples/`:
- `Kinect2DepthExample.kt` - Minimal depth camera example
- `Kinect2Example.kt` - Full multi-stream example

## Development

### Building Individual Modules

```bash
# JNI layer only
cd kinect-jni
mvn clean compile

# Kotlin API only
cd kinect-core
mvn clean compile

# Sample app only
cd kinect-app
mvn clean package
```

### Debugging Native Code

```bash
# Enable JNI verbose output
java -verbose:jni -Djava.library.path=... com.kinect.jni.FrameCaptureDemo

# Check native library symbols
nm -g kinect-jni/target/libkinect-jni.dylib | grep Java

# Debug with lldb
lldb -- java -Djava.library.path=... com.kinect.jni.FrameCaptureDemo
```

## Known Limitations

- **macOS Only**: Current build configured for macOS (Linux/Windows support requires build config changes)
- **No Skeletal Tracking**: libfreenect2 doesn't provide this (requires separate ML framework)
- **No Audio**: Kinect V2 microphone array not supported by libfreenect2 on macOS
- **Single Device**: Multi-Kinect support not implemented

## References

- **libfreenect2**: https://github.com/OpenKinect/libfreenect2
- **Kinect V2 Specifications**: https://developer.microsoft.com/en-us/windows/kinect/hardware-setup
- **GLFW**: https://www.glfw.org/
- **JNI Specification**: https://docs.oracle.com/javase/8/docs/technotes/guides/jni/

## License

[Your license here]

## Contributing

[Your contribution guidelines here]

## Support

For issues and questions:
- Check the [Troubleshooting](#troubleshooting) section
- Review `docs/main-thread-opengl-solution.md` for technical details
- Open an issue on GitHub

---

**Status**: ✅ Fully functional with main-thread OpenGL solution + OPENRNDR integration
**Last Updated**: November 2025

**Modules**:
- ✅ kinect-jni: JNI bindings with pipeline selection (CPU/OpenGL)
- ✅ kinect-core: Kotlin high-level API
- ✅ kinect-openrndr: OPENRNDR extension with reactive streams
- ✅ kinect-app: Sample application
