package com.videosplit.calibration

import java.io.{File, FileInputStream}
import java.nio.{ByteBuffer, ByteOrder}

/**
 * Loads calibration data for render nodes
 */
object CalibrationLoader {
  
  case class ProjectorConfig(
    index: Int,
    resolution: (Int, Int),  // (width, height)
    warpMapPath: String
  )
  
  case class NodeCalibration(
    hostname: String,
    projectors: List[ProjectorConfig]
  )
  
  /**
   * Load calibration for a node from text file
   * Format: hostname.txt contains projector configs
   */
  def loadNodeCalibration(hostname: String, calibrationDir: String): Option[NodeCalibration] = {
    val file = new File(calibrationDir, s"$hostname.txt")
    if (!file.exists()) {
      return None
    }
    
    val lines = scala.io.Source.fromFile(file).getLines().toList
    val projectors = parseProjectorConfigs(lines)
    
    Some(NodeCalibration(hostname, projectors))
  }
  
  /**
   * Parse projector configurations from text file
   * Format:
   *   id <id>
   *   filepath <path>
   *   width <width>
   *   height <height>
   *   b <bottom> h <height> l <left> w <width>  (viewport)
   *   active <0|1>
   */
  private def parseProjectorConfigs(lines: List[String]): List[ProjectorConfig] = {
    var projectors = List.empty[ProjectorConfig]
    var currentId: Option[Int] = None
    var currentPath: Option[String] = None
    var currentWidth: Option[Int] = None
    var currentHeight: Option[Int] = None
    var projectorIndex = 0
    
    lines.foreach { line =>
      val trimmed = line.trim
      if (trimmed.startsWith("id ")) {
        // Save previous projector if exists
        if (currentId.isDefined && currentPath.isDefined && currentWidth.isDefined && currentHeight.isDefined) {
          projectors = ProjectorConfig(projectorIndex, (currentWidth.get, currentHeight.get), currentPath.get) :: projectors
          projectorIndex += 1
        }
        currentId = Some(trimmed.substring(3).trim.toInt)
      } else if (trimmed.startsWith("filepath ")) {
        val path = trimmed.substring(9).trim
        // Extract filename from path
        val filename = path.split("/").last
        currentPath = Some(filename)
      } else if (trimmed.startsWith("width ")) {
        currentWidth = Some(trimmed.substring(6).trim.toInt)
      } else if (trimmed.startsWith("height ")) {
        currentHeight = Some(trimmed.substring(7).trim.toInt)
      }
    }
    
    // Add last projector
    if (currentId.isDefined && currentPath.isDefined && currentWidth.isDefined && currentHeight.isDefined) {
      projectors = ProjectorConfig(projectorIndex, (currentWidth.get, currentHeight.get), currentPath.get) :: projectors
    }
    
    projectors.reverse
  }
  
  /**
   * Load warp map from binary file
   * Format: x, y, z, alpha per pixel (non-interleaved: all x, then all y, then all z, then all alpha)
   */
  def loadWarpMap(path: String, width: Int, height: Int): Option[WarpMap] = {
    val file = new File(path)
    if (!file.exists()) {
      println(s"Warp map file not found: $path")
      return None
    }
    
    val fileSize = file.length().toInt
    val expectedSize = width * height * 4 * 4  // 4 floats per pixel (x, y, z, alpha)
    
    // Infer actual resolution from file size if mismatch
    var actualWidth = width
    var actualHeight = height
    
    if (fileSize != expectedSize) {
      println(s"Warning: Warp map size mismatch. Expected $expectedSize bytes, got $fileSize")
      // Try to infer resolution from file size
      val pixels = fileSize / 16  // 16 bytes per pixel (4 floats)
      // Try common resolutions
      if (pixels == 1920 * 1200) {
        actualWidth = 1920
        actualHeight = 1200
        println(s"Inferred resolution: ${actualWidth}x${actualHeight}")
      } else if (pixels == 1920 * 1080) {
        actualWidth = 1920
        actualHeight = 1080
        println(s"Inferred resolution: ${actualWidth}x${actualHeight}")
      } else {
        println(s"Could not infer resolution. File has $pixels pixels")
        // Use original dimensions but warn
      }
    }
    
    val fis = new FileInputStream(file)
    val bytes = new Array[Byte](fileSize)
    
    var bytesRead = 0
    while (bytesRead < fileSize) {
      val read = fis.read(bytes, bytesRead, fileSize - bytesRead)
      if (read < 0) {
        fis.close()
        return None
      }
      bytesRead += read
    }
    fis.close()
    
    // Convert to float buffer
    val buffer = ByteBuffer.wrap(bytes)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    
    // Parse as floats
    val floatCount = fileSize / 4
    val floats = new Array[Float](floatCount)
    val floatBuffer = buffer.asFloatBuffer()
    floatBuffer.get(floats)
    
    // Interleaved format: x, y, z, alpha per pixel
    val warpMap = new WarpMap(actualWidth, actualHeight)
    
    val pixels = actualWidth * actualHeight
    if (floats.length >= pixels * 4) {
      for (i <- 0 until pixels) {
        val baseIdx = i * 4  // Each pixel is 4 consecutive floats
        val x = floats(baseIdx)
        val y = floats(baseIdx + 1)
        val z = floats(baseIdx + 2)
        val alpha = floats(baseIdx + 3)
        warpMap.setPixel(i % actualWidth, i / actualWidth, x, y, z, alpha)
      }
      Some(warpMap)
    } else {
      println(s"Error: Not enough float data. Expected ${pixels * 4}, got ${floats.length}")
      None
    }
  }
}

/**
 * Represents a warp map (3D direction + alpha per pixel)
 */
class WarpMap(val width: Int, val height: Int) {
  private val data = Array.ofDim[Float](height, width, 4)  // [y][x][x, y, z, alpha]
  
  def setPixel(x: Int, y: Int, dirX: Float, dirY: Float, dirZ: Float, alpha: Float): Unit = {
    if (x >= 0 && x < width && y >= 0 && y < height) {
      data(y)(x)(0) = dirX
      data(y)(x)(1) = dirY
      data(y)(x)(2) = dirZ
      data(y)(x)(3) = alpha
    }
  }
  
  def getPixel(x: Int, y: Int): (Float, Float, Float, Float) = {
    if (x >= 0 && x < width && y >= 0 && y < height) {
      (data(y)(x)(0), data(y)(x)(1), data(y)(x)(2), data(y)(x)(3))
    } else {
      (0f, 0f, 0f, 0f)
    }
  }
  
  /**
   * Convert 3D direction to equirectangular (u, v) coordinates
   * Right-handed coordinates: +x right, -z forward, +y up
   * Center (uv 0.5, 0.5) maps to direction (0, 0, -1)
   * u, v in range [0, 1]
   */
  def directionToEquirectangular(dirX: Float, dirY: Float, dirZ: Float): (Float, Float) = {
    val length = math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ).toDouble
    if (length < 0.0001) {
      return (0.5f, 0.5f)  // Default to center if invalid
    }
    
    val normalizedX = (dirX / length).toFloat
    val normalizedY = (dirY / length).toFloat
    val normalizedZ = (dirZ / length).toFloat
    
    // Azimuth: angle in xz plane (y is up)
    // theta = atan2(x, -z) in xz plane
    val theta = math.atan2(normalizedX, -normalizedZ).toFloat  // [-π, π]
    val u = ((theta + math.Pi) / (2 * math.Pi)).toFloat  // [0, 1]
    
    // Elevation: angle from horizontal plane (y component)
    // phi = asin(y)
    val phi = math.asin(math.max(-1.0, math.min(1.0, normalizedY))).toFloat  // [-π/2, π/2]
    val v = (1.0f - (phi + math.Pi/2.0) / math.Pi).toFloat  // Flip: [0, 1] where 0.5 is horizontal
    
    (u, v)
  }
  
  /**
   * Get equirectangular coordinates for a projector pixel
   */
  def getEquirectangularUV(x: Int, y: Int): (Float, Float) = {
    val (dirX, dirY, dirZ, _) = getPixel(x, y)
    directionToEquirectangular(dirX, dirY, dirZ)
  }
}
