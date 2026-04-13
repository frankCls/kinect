# AGENTS.md

Focused guidance for working with this Kinect V2 JNI integration project.

## Build System

**Maven multi-module project** (4 modules):
- `kinect-jni` - Java/JNI interface + C++ native code (compiled to `.dylib`)
- `kinect-core` - Kotlin high-level API
- `kinect-openrndr` - OPENRNDR extension for creative coding
- `kinect-app` - Demo application

**Module build order matters**. Always build from root or use `-pl` carefully:

```bash
# From root: builds all modules in dependency order
mvn clean install

# Individual module (ensure dependencies built first)
cd kinect-jni && mvn clean install
cd kinect-core && mvn clean install
```

**Native compilation**: `kinect-jni` uses `maven-antrun-plugin` (not `native-maven-plugin`) to invoke `clang++` directly. C++ code in `kinect-jni/src/main/cpp/` compiles to `kinect-jni/target/libkinect-jni.dylib`.

## Critical Runtime Requirements

**Before any development or testing**:

1. **libfreenect2 must be installed at `~/freenect2`** (not Homebrew location)
   - Verify: `ls ~/freenect2/lib/libfreenect2.dylib`
   - If missing, see `README.md` Step 2 for compilation instructions

2. **Protonect must work** before anything else will
   ```bash
   ~/freenect2/bin/Protonect
   ```
   - Should enumerate Kinect V2 device and show live streams
   - If this fails, stop and fix hardware/libfreenect2 setup first

3. **Java library path must include both locations**:
   ```bash
   -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib
   ```
   Both paths required: JNI lib AND libfreenect2 dependency

## Running Code

**Use shell scripts, not raw Maven**:

```bash
# Demo app (kinect-jni)
cd kinect-jni
./run-demo.sh                    # Console output with OpenGL pipeline
./run-demo.sh --pipeline CPU     # Safe for framework integration
./run-demo.sh --gui              # Visual window (uses CPU pipeline auto)

# OPENRNDR examples
cd kinect-openrndr
./run-example.sh depth           # Depth camera
./run-example.sh full            # All three streams
./run-example.sh pointcloud      # 3D point cloud
./run-example.sh mesh            # 3D mesh rendering
```

**Why scripts?**
- Handle `-Djava.library.path` correctly
- `run-demo.sh`: Uses `-XstartOnFirstThread` (required for OpenGL pipeline on macOS)
- `run-example.sh`: Uses `mvn exec:exec` with proper JVM args for OPENRNDR

## macOS-Specific OpenGL Constraint

**Main-thread requirement**: GLFW/OpenGL operations must execute on macOS main dispatch queue.

**Implementation**:
- C++ code uses `dispatch_sync(dispatch_get_main_queue(), ^{ ... })` for pipeline creation
- See `docs/main-thread-opengl-solution.md` for technical details
- **Do not refactor this** without understanding the macOS threading constraint

**Pipeline selection**:
- `PipelineType.OPENGL`: Fast (~296Hz), uses main-thread dispatch, default for standalone apps
- `PipelineType.CPU`: Slower (~30Hz), thread-safe, **required for OPENRNDR** to avoid GL context conflicts

## Module Dependencies

**kinect-core depends on kinect-jni**:
```xml
<dependency>
    <groupId>com.kinect</groupId>
    <artifactId>kinect-jni</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**kinect-openrndr depends on kinect-core**. Always build kinect-jni first, then kinect-core, then kinect-openrndr.

## Testing Quirks

**Hardware-dependent tests** are marked `@Ignore` by default (allows headless CI).

To run with hardware:
1. Connect Kinect V2 via USB 3.0
2. Remove `@Ignore` annotations
3. Run in GUI session (not SSH)
4. Use `mvn test -pl kinect-jni` for specific module

**No hardware mocking** - tests are integration tests requiring real device.

## Common Build Failures

**"cannot find libfreenect2"**:
- Verify `$HOME/freenect2/lib/libfreenect2.dylib` exists
- Check `pom.xml` property `<freenect2.home>${env.HOME}/freenect2</freenect2.home>`
- Rebuild libfreenect2 if needed (see `README.md` Step 2)

**"UnsatisfiedLinkError: no kinect-jni in java.library.path"**:
- Ensure `libkinect-jni.dylib` exists: `ls kinect-jni/target/libkinect-jni.dylib`
- Check Java command includes `-Djava.library.path=kinect-jni/target:$HOME/freenect2/lib`
- Use shell scripts to avoid manual path management

**"dyld: Library not loaded: libfreenect2.0.2.dylib"**:
- Runtime can't find libfreenect2 dependency
- Add `$HOME/freenect2/lib` to `-Djava.library.path`
- Or fix rpath: `install_name_tool -add_rpath $HOME/freenect2/lib kinect-jni/target/libkinect-jni.dylib`

## Architecture Notes

**Data flow**:
```
Kinect V2 Hardware → libfreenect2.dylib (C++) → libkinect-jni.dylib (JNI) 
    → kinect-jni JAR (Java) → kinect-core JAR (Kotlin) → Application
```

**Zero-copy frame data**: Uses `ByteBuffer.allocateDirect()` in JNI layer. Never convert entire depth/color arrays to JVM heap objects in hot paths.

**Stream specs**:
- Color: 1920x1080, BGRX, 4 bytes/pixel, 30 FPS
- Depth: 512x424, 16-bit mm, 4 bytes/pixel, 30 FPS
- IR: 512x424, 16-bit intensity, 4 bytes/pixel, 30 FPS

## Logging

**C++ (JNI layer)**: Set `KINECT_JNI_LOG_LEVEL` env var
- `0` = ERROR only
- `1` = INFO (default, recommended)
- `2` = DEBUG (frame diagnostics)
- `3` = TRACE (verbose, includes pixel data)

**Kotlin**: Edit `kinect-openrndr/src/main/resources/simplelogger.properties`

**Production**: `KINECT_JNI_LOG_LEVEL=1` + `simplelogger.properties: defaultLogLevel=info`

## Development Conventions

**CLAUDE.md instructs**:
- Document all work (successes AND failures) in `docs/log.md`
- Use "local-maven-runner agent" for Maven commands (project-specific convention)

**Frame lifecycle**: Always call `frame.close()` when done with frames to prevent memory leaks in C++.

**Hardware limitations**:
- libfreenect2 does NOT provide skeletal tracking, face tracking, or audio
- USB 3.0 required (preferably Intel/NEC controllers, avoid ASMedia/hubs)
- macOS only (current build config)

## Key Files to Understand

**Build config**:
- `pom.xml` - Parent POM with module declarations and shared properties
- `kinect-jni/pom.xml` - Native compilation via maven-antrun-plugin, clang++ invocation

**JNI implementation**:
- `kinect-jni/src/main/cpp/Freenect2JNI.cpp` - C++ implementation with main-thread GCD

**Scripts**:
- `kinect-jni/run-demo.sh` - Demo app runner with `-XstartOnFirstThread`
- `kinect-openrndr/run-example.sh` - OPENRNDR example runner
- `build-and-test.sh` - Quick build for kinect-jni + kinect-openrndr

**Docs**:
- `docs/main-thread-opengl-solution.md` - macOS threading solution (read before modifying)
- `docs/log.md` - Development history and troubleshooting results
- `CLAUDE.md` - Comprehensive development guide for Claude Code

## What NOT to Do

- **Don't** run tests without hardware connected (they'll fail)
- **Don't** skip libfreenect2 verification (Protonect test) before debugging Java code
- **Don't** remove `-XstartOnFirstThread` from run-demo.sh (breaks OpenGL pipeline)
- **Don't** use OPENGL pipeline with OPENRNDR (GL context conflict - use CPU pipeline)
- **Don't** assume standard Maven conventions (native compilation is custom via maven-antrun-plugin)
- **Don't** forget both library paths: JNI lib location AND libfreenect2 location
