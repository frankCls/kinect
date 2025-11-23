package com.kinect.jni;

import org.junit.Test;
import org.junit.Ignore;
import static org.junit.Assert.*;

/**
 * Test frame capture functionality.
 *
 * NOTE: This test requires a physical Kinect V2 device to be connected.
 * If no device is present, the test will be skipped.
 */
public class FrameCaptureTest {

    @Test
    public void testDeviceEnumeration() {
        System.out.println("=== Testing Device Enumeration ===");

        assertTrue("Native library should be loaded", Freenect.isLibraryLoaded());

        try (FreenectContext context = Freenect.createContext()) {
            assertNotNull("Context should not be null", context);

            int deviceCount = context.getDeviceCount();
            System.out.println("Found " + deviceCount + " Kinect device(s)");

            if (deviceCount == 0) {
                System.out.println("SKIP: No Kinect device connected");
                return;
            }

            String serial = context.getDefaultDeviceSerial();
            System.out.println("Default device serial: " + serial);
            assertNotNull("Device serial should not be null", serial);

        } catch (Exception e) {
            fail("Device enumeration failed: " + e.getMessage());
        }
    }

    @Test(timeout = 60000) // 60 second timeout for full frame capture test
    @Ignore("Requires physical Kinect device and display context for OpenGL - Maven runs headless. Run as standalone Java app instead.")
    public void testFrameCapture() {
        System.out.println("\n=== Testing Frame Capture ===");

        try (FreenectContext context = Freenect.createContext()) {
            int deviceCount = context.getDeviceCount();

            if (deviceCount == 0) {
                System.out.println("SKIP: No Kinect device connected");
                return;
            }

            System.out.println("Opening device...");
            try (KinectDevice device = context.openDefaultDevice()) {
                assertNotNull("Device should not be null", device);

                String firmware = device.getFirmwareVersion();
                System.out.println("Firmware version: " + firmware);
                assertNotNull("Firmware should not be null", firmware);

                System.out.println("Starting streaming...");
                device.start();
                assertTrue("Device should be streaming", device.isStreaming());

                // Capture color frame
                System.out.println("Capturing COLOR frame...");
                Frame colorFrame = device.getNextFrame(FrameType.COLOR, 5000);
                if (colorFrame != null) {
                    System.out.println("  " + colorFrame);
                    assertEquals("Color frame should be 1920x1080", 1920, colorFrame.getWidth());
                    assertEquals("Color frame should be 1920x1080", 1080, colorFrame.getHeight());
                    assertEquals("Color frame should have 4 bytes per pixel", 4, colorFrame.getBytesPerPixel());

                    int expectedSize = 1920 * 1080 * 4;
                    assertEquals("Color frame data size should be correct", expectedSize, colorFrame.getDataSize());

                    assertNotNull("Frame data should not be null", colorFrame.getData());
                    assertEquals("ByteBuffer capacity should match data size", expectedSize, colorFrame.getData().capacity());

                    colorFrame.close();
                    System.out.println("  COLOR frame OK!");
                } else {
                    System.out.println("  Timeout waiting for COLOR frame");
                }

                // Capture depth frame
                System.out.println("Capturing DEPTH frame...");
                Frame depthFrame = device.getNextFrame(FrameType.DEPTH, 5000);
                if (depthFrame != null) {
                    System.out.println("  " + depthFrame);
                    assertEquals("Depth frame should be 512x424", 512, depthFrame.getWidth());
                    assertEquals("Depth frame should be 512x424", 424, depthFrame.getHeight());
                    assertEquals("Depth frame should have 4 bytes per pixel", 4, depthFrame.getBytesPerPixel());

                    int expectedSize = 512 * 424 * 4;
                    assertEquals("Depth frame data size should be correct", expectedSize, depthFrame.getDataSize());

                    assertNotNull("Frame data should not be null", depthFrame.getData());
                    assertEquals("ByteBuffer capacity should match data size", expectedSize, depthFrame.getData().capacity());

                    depthFrame.close();
                    System.out.println("  DEPTH frame OK!");
                } else {
                    System.out.println("  Timeout waiting for DEPTH frame");
                }

                // Capture IR frame
                System.out.println("Capturing IR frame...");
                Frame irFrame = device.getNextFrame(FrameType.IR, 5000);
                if (irFrame != null) {
                    System.out.println("  " + irFrame);
                    assertEquals("IR frame should be 512x424", 512, irFrame.getWidth());
                    assertEquals("IR frame should be 512x424", 424, irFrame.getHeight());
                    assertEquals("IR frame should have 4 bytes per pixel", 4, irFrame.getBytesPerPixel());

                    int expectedSize = 512 * 424 * 4;
                    assertEquals("IR frame data size should be correct", expectedSize, irFrame.getDataSize());

                    assertNotNull("Frame data should not be null", irFrame.getData());
                    assertEquals("ByteBuffer capacity should match data size", expectedSize, irFrame.getData().capacity());

                    irFrame.close();
                    System.out.println("  IR frame OK!");
                } else {
                    System.out.println("  Timeout waiting for IR frame");
                }

                System.out.println("Stopping streaming...");
                device.stop();
                assertFalse("Device should not be streaming", device.isStreaming());

                System.out.println("Frame capture test PASSED!");
            }

        } catch (Exception e) {
            e.printStackTrace();
            fail("Frame capture failed: " + e.getMessage());
        }
    }

    @Test(timeout = 120000) // 120 second timeout - device initialization can be slow
    @Ignore("Requires physical Kinect device and display context for OpenGL - Maven runs headless. Run as standalone Java app instead.")
    public void testMultipleFrameCapture() {
        System.out.println("\n=== Testing Multiple Frame Capture ===");

        try (FreenectContext context = Freenect.createContext()) {
            if (context.getDeviceCount() == 0) {
                System.out.println("SKIP: No Kinect device connected");
                return;
            }

            System.out.println("Opening device...");
            try (KinectDevice device = context.openDefaultDevice()) {
                System.out.println("Device opened successfully");
                System.out.println("Starting device...");
                device.start();
                System.out.println("Device started successfully");

                int frameCount = 10;
                System.out.println("Capturing " + frameCount + " frames...");

                for (int i = 0; i < frameCount; i++) {
                    Frame frame = device.getNextFrame(FrameType.DEPTH, 2000);
                    if (frame != null) {
                        System.out.println("Frame " + (i+1) + ": seq=" + frame.getSequence() +
                                         ", timestamp=" + frame.getTimestamp());
                        frame.close();
                    } else {
                        System.out.println("Frame " + (i+1) + ": timeout");
                    }
                }

                device.stop();
                System.out.println("Multiple frame capture PASSED!");
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail("Multiple frame capture failed: " + e.getMessage());
        }
    }
}