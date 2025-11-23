package com.kinect.jni;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone application to test Kinect V2 frame capture with proper display context.
 *
 * This application runs outside Maven Surefire's headless environment, allowing
 * OpenGL pipeline initialization to succeed.
 *
 * Usage:
 *   mvn exec:java -pl kinect-jni                              # Console only
 *   mvn exec:java -pl kinect-jni -Dexec.args="--gui"          # With window display
 *   mvn exec:java -pl kinect-jni -Dexec.args="--duration 30"  # Custom duration
 */
public class FrameCaptureDemo {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RED = "\u001B[31m";

    public static void main(String[] args) {
        // Parse command-line arguments
        boolean enableGui = false;
        int durationSeconds = 10;
        int frameCount = 30;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--gui":
                    enableGui = true;
                    break;
                case "--duration":
                    if (i + 1 < args.length) {
                        durationSeconds = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--frames":
                    if (i + 1 < args.length) {
                        frameCount = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--help":
                    printHelp();
                    return;
            }
        }

        System.out.println("\n" + ANSI_CYAN + "=== Kinect V2 Frame Capture Demo ===" + ANSI_RESET);

        // Check native library loading
        if (!Freenect.isLibraryLoaded()) {
            System.err.println(ANSI_RED + "Native library not loaded!" + ANSI_RESET);
            System.exit(1);
        }
        System.out.println(ANSI_GREEN + "Native library loaded: ✓" + ANSI_RESET);
        System.out.println("libfreenect2 version: " + Freenect.getVersion());

        // Run capture session
        CaptureSession session = new CaptureSession(enableGui, durationSeconds, frameCount);
        try {
            session.run();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "\nError: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("\nKinect V2 Frame Capture Demo");
        System.out.println("\nUsage: mvn exec:java -pl kinect-jni -Dexec.args=\"[OPTIONS]\"");
        System.out.println("\nOptions:");
        System.out.println("  --gui              Enable visual window display");
        System.out.println("  --duration <sec>   Capture duration in seconds (default: 10)");
        System.out.println("  --frames <count>   Frames to capture per type (default: 30)");
        System.out.println("  --help             Show this help message");
        System.out.println("\nExamples:");
        System.out.println("  mvn exec:java -pl kinect-jni");
        System.out.println("  mvn exec:java -pl kinect-jni -Dexec.args=\"--gui\"");
        System.out.println("  mvn exec:java -pl kinect-jni -Dexec.args=\"--gui --duration 30\"");
    }

    /**
     * Manages a frame capture session.
     */
    static class CaptureSession {
        private final boolean enableGui;
        private final int durationSeconds;
        private final int targetFrameCount;
        private final Map<FrameType, FrameStats> stats = new HashMap<>();
        private FrameDisplay display;

        public CaptureSession(boolean enableGui, int durationSeconds, int targetFrameCount) {
            this.enableGui = enableGui;
            this.durationSeconds = durationSeconds;
            this.targetFrameCount = targetFrameCount;

            // Initialize statistics
            stats.put(FrameType.COLOR, new FrameStats("COLOR", 1920, 1080));
            stats.put(FrameType.DEPTH, new FrameStats("DEPTH", 512, 424));
            stats.put(FrameType.IR, new FrameStats("IR", 512, 424));
        }

        public void run() throws Exception {
            try (FreenectContext context = Freenect.createContext()) {
                int deviceCount = context.getDeviceCount();
                System.out.println("Found " + deviceCount + " Kinect device(s)");

                if (deviceCount == 0) {
                    throw new RuntimeException("No Kinect device connected");
                }

                String serial = context.getDefaultDeviceSerial();
                System.out.println("Device serial: " + serial);

                if (enableGui) {
                    display = new FrameDisplay();
                    display.show();
                }

                System.out.println("Opening device...");
                try (KinectDevice device = context.openDefaultDevice()) {
                    System.out.println(ANSI_GREEN + "Device opened successfully" + ANSI_RESET);
                    System.out.println("Firmware version: " + device.getFirmwareVersion());

                    System.out.println("\nStarting frame capture...");
                    device.start();

                    long startTime = System.currentTimeMillis();
                    long endTime = startTime + (durationSeconds * 1000L);

                    // Capture loop
                    while (System.currentTimeMillis() < endTime && !isComplete()) {
                        captureFrame(device, FrameType.COLOR);
                        captureFrame(device, FrameType.DEPTH);
                        captureFrame(device, FrameType.IR);

                        Thread.sleep(10); // Small delay between captures
                    }

                    device.stop();

                    if (display != null) {
                        display.close();
                    }

                    printStatistics();
                }
            }
        }

        private void captureFrame(KinectDevice device, FrameType type) {
            FrameStats stat = stats.get(type);
            if (stat.count >= targetFrameCount) {
                return;
            }

            Frame frame = device.getNextFrame(type, 2000); // 2 second timeout
            if (frame != null) {
                try {
                    stat.recordFrame(frame);
                    printFrameInfo(type, frame, stat.count);

                    if (display != null) {
                        display.updateFrame(type, frame);
                    }
                } finally {
                    frame.close();
                }
            } else {
                stat.timeouts++;
            }
        }

        private void printFrameInfo(FrameType type, Frame frame, int count) {
            String color;
            switch (type) {
                case COLOR: color = ANSI_CYAN; break;
                case DEPTH: color = ANSI_YELLOW; break;
                case IR: color = ANSI_GREEN; break;
                default: color = ANSI_RESET;
            }

            System.out.printf("%s[%s]%s Frame %d/%d: seq=%d, timestamp=%.3f, %dx%dx%d, %d bytes\n",
                color, type.name(), ANSI_RESET,
                count, targetFrameCount,
                frame.getSequence(),
                frame.getTimestamp() / 1000.0,
                frame.getWidth(), frame.getHeight(), frame.getBytesPerPixel(),
                frame.getDataSize());
        }

        private boolean isComplete() {
            return stats.values().stream().allMatch(s -> s.count >= targetFrameCount);
        }

        private void printStatistics() {
            System.out.println("\n" + ANSI_CYAN + "=== Capture Statistics ===" + ANSI_RESET);

            for (FrameStats stat : stats.values()) {
                double successRate = (stat.count * 100.0) / targetFrameCount;
                System.out.printf("%s frames: %d/%d (%.1f%%) - %d timeouts\n",
                    stat.name, stat.count, targetFrameCount, successRate, stat.timeouts);
            }

            long totalFrames = stats.values().stream().mapToLong(s -> s.count).sum();
            double avgFps = stats.values().stream()
                .filter(s -> s.getDuration() > 0)
                .mapToDouble(FrameStats::getFps)
                .average()
                .orElse(0.0);

            System.out.printf("\nTotal frames captured: %d\n", totalFrames);
            System.out.printf("Average capture rate: %.1f fps\n", avgFps);
        }
    }

    /**
     * Tracks statistics for a specific frame type.
     */
    static class FrameStats {
        final String name;
        final int width;
        final int height;
        int count = 0;
        int timeouts = 0;
        long firstTimestamp = -1;
        long lastTimestamp = -1;

        public FrameStats(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
        }

        public void recordFrame(Frame frame) {
            count++;
            long timestamp = (long) frame.getTimestamp();
            if (firstTimestamp == -1) {
                firstTimestamp = timestamp;
            }
            lastTimestamp = timestamp;
        }

        public double getDuration() {
            if (firstTimestamp == -1 || lastTimestamp == -1) {
                return 0.0;
            }
            return (lastTimestamp - firstTimestamp) / 1000.0; // Convert to seconds
        }

        public double getFps() {
            double duration = getDuration();
            return duration > 0 ? count / duration : 0.0;
        }
    }

    /**
     * Simple Swing window to display frames (optional).
     */
    static class FrameDisplay {
        private final JFrame frame;
        private final Map<FrameType, JPanel> panels = new HashMap<>();
        private final Map<FrameType, BufferedImage> images = new HashMap<>();

        public FrameDisplay() {
            frame = new JFrame("Kinect V2 Frame Capture");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setLayout(new GridLayout(1, 3, 10, 10));

            // Create panels for each frame type
            createPanel(FrameType.COLOR, 1920, 1080);
            createPanel(FrameType.DEPTH, 512, 424);
            createPanel(FrameType.IR, 512, 424);

            frame.pack();
            frame.setLocationRelativeTo(null);
        }

        private void createPanel(FrameType type, int width, int height) {
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    BufferedImage img = images.get(type);
                    if (img != null) {
                        // Scale to fit panel
                        int panelWidth = getWidth();
                        int panelHeight = getHeight();
                        double scale = Math.min((double) panelWidth / width, (double) panelHeight / height);
                        int scaledWidth = (int) (width * scale);
                        int scaledHeight = (int) (height * scale);
                        int x = (panelWidth - scaledWidth) / 2;
                        int y = (panelHeight - scaledHeight) / 2;
                        g.drawImage(img, x, y, scaledWidth, scaledHeight, null);
                    }

                    // Draw label
                    g.setColor(Color.WHITE);
                    g.drawString(type.name(), 10, 20);
                }
            };
            panel.setPreferredSize(new Dimension(320, 240));
            panel.setBackground(Color.BLACK);
            panels.put(type, panel);
            frame.add(panel);

            // Create image buffer
            images.put(type, new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
        }

        public void show() {
            SwingUtilities.invokeLater(() -> frame.setVisible(true));
        }

        public void close() {
            SwingUtilities.invokeLater(() -> frame.dispose());
        }

        public void updateFrame(FrameType type, Frame frame) {
            BufferedImage img = images.get(type);
            if (img == null) return;

            ByteBuffer data = frame.getData();
            if (data == null) return;

            int width = frame.getWidth();
            int height = frame.getHeight();
            int bpp = frame.getBytesPerPixel();

            // Convert frame data to image
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int idx = (y * width + x) * bpp;

                    if (type == FrameType.COLOR) {
                        // BGRA format
                        int b = data.get(idx) & 0xFF;
                        int g = data.get(idx + 1) & 0xFF;
                        int r = data.get(idx + 2) & 0xFF;
                        img.setRGB(x, y, (r << 16) | (g << 8) | b);
                    } else {
                        // Depth/IR: grayscale from first 2 bytes
                        int low = data.get(idx) & 0xFF;
                        int high = data.get(idx + 1) & 0xFF;
                        int value = (high << 8) | low;

                        // Normalize to 0-255
                        int gray = type == FrameType.DEPTH ?
                            Math.min(255, (value * 255) / 4500) :  // Depth: 0-4500mm -> 0-255
                            Math.min(255, (value * 255) / 65535);  // IR: full 16-bit range

                        img.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                    }
                }
            }

            // Repaint panel
            JPanel panel = panels.get(type);
            if (panel != null) {
                SwingUtilities.invokeLater(panel::repaint);
            }
        }
    }
}
