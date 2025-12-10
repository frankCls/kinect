package org.openrndr.kinect2.examples

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.camera.Orbital
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.parameters.BooleanParameter
import org.openrndr.extra.parameters.IntParameter
import org.openrndr.extra.parameters.DoubleParameter
import org.openrndr.extra.parameters.OptionParameter
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import org.openrndr.kinect2.GridMeshGenerator
import org.openrndr.math.Vector3
import com.kinect.jni.PipelineType
import org.slf4j.LoggerFactory

/**
 * Visualization modes for mesh rendering
 */
enum class VisualizationMode {
    POINTS, MESH, WIREFRAME
}

/**
 * Kinect V2 3D Mesh Visualization Example
 *
 * Demonstrates real-time 3D mesh generation and rendering from Kinect V2 depth data.
 *
 * **What it shows**: Complete triangulated 3D mesh from Kinect sensor with:
 * - Real RGB colors from the color camera
 * - Surface normals for realistic lighting
 * - Multiple visualization modes (points/mesh/wireframe)
 * - Real-time mesh export to OBJ/PLY formats
 *
 * Features:
 * - Grid-based triangulation with depth discontinuity filtering
 * - Phong lighting with adjustable light source
 * - Interactive 3D camera controls
 * - Real-time statistics display
 * - Export functionality
 *
 * Controls:
 * - **Mouse drag**: Orbit camera around the scene
 * - **Mouse scroll**: Zoom in/out
 * - **W/S/A/D/E/Q**: Move camera
 * - **R**: Reset camera to default position
 * - **E**: Export current mesh to OBJ and PLY files
 * - **F11**: Toggle UI panel visibility (use GUI to change settings)
 *
 * **Performance Notes**:
 * - Initial downsampling: 4x = ~3K triangles
 * - Mesh generation: 5-15ms CPU time
 * - Rendering: GPU-accelerated via VertexBuffer
 */
fun main() {
    val logger = LoggerFactory.getLogger("Kinect2MeshExample")

    println("=== Kinect V2 3D Mesh Example Starting ===")

    // Register shutdown hook for clean Ctrl+C handling
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n=== Shutdown hook triggered (Ctrl+C or system exit) ===")
        println("Cleaning up resources...")
    })

    // UI Settings
    val settings = object {
        @OptionParameter("Visualization Mode", order = 0)
        var visualizationMode = VisualizationMode.MESH

        @IntParameter("Downsample Factor", 1, 8, order = 1)
        var downsample = 4

        @DoubleParameter("Max Depth Discontinuity (mm)", 50.0, 200.0, precision = 0, order = 2)
        var maxDepthDiscontinuity = 100.0

        @BooleanParameter("Enable Lighting", order = 3)
        var enableLighting = true

        @BooleanParameter("Show Coordinate Axes", order = 4)
        var showAxes = false

        @BooleanParameter("Show Reference Grid", order = 5)
        var showGrid = true

        @BooleanParameter("Paused", order = 6)
        var isPaused = false
    }

    // Configuration constants
    val DEPTH_MIN = 50.0     // mm (0.5m)
    val DEPTH_MAX = 5000.0    // mm (5m)
    val DOWNSAMPLE_INITIAL = 4  // Balance between detail and performance
    val DOWNSAMPLE_MIN = 1
    val DOWNSAMPLE_MAX = 8

    application {
        configure {
            width = 1280
            height = 720
            title = "Kinect V2 3D Mesh"
        }

        program {
            // Check for devices
            if (!Kinect2Manager.hasDevices()) {
                println("ERROR: No Kinect V2 devices found!")
                application.exit()
                return@program
            }

            // Timing
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()
            var fps = 0.0
            var meshGenTime = 0L

            // State
            var exportRequested = false
            var meshGenerator: GridMeshGenerator? = null

            // Mesh data
            var currentMesh: org.openrndr.kinect2.Mesh? = null
            var meshVB: VertexBuffer? = null
            var indexBuffer: IndexBuffer? = null
            var wireframeVB: VertexBuffer? = null

            // Intrinsic camera parameters (Kinect V2 depth camera)
            val fx = 365.0
            val fy = 365.0
            val cx = 256.0
            val cy = 212.0

            logger.info("=== Kinect V2 3D Mesh Visualization ===")
            println("Found ${Kinect2Manager.getDeviceCount()} Kinect V2 device(s)")
            println("\nControls:")
            println("  - Mouse: Orbit")
            println("  - Scroll: Zoom")
            println("  - W/S/A/D/E/Q: Move camera")
            println("  - R: Reset camera")
            println("  - E: Export mesh (OBJ + PLY)")
            println("  - F11: Toggle UI panel (use GUI for all settings)")

            // Create Kinect2 extension
            val kinect = extend(Kinect2()) {
                enableDepth = true
                enableColor = true
                enableInfrared = false
                pipelineType = PipelineType.CPU
            }

            // Setup 3D camera
            val camera = Orbital()
            camera.eye = Vector3(0.0, 0.0, 3.0)
            camera.lookAt = Vector3(0.0, 0.0, 1.5)
            camera.fov = 60.0
            camera.near = 0.01
            camera.far = 10.0

            // Extend camera FIRST so it receives mouse events before GUI
            extend(camera)

            // Setup GUI
            val gui = GUI()
            gui.add(settings, "Display Settings")
            extend(gui)

            // Handle window close event for clean shutdown
            window.closed.listen {
                logger.info("Window closed, requesting application exit...")
                application.exit()
            }

            keyboard.keyDown.listen { event ->
                when (event.name) {
                    "r" -> {
                        camera.eye = Vector3(0.0, 0.0, 3.0)
                        camera.lookAt = Vector3(0.0, 0.0, 1.5)
                    }
                    "e" -> exportRequested = true
                }
            }

            extend {
                // FPS calculation
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFpsTime >= 1000) {
                    fps = frameCount * 1000.0 / (currentTime - lastFpsTime)
                    frameCount = 0
                    lastFpsTime = currentTime
                }

                // Background
                drawer.clear(ColorRGBa.BLACK)

                // Get depth data and registered color buffer
                val depthData = kinect.depthCamera.getDepthMillimeters()
                val registeredBuffer = kinect.getRegisteredColorBuffer()

                if (depthData != null && registeredBuffer != null && !settings.isPaused) {
                    // Create or update mesh generator if discontinuity threshold changed
                    if (meshGenerator == null ||
                        meshGenerator!!.maxDepthDiscontinuity != settings.maxDepthDiscontinuity) {
                        meshGenerator = GridMeshGenerator(
                            depthWidth = 512,
                            depthHeight = 424,
                            fx = fx,
                            fy = fy,
                            cx = cx,
                            cy = cy,
                            maxDepthDiscontinuity = settings.maxDepthDiscontinuity,
                            minDepth = DEPTH_MIN,
                            maxDepth = DEPTH_MAX
                        )
                    }

                    // Generate mesh
                    val meshGenStart = System.currentTimeMillis()
                    currentMesh = meshGenerator!!.generateMesh(depthData, registeredBuffer, settings.downsample)
                    meshGenTime = System.currentTimeMillis() - meshGenStart

                    // Update vertex buffer and index buffer
                    val mesh = currentMesh!!
                    if (mesh.vertexCount > 0 && mesh.triangleCount > 0) {
                        // Recreate VB if size changed
                        if (meshVB == null || meshVB!!.vertexCount != mesh.vertexCount) {
                            meshVB?.destroy()
                            meshVB = vertexBuffer(
                                vertexFormat {
                                    position(3)
                                    normal(3)
                                    color(4)
                                },
                                mesh.vertexCount
                            )
                        }

                        // Upload vertex data
                        val vb = meshVB!!
                        vb.put {
                            for (vertex in mesh.vertices) {
                                write(vertex.position)
                                write(vertex.normal)
                                write(vertex.color)
                            }
                        }

                        // Recreate IndexBuffer if size changed
                        if (indexBuffer == null || indexBuffer!!.indexCount != mesh.indices.size) {
                            indexBuffer?.destroy()
                            indexBuffer = indexBuffer(mesh.indices.size, IndexType.INT32)
                        }

                        // Upload index data
                        val ib = indexBuffer!!
                        val intBuffer = java.nio.ByteBuffer.allocateDirect(mesh.indices.size * 4)
                        intBuffer.order(java.nio.ByteOrder.nativeOrder())
                        val intView = intBuffer.asIntBuffer()
                        for (index in mesh.indices) {
                            intView.put(index)
                        }
                        intBuffer.rewind()
                        ib.write(intBuffer)

                        // Generate wireframe edges (each triangle = 3 edges)
                        val wireframeEdges = mutableListOf<Pair<Int, Int>>()
                        val edgeSet = mutableSetOf<Pair<Int, Int>>()

                        for (i in mesh.indices.indices step 3) {
                            val i0 = mesh.indices[i]
                            val i1 = mesh.indices[i + 1]
                            val i2 = mesh.indices[i + 2]

                            // Add edges (deduplicate by storing sorted pairs)
                            val edge1 = if (i0 < i1) Pair(i0, i1) else Pair(i1, i0)
                            val edge2 = if (i1 < i2) Pair(i1, i2) else Pair(i2, i1)
                            val edge3 = if (i2 < i0) Pair(i2, i0) else Pair(i0, i2)

                            if (edgeSet.add(edge1)) wireframeEdges.add(edge1)
                            if (edgeSet.add(edge2)) wireframeEdges.add(edge2)
                            if (edgeSet.add(edge3)) wireframeEdges.add(edge3)
                        }

                        // Create wireframe vertex buffer (2 vertices per edge)
                        val wireframeVertexCount = wireframeEdges.size * 2
                        if (wireframeVB == null || wireframeVB!!.vertexCount != wireframeVertexCount) {
                            wireframeVB?.destroy()
                            wireframeVB = vertexBuffer(
                                vertexFormat {
                                    position(3)
                                    color(4)
                                },
                                wireframeVertexCount
                            )
                        }

                        // Upload wireframe data
                        val wvb = wireframeVB!!
                        wvb.put {
                            for ((idx1, idx2) in wireframeEdges) {
                                val v1 = mesh.vertices[idx1]
                                val v2 = mesh.vertices[idx2]
                                write(v1.position)
                                write(v1.color)
                                write(v2.position)
                                write(v2.color)
                            }
                        }
                    }

                    // Handle export request
                    if (exportRequested && currentMesh != null) {
                        try {
                            val timestamp = java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            val objPath = "kinect_mesh_$timestamp.obj"
                            val plyPath = "kinect_mesh_$timestamp.ply"

                            org.openrndr.kinect2.MeshExporter.exportToOBJ(currentMesh!!, objPath)
                            org.openrndr.kinect2.MeshExporter.exportToPLY(currentMesh!!, plyPath)

                            println("Exported mesh to:")
                            println("  - $objPath")
                            println("  - $plyPath")
                        } catch (e: Exception) {
                            logger.error("Export failed: ${e.message}")
                        }
                        exportRequested = false
                    }

                    // Draw 3D scene
                    drawer.isolated {
                        // Draw reference grid (ground plane at Y=0)
                        if (settings.showGrid) {
                            drawer.stroke = ColorRGBa.GRAY.opacify(0.3)
                            drawer.strokeWeight = 1.0
                            val gridSize = 2.0
                            val gridStep = 0.2
                            for (i in (-10..10)) {
                                val pos = i * gridStep
                                drawer.lineSegment(Vector3(pos, 0.0, -gridSize), Vector3(pos, 0.0, gridSize))
                                drawer.lineSegment(Vector3(-gridSize, 0.0, pos), Vector3(gridSize, 0.0, pos))
                            }
                        }

                        // Draw coordinate axes
                        if (settings.showAxes) {
                            drawer.strokeWeight = 2.0
                            drawer.stroke = ColorRGBa.RED
                            drawer.lineSegment(Vector3.ZERO, Vector3(0.5, 0.0, 0.0))
                            drawer.stroke = ColorRGBa.GREEN
                            drawer.lineSegment(Vector3.ZERO, Vector3(0.0, 0.5, 0.0))
                            drawer.stroke = ColorRGBa.BLUE
                            drawer.lineSegment(Vector3.ZERO, Vector3(0.0, 0.0, 0.5))
                        }

                        // Draw mesh/points/wireframe
                        if (meshVB != null && indexBuffer != null && currentMesh != null && currentMesh!!.vertexCount > 0) {
                            val vb = meshVB!!
                            val ib = indexBuffer!!
                            val mesh = currentMesh!!

                            when (settings.visualizationMode) {
                                VisualizationMode.POINTS -> {
                                    // Point cloud mode (no lighting)
                                    drawer.shadeStyle = shadeStyle {
                                        fragmentTransform = """
                                            x_fill.rgb = va_color.rgb;
                                            x_fill.a = 1.0;
                                        """
                                    }
                                    drawer.vertexBuffer(vb, DrawPrimitive.POINTS)
                                }

                                VisualizationMode.MESH -> {
                                    // Solid mesh with optional lighting
                                    if (settings.enableLighting) {
                                        drawer.shadeStyle = shadeStyle {
                                            fragmentTransform = """
                                                // Phong lighting
                                                vec3 lightDir = normalize(vec3(1.0, 1.0, 2.0));
                                                vec3 normal = normalize(va_normal);

                                                // Ambient
                                                vec3 ambient = va_color.rgb * 0.3;

                                                // Diffuse
                                                float diff = max(dot(normal, lightDir), 0.0);
                                                vec3 diffuse = va_color.rgb * diff * 0.7;

                                                x_fill.rgb = ambient + diffuse;
                                                x_fill.a = 1.0;
                                            """
                                        }
                                    } else {
                                        drawer.shadeStyle = shadeStyle {
                                            fragmentTransform = """
                                                x_fill.rgb = va_color.rgb;
                                                x_fill.a = 1.0;
                                            """
                                        }
                                    }

                                    // Draw triangles with index buffer
                                    drawer.vertexBuffer(ib, listOf(vb), DrawPrimitive.TRIANGLES)
                                }

                                VisualizationMode.WIREFRAME -> {
                                    // Wireframe mode using line primitives
                                    if (wireframeVB != null) {
                                        drawer.shadeStyle = shadeStyle {
                                            fragmentTransform = """
                                                x_stroke.rgb = va_color.rgb;
                                                x_stroke.a = 1.0;
                                            """
                                        }
                                        drawer.stroke = ColorRGBa.WHITE
                                        drawer.strokeWeight = 1.0
                                        drawer.vertexBuffer(wireframeVB!!, DrawPrimitive.LINES)
                                    }
                                }
                            }

                            drawer.shadeStyle = null
                        }
                    }

                    // Draw 2D overlay (stats)
                    drawer.fill = ColorRGBa.WHITE
                    drawer.text("FPS: ${"%.1f".format(fps)}", 10.0, 30.0)
                    drawer.text("Mode: ${settings.visualizationMode}", 10.0, 50.0)
                    drawer.text("Vertices: ${currentMesh?.vertexCount ?: 0}", 10.0, 70.0)
                    drawer.text("Triangles: ${currentMesh?.triangleCount ?: 0}", 10.0, 90.0)
                    drawer.text("Mesh gen: ${meshGenTime}ms", 10.0, 110.0)
                    drawer.text("Downsample: ${settings.downsample}x", 10.0, 130.0)
                    drawer.text("Lighting: ${if (settings.enableLighting) "ON" else "OFF"}", 10.0, 150.0)

                    // Instructions
                    drawer.text("R=reset camera, E=export mesh, F11=toggle UI (use GUI for all settings)", 10.0, height - 30.0)
                } else {
                    drawer.fill = ColorRGBa.RED
                    drawer.text("Waiting for depth/color frames...", 10.0, 30.0)
                }
            }
        }
    }

    println("=== Kinect V2 3D Mesh Example Finished ===")
}
