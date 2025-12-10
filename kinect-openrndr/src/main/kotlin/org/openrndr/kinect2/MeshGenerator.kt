package org.openrndr.kinect2

import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector3
import java.nio.ByteBuffer
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Represents a single vertex in a 3D mesh
 * @property position 3D coordinates in meters
 * @property color RGB color from Kinect color camera
 * @property normal Surface normal vector for lighting calculations
 */
data class Vertex(
    val position: Vector3,
    val color: ColorRGBa,
    val normal: Vector3
)

/**
 * Represents a triangulated 3D mesh
 * @property vertices List of all unique vertices
 * @property indices Triangle indices (3 consecutive indices = 1 triangle)
 */
data class Mesh(
    val vertices: List<Vertex>,
    val indices: List<Int>
) {
    val triangleCount: Int get() = indices.size / 3
    val vertexCount: Int get() = vertices.size
}

/**
 * Grid-based mesh generator for Kinect V2 depth data
 *
 * Converts the 512x424 depth grid into a triangulated mesh by:
 * 1. Creating a vertex for each valid depth pixel
 * 2. Triangulating adjacent pixels in a grid pattern (2 triangles per quad)
 * 3. Computing surface normals from adjacent depth values
 * 4. Filtering triangles that span large depth discontinuities
 *
 * @property depthWidth Depth camera width (typically 512 for Kinect V2)
 * @property depthHeight Depth camera height (typically 424 for Kinect V2)
 * @property fx Focal length X in pixels (camera intrinsic)
 * @property fy Focal length Y in pixels (camera intrinsic)
 * @property cx Principal point X in pixels (camera intrinsic)
 * @property cy Principal point Y in pixels (camera intrinsic)
 * @property maxDepthDiscontinuity Maximum allowed depth jump between adjacent pixels (mm)
 * @property minDepth Minimum valid depth value (mm)
 * @property maxDepth Maximum valid depth value (mm)
 */
class GridMeshGenerator(
    private val depthWidth: Int = 512,
    private val depthHeight: Int = 424,
    private val fx: Double = 365.0,
    private val fy: Double = 365.0,
    private val cx: Double = 256.0,
    private val cy: Double = 212.0,
    val maxDepthDiscontinuity: Double = 100.0,  // mm (made public for comparison)
    private val minDepth: Double = 500.0,               // mm (0.5m)
    private val maxDepth: Double = 8000.0               // mm (8m)
) {
    private val logger = LoggerFactory.getLogger(GridMeshGenerator::class.java)

    /**
     * Unprojects a 2D depth pixel to 3D coordinates
     * @param x Pixel X coordinate
     * @param y Pixel Y coordinate
     * @param depth Depth value in millimeters
     * @return 3D position in meters
     */
    private fun unProject(x: Int, y: Int, depth: Double): Vector3 {
        val z = depth / 1000.0  // Convert mm to meters
        val xVal = (x.toDouble() - cx) * z / fx
        val yVal = (y.toDouble() - cy) * z / fy
        return Vector3(xVal, -yVal, z)  // Negate Y for standard coordinate system
    }

    /**
     * Extracts depth value at grid position
     * @param depthData ByteBuffer containing depth data (4 bytes per float)
     * @param x Grid X coordinate
     * @param y Grid Y coordinate
     * @return Depth in millimeters, or null if invalid
     */
    private fun getDepth(depthData: ByteBuffer, x: Int, y: Int): Double? {
        if (x < 0 || x >= depthWidth || y < 0 || y >= depthHeight) return null

        val idx = (y * depthWidth + x) * 4  // 4 bytes per float
        if (idx + 3 >= depthData.capacity()) return null

        val depthFloat = depthData.getFloat(idx)
        val depthMm = depthFloat.toDouble()

        return if (depthMm > minDepth && depthMm < maxDepth && !depthMm.isNaN()) {
            depthMm
        } else {
            null
        }
    }

    /**
     * Extracts RGB color from registered color buffer
     * @param colorBuffer ByteBuffer in BGRX format (4 bytes per pixel)
     * @param x Grid X coordinate
     * @param y Grid Y coordinate
     * @return ColorRGBa or white fallback
     */
    private fun getColor(colorBuffer: ByteBuffer, x: Int, y: Int): ColorRGBa {
        val idx = (y * depthWidth + x) * 4  // BGRX = 4 bytes per pixel
        if (idx + 2 >= colorBuffer.capacity()) return ColorRGBa.WHITE

        val b = (colorBuffer[idx].toInt() and 0xFF) / 255.0
        val g = (colorBuffer[idx + 1].toInt() and 0xFF) / 255.0
        val r = (colorBuffer[idx + 2].toInt() and 0xFF) / 255.0

        return ColorRGBa(r, g, b)
    }

    /**
     * Computes surface normal from three 3D points using cross product
     * @param p0 First vertex
     * @param p1 Second vertex
     * @param p2 Third vertex
     * @return Normalized surface normal
     */
    private fun computeNormal(p0: Vector3, p1: Vector3, p2: Vector3): Vector3 {
        val edge1 = p1 - p0
        val edge2 = p2 - p0
        val normal = edge1.cross(edge2)
        val length = sqrt(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z)
        return if (length > 0.0001) {
            Vector3(normal.x / length, normal.y / length, normal.z / length)
        } else {
            Vector3(0.0, 0.0, 1.0)  // Fallback normal
        }
    }

    /**
     * Checks if depth discontinuity between points is acceptable
     * @param d1 First depth value (mm)
     * @param d2 Second depth value (mm)
     * @return True if discontinuity is within threshold
     */
    private fun isValidEdge(d1: Double, d2: Double): Boolean {
        return abs(d1 - d2) < maxDepthDiscontinuity
    }

    /**
     * Generates a triangulated mesh from Kinect depth and color data
     *
     * Algorithm:
     * 1. Iterate through depth grid in 2x2 quads
     * 2. For each quad with 4 valid depth values, create 2 triangles
     * 3. Filter triangles that span large depth discontinuities (edges)
     * 4. Compute per-vertex normals by averaging adjacent triangle normals
     *
     * @param depthData Depth buffer (512x424 floats in millimeters)
     * @param colorBuffer Registered color buffer (512x424 BGRX pixels)
     * @param downsample Downsampling factor (1=full resolution, 2=half, etc.)
     * @return Generated Mesh with vertices, indices, and normals
     */
    fun generateMesh(
        depthData: ByteBuffer,
        colorBuffer: ByteBuffer,
        downsample: Int = 1
    ): Mesh {
        val startTime = System.currentTimeMillis()

        // Rewind buffers to start
        depthData.rewind()
        colorBuffer.rewind()

        // Step 1: Build vertex grid (position + color)
        val vertexGrid = Array(depthHeight) { y ->
            Array(depthWidth) { x ->
                if (x % downsample == 0 && y % downsample == 0) {
                    val depth = getDepth(depthData, x, y)
                    if (depth != null) {
                        val pos = unProject(x, y, depth)
                        val color = getColor(colorBuffer, x, y)
                        Triple(pos, color, depth)
                    } else null
                } else null
            }
        }

        // Step 2: Generate triangles from grid quads
        val triangles = mutableListOf<Triple<Int, Int, Int>>()  // Vertex indices
        val vertices = mutableListOf<Triple<Vector3, ColorRGBa, Double>>()  // Temp: pos, color, depth
        val vertexIndices = mutableMapOf<Pair<Int, Int>, Int>()  // (x,y) -> vertex index

        var nextVertexIdx = 0

        for (y in 0 until depthHeight - downsample step downsample) {
            for (x in 0 until depthWidth - downsample step downsample) {
                // Get 4 corners of quad
                val v00 = vertexGrid[y][x]
                val v10 = vertexGrid[y][x + downsample]
                val v01 = vertexGrid[y + downsample][x]
                val v11 = vertexGrid[y + downsample][x + downsample]

                // Skip if any corner is invalid
                if (v00 == null || v10 == null || v01 == null || v11 == null) continue

                val (p00, _, d00) = v00
                val (p10, _, d10) = v10
                val (p01, _, d01) = v01
                val (p11, _, d11) = v11

                // Check depth discontinuities on quad edges
                val validEdges = isValidEdge(d00, d10) && isValidEdge(d00, d01) &&
                                isValidEdge(d11, d10) && isValidEdge(d11, d01)

                if (!validEdges) continue

                // Add vertices if not already in map
                fun addVertex(vx: Int, vy: Int, vertex: Triple<Vector3, ColorRGBa, Double>): Int {
                    val key = Pair(vx, vy)
                    return vertexIndices.getOrPut(key) {
                        vertices.add(vertex)
                        nextVertexIdx++
                    }
                }

                val idx00 = addVertex(x, y, v00)
                val idx10 = addVertex(x + downsample, y, v10)
                val idx01 = addVertex(x, y + downsample, v01)
                val idx11 = addVertex(x + downsample, y + downsample, v11)

                // Create 2 triangles per quad
                // Triangle 1: (0,0) -> (1,0) -> (0,1)
                triangles.add(Triple(idx00, idx10, idx01))

                // Triangle 2: (1,0) -> (1,1) -> (0,1)
                triangles.add(Triple(idx10, idx11, idx01))
            }
        }

        // Step 3: Compute per-vertex normals (average of adjacent triangle normals)
        val vertexNormals = Array(vertices.size) { mutableListOf<Vector3>() }

        for ((i0, i1, i2) in triangles) {
            val p0 = vertices[i0].first
            val p1 = vertices[i1].first
            val p2 = vertices[i2].first
            val normal = computeNormal(p0, p1, p2)

            vertexNormals[i0].add(normal)
            vertexNormals[i1].add(normal)
            vertexNormals[i2].add(normal)
        }

        // Step 4: Average normals and create final vertex list
        val finalVertices = vertices.mapIndexed { idx, (pos, color, _) ->
            val normals = vertexNormals[idx]
            val avgNormal = if (normals.isNotEmpty()) {
                val sum = normals.fold(Vector3.ZERO) { acc, n -> acc + n }
                val len = sqrt(sum.x * sum.x + sum.y * sum.y + sum.z * sum.z)
                if (len > 0.0001) Vector3(sum.x / len, sum.y / len, sum.z / len)
                else Vector3(0.0, 0.0, 1.0)
            } else {
                Vector3(0.0, 0.0, 1.0)  // Fallback
            }

            Vertex(pos, color, avgNormal)
        }

        // Step 5: Flatten triangle indices
        val finalIndices = triangles.flatMap { (i0, i1, i2) -> listOf(i0, i1, i2) }

        val elapsed = System.currentTimeMillis() - startTime
        logger.debug("Generated mesh: ${finalVertices.size} vertices, ${triangles.size} triangles in ${elapsed}ms")

        return Mesh(finalVertices, finalIndices)
    }
}
