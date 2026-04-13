#!/usr/bin/env python3
"""
MediaPipe Hand Tracker Service for Air Drums.

Reads raw BGRX frames from stdin, runs MediaPipe hand detection,
and writes JSON hand landmark results to stdout.

Uses the MediaPipe Tasks API (HandLandmarker) which requires a downloaded
model file (hand_landmarker.task). The setup_python.sh script handles this.

Protocol:
  Input (stdin):  4-byte frame width (LE uint32)
                  4-byte frame height (LE uint32)
                  width * height * 4 bytes of BGRX pixel data
  Output (stdout): One JSON line per frame:
                   {"hands": [{"handedness": "Left"|"Right", "landmarks": [[x,y,z], ...], "score": 0.95}]}

The service runs in a loop until stdin is closed or it receives a SIGTERM.
"""

import sys
import os
import struct
import json
import signal
import numpy as np

# Attempt to import mediapipe; exit with clear error if missing
try:
    import mediapipe as mp
    from mediapipe.tasks.python import vision
    from mediapipe.tasks.python.core import base_options as base_options_module
except ImportError as e:
    print(json.dumps({"error": f"mediapipe import failed: {e}. Run: pip install mediapipe"}),
          file=sys.stdout, flush=True)
    sys.exit(1)


def find_model_path():
    """Find the hand_landmarker.task model file."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(script_dir, "hand_landmarker.task"),
        os.path.join(script_dir, "models", "hand_landmarker.task"),
    ]
    for path in candidates:
        if os.path.exists(path):
            return path
    return None


def setup_hand_tracker(model_path):
    """Initialize MediaPipe HandLandmarker using Tasks API."""
    base_options = base_options_module.BaseOptions(
        model_asset_path=model_path
    )
    options = vision.HandLandmarkerOptions(
        base_options=base_options,
        running_mode=vision.RunningMode.IMAGE,  # Process individual frames
        num_hands=2,
        min_hand_detection_confidence=0.5,
        min_hand_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    return vision.HandLandmarker.create_from_options(options)


def read_exact(stream, n):
    """Read exactly n bytes from a binary stream."""
    data = b""
    while len(data) < n:
        chunk = stream.read(n - len(data))
        if not chunk:
            return None  # EOF
        data += chunk
    return data


def process_frame(landmarker, frame_rgb):
    """Run MediaPipe hand detection on an RGB frame.

    Returns list of detected hands with landmarks.
    """
    # Create MediaPipe Image from numpy array
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame_rgb)

    # Run detection
    result = landmarker.detect(mp_image)

    detected_hands = []

    if result.hand_landmarks and result.handedness:
        for hand_landmarks, handedness_list in zip(
            result.hand_landmarks, result.handedness
        ):
            # Get handedness label and score
            category = handedness_list[0]
            label = category.category_name  # "Left" or "Right"
            score = category.score

            # Note: MediaPipe mirrors handedness (designed for selfie/webcam).
            # Since Kinect faces the user (non-mirrored), we need to flip:
            # MediaPipe "Left" = actually user's Right hand (from Kinect's POV)
            # We flip here so Kotlin receives correct handedness.
            flipped_label = "Right" if label == "Left" else "Left"

            # Extract 21 landmarks as [x, y, z] normalized coordinates
            landmarks = []
            for lm in hand_landmarks:
                landmarks.append([
                    round(lm.x, 6),
                    round(lm.y, 6),
                    round(lm.z, 6)
                ])

            detected_hands.append({
                "handedness": flipped_label,
                "landmarks": landmarks,
                "score": round(score, 4)
            })

    return detected_hands


def main():
    """Main service loop: read frames from stdin, output landmarks to stdout."""
    # Graceful shutdown on SIGTERM
    running = True
    def handle_signal(signum, frame):
        nonlocal running
        running = False
    signal.signal(signal.SIGTERM, handle_signal)

    # Use binary mode for stdin
    stdin_bin = sys.stdin.buffer

    # Find model file
    model_path = find_model_path()
    if model_path is None:
        print(json.dumps({"error": "hand_landmarker.task model not found. Run setup_python.sh"}),
              flush=True)
        sys.exit(1)

    # Initialize hand landmarker
    try:
        landmarker = setup_hand_tracker(model_path)
    except Exception as e:
        print(json.dumps({"error": f"Failed to initialize HandLandmarker: {e}"}),
              flush=True)
        sys.exit(1)

    # Send ready signal
    print(json.dumps({"status": "ready", "version": "2.0"}), flush=True)

    frame_count = 0

    try:
        while running:
            # Read frame header: width (4 bytes LE) + height (4 bytes LE)
            header = read_exact(stdin_bin, 8)
            if header is None:
                break  # EOF - parent process closed pipe

            width, height = struct.unpack("<II", header)

            # Validate dimensions (sanity check)
            if width == 0 or height == 0 or width > 4096 or height > 4096:
                print(json.dumps({"error": f"invalid dimensions: {width}x{height}"}),
                      flush=True)
                continue

            # Read BGRX pixel data
            frame_size = width * height * 4
            frame_data = read_exact(stdin_bin, frame_size)
            if frame_data is None:
                break  # EOF

            # Convert BGRX to RGB numpy array
            frame_bgrx = np.frombuffer(frame_data, dtype=np.uint8).reshape(height, width, 4)
            frame_rgb = frame_bgrx[:, :, :3][:, :, ::-1].copy()  # BGRX -> BGR -> RGB

            # Run hand detection
            detected_hands = process_frame(landmarker, frame_rgb)

            # Output result as JSON line
            result = {
                "frame": frame_count,
                "hands": detected_hands
            }
            print(json.dumps(result), flush=True)

            frame_count += 1

    except (BrokenPipeError, IOError):
        pass  # Parent process closed connection
    except KeyboardInterrupt:
        pass
    finally:
        landmarker.close()
        # Send shutdown signal
        try:
            print(json.dumps({"status": "shutdown"}), flush=True)
        except (BrokenPipeError, IOError):
            pass


if __name__ == "__main__":
    main()
