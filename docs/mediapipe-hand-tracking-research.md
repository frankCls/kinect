# MediaPipe Hand Tracking Integration Research
**Date:** April 13, 2026  
**Target:** Kotlin/JVM Desktop + JavaCV + macOS Apple Silicon

---

## Executive Summary

**Critical Finding:** MediaPipe Java API is **Android-only**. No official desktop JVM support exists.

**Recommended Approach:** OpenCV DNN (JavaCV) + ONNX hand model  
**Estimated Effort:** 8-16 hours (MVP)  
**Expected Performance:** 10-20 FPS on M2 CPU  

---

## 1. MediaPipe JVM Availability

### ❌ Desktop Java NOT Supported
- MediaPipe Tasks Java API requires Android SDK API 24+
- All official samples: Android/Python/Web only
- No desktop examples in `google-ai-edge/mediapipe-samples`
- Build system exclusively Android Gradle

### ✅ What IS Available
- **Python:** Full desktop support with GPU acceleration (Metal on macOS)
- **C++:** Desktop support but requires Bazel build + custom integration
- **Web:** JavaScript/WASM for browser

---

## 2. Integration Options Ranked

| Approach | Effort | Performance | Deployment | Recommendation |
|----------|--------|-------------|------------|----------------|
| **OpenCV DNN + ONNX** | 8-16h | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ✅ **START HERE** |
| MediaPipe Python + IPC | 2-4h | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⚠️ If speed critical |
| ONNX Runtime Java | 20-30h | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⚠️ If OpenCV fails |
| TFLite Java | 20-40h | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⚠️ Complex pipeline |
| MediaPipe C++ + JNI | 40-80h | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ❌ Too complex |

---

## 3. Recommended Implementation (Option A)

### Phase 1: MVP with OpenCV DNN (8 hours)

**Dependencies:**
```xml
<!-- Already in project via JavaCV -->
<dependency>
    <groupId>org.bytedeco</groupId>
    <artifactId>opencv-platform</artifactId>
    <version>4.13.0-1.5.13</version>
</dependency>
```

**Steps:**
1. Download ONNX hand landmark model (see Model Options below)
2. Load with OpenCV DNN: `Dnn.readNetFromONNX(modelPath)`
3. Preprocess Kinect RGB frame → 224x224 blob
4. Run inference: `net.forward()`
5. Parse output → 21 hand landmarks
6. Map coordinates RGB → Kinect depth space

**Code Skeleton:**
```kotlin
class HandTracker(modelPath: String) {
    private val net = Dnn.readNetFromONNX(modelPath)
    
    fun detectHands(rgbFrame: Frame): List<HandLandmarks> {
        val mat = converter.convert(rgbFrame)
        
        // Preprocess: resize + normalize
        val blob = Dnn.blobFromImage(
            mat, 
            1.0/255.0,           // Scale pixels to [0,1]
            Size(224, 224),      // Input size
            Scalar.all(0.0),     // Mean subtraction
            true,                // Swap RGB→BGR
            false                // Don't crop
        )
        
        net.setInput(blob)
        val output = net.forward()
        
        return parseHandLandmarks(output, mat.cols(), mat.rows())
    }
}

data class HandLandmarks(
    val landmarks: List<Point3f>, // 21 points (x,y,z)
    val confidence: Float
)
```

---

## 4. Hand Landmark Models

### MediaPipe Official Model (❌ Not directly usable)
- **URL:** `https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task`
- **Size:** 7.8 MB
- **Format:** `.task` (TensorFlow Lite + metadata, proprietary)
- **License:** Apache 2.0 ✅ Commercial use OK
- **Problem:** Two-stage pipeline (palm detector → hand landmarks) + custom format

### Alternative ONNX Models (✅ Use These)

**Option 1: Hand Tracking from ONNX Model Zoo**
- Search: `https://github.com/onnx/models` → "hand"
- Look for: Single-stage hand pose estimation
- Typical size: 5-15 MB
- Input: 224x224 RGB
- Output: 21 landmarks (x,y,z normalized)

**Option 2: MediaPipe Converted to ONNX**
- Community conversions may exist on GitHub
- Search: "mediapipe hand onnx"
- Verify model works before heavy integration

**Option 3: MobileNet Hand Pose**
- Lighter weight (~3-5 MB)
- Lower accuracy but faster inference
- Good for MVP

---

## 5. Coordinate Mapping: RGB → Depth

MediaPipe outputs normalized coordinates `[0, 1]` in RGB space (1920x1080).  
Kinect depth is 512x424 with different FOV.

### Step 1: Denormalize
```kotlin
val pixelX = normalizedX * 1920  // RGB width
val pixelY = normalizedY * 1080  // RGB height
```

### Step 2: Simple Proportional Mapping (Quick Start)
```kotlin
val depthX = (pixelX / 1920.0 * 512.0).toInt()
val depthY = (pixelY / 1080.0 * 424.0).toInt()
```
⚠️ **Warning:** This assumes same FOV/alignment. Kinect V2 RGB ≠ depth FOV!

### Step 3: Proper Calibration (Production)
1. Use Kinect's `getRegistration()` API (if libfreenect2 exposes it)
2. Or manual calibration:
   - Print checkerboard pattern
   - Capture with both RGB and depth
   - Compute homography matrix
   - Apply transform: `cv::perspectiveTransform()`

**Recommended for MVP:** Use Step 2 initially, iterate to Step 3 if accuracy matters.

---

## 6. Performance Expectations (macOS M2)

| Implementation | FPS | Latency | GPU |
|----------------|-----|---------|-----|
| MediaPipe Python (native) | 30-60 | ~16ms | Metal ✅ |
| OpenCV DNN (CPU) | 10-20 | ~50-100ms | ❌ |
| ONNX Runtime (CPU) | 20-30 | ~33-50ms | ❌ |
| TFLite Java (CPU) | 15-25 | ~40-60ms | ❌ |

**Note:** M2 GPU acceleration not available in Java/JVM options (no Metal/CoreML bindings in JavaCV/ONNX Runtime Java).

**Optimization Tips:**
- Downsample Kinect RGB: 1920x1080 → 960x540 before inference
- Run hand tracking every 2-3 frames, interpolate between
- Use lower-res Kinect mode if 30 FPS not needed

---

## 7. Alternative: Python Subprocess Approach

If 10-20 FPS isn't acceptable, consider running MediaPipe in Python subprocess:

**Architecture:**
```
Kotlin/JVM (Kinect) 
    ↓ (write frame to shared memory or pipe)
Python Process (MediaPipe) 
    ↓ (return landmarks via JSON/protobuf)
Kotlin/JVM (render + depth mapping)
```

**Effort:** 2-4 hours  
**Performance:** 30-60 FPS with GPU acceleration  
**Deployment:** Requires Python 3.8+ + mediapipe package  

**Communication Options:**
1. **Named pipes (simplest):**
   ```kotlin
   ProcessBuilder("python3", "hand_tracker.py").start()
   BufferedReader(FileReader("/tmp/hand_landmarks.json"))
   ```

2. **HTTP/gRPC (most robust):**
   - Python: Flask/FastAPI server
   - Kotlin: HTTP client
   - ~5ms overhead per request

3. **Shared memory (fastest):**
   - Use `java.nio.MappedByteBuffer`
   - Python: `mmap` module
   - Complex but <1ms overhead

---

## 8. Next Steps

### Immediate (Week 1):
1. ✅ Research complete (this document)
2. ⬜ Find suitable ONNX hand model (search GitHub/ONNX Zoo)
3. ⬜ Prototype OpenCV DNN loading in separate Kotlin file
4. ⬜ Test inference on static image first

### MVP (Week 2):
1. ⬜ Integrate with Kinect RGB stream
2. ⬜ Implement coordinate mapping (simple version)
3. ⬜ Render landmarks on OPENRNDR canvas
4. ⬜ Measure FPS + latency

### Production (Week 3+):
1. ⬜ Optimize performance (downsampling, frame skipping)
2. ⬜ Proper RGB→depth calibration
3. ⬜ Handle multiple hands (if model supports)
4. ⬜ Smoothing/filtering (Kalman filter for jitter)

---

## 9. References

- **MediaPipe GitHub:** https://github.com/google-ai-edge/mediapipe
- **MediaPipe Samples:** https://github.com/google-ai-edge/mediapipe-samples
- **JavaCV:** https://github.com/bytedeco/javacv
- **ONNX Model Zoo:** https://github.com/onnx/models
- **OpenCV DNN Tutorial:** https://docs.opencv.org/4.x/d2/d58/tutorial_table_of_content_dnn.html

---

## 10. Decision Matrix

**Choose OpenCV DNN + ONNX if:**
- ✅ Pure JVM/Kotlin solution preferred
- ✅ Already using JavaCV for Kinect
- ✅ 10-20 FPS acceptable
- ✅ Simple deployment (no Python dependency)

**Choose Python Subprocess if:**
- ✅ Need 30+ FPS
- ✅ GPU acceleration required
- ✅ Official MediaPipe features needed (tracking smoothness)
- ⚠️ Can accept Python runtime dependency

**Avoid MediaPipe C++ + JNI unless:**
- ❌ OpenCV DNN completely fails
- ❌ Python approach rejected
- ✅ Have 40+ hours for custom JNI development
