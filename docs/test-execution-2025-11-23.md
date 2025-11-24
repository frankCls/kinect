# Test Execution Report: Kinect JNI Surefire Configuration with systemPropertyVariables

**Date**: November 23, 2025  
**Module**: kinect-jni  
**Build Tool**: Apache Maven 3.6.3  
**Java Version**: 17.0.9-amzn (Amazon Corretto)  
**Platform**: macOS 15.7.1 (M2 Pro ARM64)  

## Test Configuration Summary

### Surefire Plugin Configuration

The following Surefire configuration was applied in `/Users/frank.claes/dev-private/kinect/kinect-jni/pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <java.library.path>${project.build.directory}${path.separator}${freenect2.lib}</java.library.path>
        </systemPropertyVariables>
        <argLine>-Djava.library.path=${project.build.directory}${path.separator}${freenect2.lib}</argLine>
    </configuration>
</plugin>
```

### Property Resolution

Maven resolved the following properties:

- `project.build.directory`: `/Users/frank.claes/dev-private/kinect/kinect-jni/target`
- `freenect2.lib`: `/Users/frank.claes/freenect2/lib` (from parent pom.xml)
- `path.separator`: `:` (colon on macOS)

### Final java.library.path Configuration

**Value set by Surefire**:
```
/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib
```

**JVM Arguments passed to forked process**:
```
-Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib
```

**Surefire Fork Command**:
```bash
/bin/sh -c cd '/Users/frank.claes/dev-private/kinect/kinect-jni' && \
  '/Users/frank.claes/.sdkman/candidates/java/17.0.9-amzn/bin/java' \
  '-Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib' \
  '-jar' '/Users/frank.claes/dev-private/kinect/kinect-jni/target/surefire/surefirebooter-20251123171036689_3.jar'
```

## Build Execution Summary

### Maven Command Executed

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk env && mvn -B test -pl kinect-jni
```

### Build Lifecycle

1. **maven-resources-plugin**: No resources to copy
2. **maven-compiler-plugin**: Java classes compiled (nothing new, all up to date)
3. **maven-antrun-plugin**: Native C++ compilation executed
   - Created `/Users/frank.claes/dev-private/kinect/kinect-jni/target/objs/` directory
   - Compiled `Freenect2JNI.cpp` to object file
   - Linked object file into dynamic library
   - Fixed rpath for libfreenect2 dependency
4. **maven-resources-plugin** (test): No test resources
5. **maven-compiler-plugin** (test): Test classes compiled
6. **maven-surefire-plugin**: Test execution in forked JVM

### Native Library Artifact

Native library successfully compiled:

```
File: /Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib
Size: 122 KB
Permissions: -rwxr-xr-x
Timestamp: 2025-11-23 17:10
```

### Library Dependencies (otool Output)

The native library has correct dependencies configured:

```
/Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib:
  @rpath/libkinect-jni.dylib (self-reference)
  /Users/frank.claes/freenect2/lib/libfreenect2.0.2.dylib (compatibility version 0.2.0)
  /opt/homebrew/opt/libusb/lib/libusb-1.0.0.dylib (compatibility version 5.0.0)
  /usr/lib/libc++.1.dylib (compatibility version 1.0.0)
  /usr/lib/libSystem.B.dylib (compatibility version 1.0.0)
```

## Test Execution Results

### Test Suite: FrameCaptureTest

- **Framework**: JUnit 4
- **Test Count**: 3
- **Total Tests Run**: 3
- **Failures**: 0
- **Errors**: 3
- **Skipped**: 0
- **Elapsed Time**: 0.056 seconds

### Test Results Details

#### 1. testDeviceEnumeration (FAILED - UnsatisfiedLinkError)

```
Status: ERROR
Elapsed Time: 0 seconds
Location: FrameCaptureTest.java:20

Exception: java.lang.UnsatisfiedLinkError: 'long com.kinect.jni.FreenectContext.nativeCreateContext()'

Stack Trace:
  at com.kinect.jni.FreenectContext.nativeCreateContext(Native Method)
  at com.kinect.jni.FreenectContext.<init>(FreenectContext.java:35)
  at com.kinect.jni.Freenect.createContext(Freenect.java:60)
  at com.kinect.jni.FrameCaptureTest.testDeviceEnumeration(FrameCaptureTest.java:20)
```

#### 2. testFrameCapture (FAILED - UnsatisfiedLinkError)

```
Status: ERROR
Elapsed Time: 0.001 seconds
Location: FrameCaptureTest.java:44

Exception: java.lang.UnsatisfiedLinkError: 'long com.kinect.jni.FreenectContext.nativeCreateContext()'

Stack Trace:
  at com.kinect.jni.FreenectContext.nativeCreateContext(Native Method)
  at com.kinect.jni.FreenectContext.<init>(FreenectContext.java:35)
  at com.kinect.jni.Freenect.createContext(Freenect.java:60)
  at com.kinect.jni.FrameCaptureTest.testFrameCapture(FrameCaptureTest.java:44)
```

#### 3. testMultipleFrameCapture (FAILED - UnsatisfiedLinkError)

```
Status: ERROR
Elapsed Time: 0.034 seconds
Location: FrameCaptureTest.java:144

Exception: java.lang.UnsatisfiedLinkError: 'long com.kinect.jni.FreenectContext.nativeCreateContext()'

Stack Trace:
  at com.kinect.jni.FreenectContext.nativeCreateContext(Native Method)
  at com.kinect.jni.FreenectContext.<init>(FreenectContext.java:35)
  at com.kinect.jni.Freenect.createContext(Freenect.java:60)
  at com.kinect.jni.FrameCaptureTest.testMultipleFrameCapture(FrameCaptureTest.java:144)
```

### Overall Build Result

```
Status: FAILURE
Total Time: 1.185 seconds
Exit Code: 1
Build Summary: BUILD FAILURE
```

## Surefire Configuration Validation

### Configuration Applied Successfully

The Surefire configuration with `systemPropertyVariables` was correctly applied:

1. **systemPropertyVariables Method**: ✓ WORKING
   - Property name: `java.library.path`
   - Property value: `/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib`
   - Method: Set as system property for the forked test JVM

2. **argLine Method**: ✓ WORKING
   - JVM argument: `-Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib`
   - Method: Passed as direct JVM argument to forked process

3. **Property Interpolation**: ✓ WORKING
   - `${project.build.directory}` resolved correctly to `/Users/frank.claes/dev-private/kinect/kinect-jni/target`
   - `${freenect2.lib}` resolved correctly to `/Users/frank.claes/freenect2/lib`
   - `${path.separator}` resolved correctly to `:`

### Verification from Maven Debug Output

From `mvn -X` debug logging, confirmed property values:

```
[DEBUG] Setting project property: freenect2.lib -> /Users/frank.claes/freenect2/lib
[DEBUG] Setting project property: freenect2.home -> /Users/frank.claes/freenect2
[DEBUG] Setting project property: freenect2.include -> /Users/frank.claes/freenect2/include

[DEBUG]   (s) argLine = -Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib
[DEBUG]   (s) systemPropertyVariables = {java.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib}

[DEBUG] Setting system property [java.library.path]=[/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib]

[DEBUG] Forking command line: /bin/sh -c cd '/Users/frank.claes/dev-private/kinect/kinect-jni' && 
  '/Users/frank.claes/.sdkman/candidates/java/17.0.9-amzn/bin/java' 
  '-Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target:/Users/frank.claes/freenect2/lib' ...
```

## Root Cause Analysis

### Issue: UnsatisfiedLinkError for nativeCreateContext()

The Surefire configuration correctly sets `java.library.path` and the native library file exists. However, JVM cannot find the JNI method `nativeCreateContext()` in the loaded native library.

### Diagnosis

1. **Library Loading**: The native library `libkinect-jni.dylib` is successfully located and loaded by the JVM in the correct search path.

2. **JNI Symbol Resolution**: The JVM cannot find the expected JNI symbol `Java_com_kinect_jni_FreenectContext_nativeCreateContext` inside the loaded library.

### Possible Root Causes

1. **Missing JNI Implementation**: The native C++ code may not export the required JNI methods
2. **Symbol Name Mismatch**: The compiled C++ code may use a different symbol name pattern
3. **Compilation Issue**: The native library may have been compiled without including the JNI implementation file
4. **Architecture Mismatch**: Although unlikely, ARM64 architecture compilation may have issues
5. **Library Stripping**: The shared library may have been stripped of debug symbols

## Recommendations for Debugging

### 1. Verify Native Library Symbols

```bash
# List all exported symbols in the native library
nm /Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib | grep -i context

# Search for expected JNI method name pattern
nm /Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib | \
  grep Java_com_kinect_jni_FreenectContext_nativeCreateContext

# List all symbols (may be verbose)
nm -a /Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib
```

### 2. Inspect C++ Source Implementation

```bash
# Check if JNI method is defined
grep -n "nativeCreateContext" \
  /Users/frank.claes/dev-private/kinect/kinect-jni/src/main/cpp/Freenect2JNI.cpp

# Look for JNIEXPORT declarations
grep -n "JNIEXPORT" \
  /Users/frank.claes/dev-private/kinect/kinect-jni/src/main/cpp/Freenect2JNI.cpp
```

### 3. Check Library Load Mechanism

```bash
# Test if Java can load the library
java -Djava.library.path=/Users/frank.claes/dev-private/kinect/kinect-jni/target \
  -Djdk.debug=modules -cp /Users/frank.claes/dev-private/kinect/kinect-jni/target/classes \
  -c "System.loadLibrary(\"kinect-jni\"); System.out.println(\"Library loaded\");"
```

### 4. Verify Architecture Match

```bash
# Check native library architecture
file /Users/frank.claes/dev-private/kinect/kinect-jni/target/libkinect-jni.dylib

# Verify JVM architecture
java -version

# Check system architecture
uname -m
```

## Conclusion

The Surefire configuration with `systemPropertyVariables` is correctly implemented and properly passes the `java.library.path` to the forked test JVM. The native library is successfully compiled and placed in the correct location. However, the tests fail due to JNI method lookup errors, indicating the issue is not with the Surefire/java.library.path configuration, but rather with the native library compilation or JNI symbol resolution.

The next step is to inspect the actual JNI symbols exported by the compiled native library using the debugging steps above to determine why the expected JNI methods are not present.

## Test Run 3: OpenCL Pipeline Fix Verification (30s timeout)

### Command
```bash
mvn clean test -Dtest=FrameCaptureTest#testMultipleFrameCapture
```

### Results
- **Status**: TIMEOUT (30 seconds)
- **Error**: `test timed out after 30000 milliseconds`
- **Stack Trace**: Timeout occurs at `nativeOpenDevice()` call
- **Build**: SUCCESS (compilation passed, native code compiled)
- **OpenCL Detection**: Dylib contains OpenCL pipeline constructor reference

### Analysis
The C++ code WAS compiled with OpenCL pipeline creation:
- `Freenect2JNI.o` contains the updated code
- `libkinect-jni.dylib` has OpenCL symbols (verified via nm)
- The pipeline is being instantiated in the code

However, the Java test still times out during `device.open()`, which calls `nativeOpenDevice()`.

### Possible Issues
1. **libfreenect2 Runtime**: The OpenCLPacketPipeline constructor might be hanging on macOS
2. **Kinect Hardware**: Device not responding or USB connection unstable
3. **libfreenect2 Not Linked**: Despite code changes, libfreenect2 might not be linked correctly to the dylib
4. **Driver Issue**: libusb or libfreenect2 driver itself hanging during initialization

### Next Investigation Steps
1. Run Protonect directly to verify libfreenect2 is working
2. Check dylib linkage: `otool -L libkinect-jni.dylib` to verify libfreenect2.dylib is linked
3. Add debug logging to C++ code to trace execution flow
4. Test with CPU pipeline instead of OpenCL to isolate the issue


## Test Run 4: Debug Logging - Root Cause Identified

### Command
```bash
mvn clean test -Dtest=FrameCaptureTest#testMultipleFrameCapture
```

### Key Finding: BLOCKING IS IN OpenCL PIPELINE CONSTRUCTOR
The debug output shows:
```
[JNI] nativeOpenDevice: Attempting to open device (serial=230921433847)
[JNI] Creating OpenCL pipeline...
[HANGS HERE - TEST TIMEOUT at 30 seconds]
```

The test times out while executing: `new libfreenect2::OpenCLPacketPipeline()`

### Root Cause Analysis
The OpenCL pipeline constructor is blocking indefinitely on macOS/ARM64. This is NOT a device issue - the pipeline initialization itself is the culprit. The OpenCLPacketPipeline initialization may:
1. Attempt to initialize OpenCL devices
2. Hang waiting for OpenCL resources
3. Have a platform-specific deadlock on ARM64 macOS

### Solution: Use CPU Pipeline as Default
Since the OpenCL pipeline is blocking, we should:
1. Remove OpenCL pipeline usage
2. Use CPU pipeline instead (still performant enough for initial testing)
3. Or wrap OpenCL initialization with a timeout

### Next Step: Switch to CPU Pipeline
Replace OpenCL with CPU pipeline and retest.

