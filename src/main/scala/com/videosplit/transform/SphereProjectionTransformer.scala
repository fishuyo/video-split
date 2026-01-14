package com.videosplit.transform

import com.videosplit.calibration.{WarpMap, CalibrationLoader}
import com.videosplit.gl.{OpenGLContext, ShaderProgram}
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.{BytePointer, PointerPointer}
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.*
import org.lwjgl.opengl.GL13.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil

import scala.util.{Try, Success, Failure}

/**
 * Transforms equirectangular video frames to projector space using warp maps
 * Includes decimation analysis and decimation
 */
class SphereProjectionTransformer(
  inputWidth: Int,
  inputHeight: Int,
  warpMap: WarpMap,
  outputWidth: Int,
  outputHeight: Int
) {
  private var context: OpenGLContext = _
  private var shaderProgram: ShaderProgram = _
  private var inputTexture: Int = 0
  private var warpMapTexture: Int = 0
  private var framebuffer: Int = 0
  private var outputTexture: Int = 0
  private var vao: Int = 0
  private var vbo: Int = 0
  
  // Decimation analysis
  private var decimationMap: Option[Array[Array[Boolean]]] = None
  private var decimatedWidth: Int = outputWidth
  private var decimatedHeight: Int = outputHeight
  
  /**
   * Initialize the transformer
   */
  def initialize(): Try[Unit] = Try {
    // Create OpenGL context
    context = new OpenGLContext(outputWidth, outputHeight)
    context.initialize() match {
      case Success(_) =>
        context.makeCurrent()
      case Failure(e) =>
        throw new RuntimeException(s"Failed to initialize OpenGL context: ${e.getMessage}", e)
    }
    
    // Analyze warp map for decimation opportunities
    analyzeDecimation()
    
    // Debug: Print some warp map samples to verify it's loaded correctly
    println(s"Warp map loaded: ${outputWidth}x${outputHeight}")
    println(s"Sample pixels from warp map:")
    for (i <- 0 until math.min(5, outputWidth * outputHeight)) {
      val x = i % outputWidth
      val y = i / outputWidth
      val (dirX, dirY, dirZ, alpha) = warpMap.getPixel(x, y)
      val (u, v) = warpMap.getEquirectangularUV(x, y)
      println(f"  Pixel ($x%4d, $y%4d): dir=($dirX%8.4f, $dirY%8.4f, $dirZ%8.4f), alpha=$alpha%6.4f, uv=($u%6.4f, $v%6.4f)")
    }
    
    
    // Load shader
    shaderProgram = new ShaderProgram()
    shaderProgram.loadShadersFromSource(
      warpingVertexShader,
      warpingFragmentShader
    ) match {
      case Success(_) => // OK
      case Failure(e) =>
        throw new RuntimeException(s"Failed to compile shaders: ${e.getMessage}", e)
    }
    
    setupBuffers()
    setupTextures()
    uploadWarpMap()
  }
  
  /**
   * Analyze warp map to find decimation opportunities
   * Finds pixels that sample the same equirectangular region
   */
  private def analyzeDecimation(): Unit = {
    // Tolerance for considering pixels the same (in UV space)
    // Larger tolerance = more aggressive decimation (more pixels considered redundant)
    // Smaller tolerance = less decimation (only exact duplicates removed)
    // For equirectangular, pixel density varies by region, so we need some tolerance
    val tolerance = 0.5f / math.min(inputWidth, inputHeight).toFloat  // ~0.5 pixel tolerance
    
    // Build map of equirectangular UV -> list of projector pixels
    val uvToPixels = scala.collection.mutable.Map[(Float, Float), scala.collection.mutable.ListBuffer[(Int, Int)]]()
    
    for (y <- 0 until outputHeight) {
      for (x <- 0 until outputWidth) {
        val (u, v) = warpMap.getEquirectangularUV(x, y)
        val key = (math.round(u / tolerance).toFloat * tolerance, math.round(v / tolerance).toFloat * tolerance)
        uvToPixels.getOrElseUpdate(key, scala.collection.mutable.ListBuffer.empty) += ((x, y))
      }
    }
    
    // Count unique equirectangular pixels (unique UV coordinates)
    val uniquePixels = uvToPixels.size
    val totalPixels = outputWidth * outputHeight
    
    // Calculate scale factor based on pixel density
    // Scale = sqrt(unique_pixels / total_pixels) to maintain 2D area ratio
    val scaleFactor = math.sqrt(uniquePixels.toDouble / totalPixels.toDouble)
    
    // Calculate decimated dimensions maintaining aspect ratio
    decimatedWidth = math.max(1, (outputWidth * scaleFactor).toInt)
    decimatedHeight = math.max(1, (outputHeight * scaleFactor).toInt)
    
    // Ensure aspect ratio is maintained exactly
    val aspectRatio = outputWidth.toDouble / outputHeight.toDouble
    if (decimatedWidth.toDouble / decimatedHeight.toDouble > aspectRatio) {
      // Width is too large, adjust height
      decimatedHeight = math.max(1, (decimatedWidth / aspectRatio).toInt)
    } else {
      // Height is too large, adjust width
      decimatedWidth = math.max(1, (decimatedHeight * aspectRatio).toInt)
    }
    
    // Build decimation map: mark pixels to keep (for reference, but we'll scale down instead)
    val keep = Array.ofDim[Boolean](outputHeight, outputWidth)
    var kept = 0
    var removed = 0
    
    uvToPixels.foreach { case (_, pixels) =>
      if (pixels.nonEmpty) {
        // Keep first pixel in each group
        val (keepX, keepY) = pixels.head
        keep(keepY)(keepX) = true
        kept += 1
        
        if (pixels.length > 1) {
          pixels.tail.foreach { case (x, y) =>
            keep(y)(x) = false
            removed += 1
          }
        }
      }
    }
    
    decimationMap = Some(keep)
    
    println(s"Decimation analysis: ${outputWidth}x${outputHeight} -> ${decimatedWidth}x${decimatedHeight}")
    println(f"  Scale factor: ${scaleFactor}%.3f (${scaleFactor * 100}%.1f%%)")
    println(s"  Unique equirectangular pixels: $uniquePixels / $totalPixels total")
    println(s"  Kept: $kept pixels, Removed: $removed redundant pixels")
  }
  
  /**
   * Set up vertex buffers for fullscreen quad
   */
  private def setupBuffers(): Unit = {
    val vertices = Array[Float](
      -1.0f, -1.0f, 0.0f, 0.0f,
       1.0f, -1.0f, 1.0f, 0.0f,
       1.0f,  1.0f, 1.0f, 1.0f,
      -1.0f,  1.0f, 0.0f, 1.0f
    )
    
    val indices = Array[Int](0, 1, 2, 2, 3, 0)
    
    vao = glGenVertexArrays()
    glBindVertexArray(vao)
    
    vbo = glGenBuffers()
    glBindBuffer(GL_ARRAY_BUFFER, vbo)
    val vertexBuffer = MemoryUtil.memAllocFloat(vertices.length)
    vertexBuffer.put(vertices)
    vertexBuffer.flip()
    glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW)
    MemoryUtil.memFree(vertexBuffer)
    
    glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * 4, 0)
    glEnableVertexAttribArray(0)
    glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * 4, 2 * 4)
    glEnableVertexAttribArray(1)
    
    val ebo = glGenBuffers()
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
    val indexBuffer = MemoryUtil.memAllocInt(indices.length)
    indexBuffer.put(indices)
    indexBuffer.flip()
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW)
    MemoryUtil.memFree(indexBuffer)
    
    glBindVertexArray(0)
  }
  
  /**
   * Set up textures
   */
  private def setupTextures(): Unit = {
    // Input texture (equirectangular video)
    inputTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    
    // Warp map texture (RGBA32F: x, y, z, alpha as float32)
    warpMapTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, warpMapTexture)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
    
    // Output texture (full resolution - decimation happens during readback)
    outputTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, outputTexture)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, outputWidth, outputHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, 0)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    
    // Framebuffer
    framebuffer = glGenFramebuffers()
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outputTexture, 0)
    
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      throw new RuntimeException("Framebuffer is not complete!")
    }
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    glBindTexture(GL_TEXTURE_2D, 0)
  }
  
  /**
   * Upload warp map to texture
   * Encode 3D direction + alpha as RGBA texture
   */
  private def uploadWarpMap(): Unit = {
    val warpData = new Array[Float](outputWidth * outputHeight * 4)  // RGBA as float32
    
    var validPixels = 0
    var invalidPixels = 0
    var minU = Float.MaxValue
    var maxU = Float.MinValue
    var minV = Float.MaxValue
    var maxV = Float.MinValue
    var minX = Int.MaxValue
    var maxX = Int.MinValue
    var minY = Int.MaxValue
    var maxY = Int.MinValue
    
    for (y <- 0 until outputHeight) {
      for (x <- 0 until outputWidth) {
        val (dirX, dirY, dirZ, alpha) = warpMap.getPixel(x, y)
        
        // Check if this pixel has valid direction data
        val dirLength = math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ)
        if (dirLength > 0.001 && alpha > 0.01) {
          validPixels += 1
          minX = math.min(minX, x)
          maxX = math.max(maxX, x)
          minY = math.min(minY, y)
          maxY = math.max(maxY, y)
          // Convert to UV to see coverage
          val (u, v) = warpMap.directionToEquirectangular(dirX, dirY, dirZ)
          minU = math.min(minU, u)
          maxU = math.max(maxU, u)
          minV = math.min(minV, v)
          maxV = math.max(maxV, v)
        } else {
          invalidPixels += 1
        }
        
        // Store directly as float32: dirX, dirY, dirZ, alpha
        val idx = (y * outputWidth + x) * 4
        warpData(idx) = dirX.toFloat
        warpData(idx + 1) = dirY.toFloat
        warpData(idx + 2) = dirZ.toFloat
        warpData(idx + 3) = alpha.toFloat
      }
    }
    
    glBindTexture(GL_TEXTURE_2D, warpMapTexture)
    val buffer = MemoryUtil.memAllocFloat(warpData.length)
    buffer.put(warpData)
    buffer.flip()
    // Use GL_RGBA32F for float32 texture
    glTexImage2D(GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, outputWidth, outputHeight, 0, GL_RGBA, GL_FLOAT, buffer)
    MemoryUtil.memFree(buffer)
    glBindTexture(GL_TEXTURE_2D, 0)
    
    // Debug: Print warp map coverage
    println(s"Warp map coverage: ${validPixels} valid pixels, ${invalidPixels} invalid pixels")
    if (validPixels > 0) {
      println(f"  Valid region: x=[$minX, $maxX], y=[$minY, $maxY] (${maxX - minX + 1}x${maxY - minY + 1})")
      println(f"  Input UV range: u=[${minU}%.4f, ${maxU}%.4f], v=[${minV}%.4f, ${maxV}%.4f]")
      println(f"  Coverage: ${(maxU - minU) * 100}%.1f%% width, ${(maxV - minV) * 100}%.1f%% height")
      if (invalidPixels > 0) {
        println(s"  WARNING: ${invalidPixels} pixels have invalid directions - these will render as black")
      }
    }
  }
  
  /**
   * Transform equirectangular frame to projector space (decimation disabled)
   */
  def transformFrame(inputFrame: AVFrame): Try[AVFrame] = Try {
    context.makeCurrent()
    
    // Upload input frame
    uploadFrameToTexture(inputFrame)
    
    // Bind framebuffer
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    // Set viewport to cover entire output resolution
    glViewport(0, 0, outputWidth, outputHeight)
    
    // Clear to black
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Use shader
    shaderProgram.use()
    shaderProgram.setUniform("inputTexture", 0)
    shaderProgram.setUniform("warpMap", 1)
    shaderProgram.setUniform("inputSize", inputWidth.toFloat, inputHeight.toFloat)
    shaderProgram.setUniform("outputSize", outputWidth.toFloat, outputHeight.toFloat)
    
    // Bind textures
    glActiveTexture(GL_TEXTURE0)
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    glActiveTexture(GL_TEXTURE1)
    glBindTexture(GL_TEXTURE_2D, warpMapTexture)
    
    // Render fullscreen quad
    glBindVertexArray(vao)
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)
    glBindVertexArray(0)
    
    // Flush to ensure rendering completes
    glFinish()
    
    // Read back result
    val outputFrame = readFramebufferToFrame()
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    
    outputFrame
  }
  
  
  /**
   * Upload AVFrame to texture
   */
  private def uploadFrameToTexture(frame: AVFrame): Unit = {
    val frameWidth = frame.width()
    val frameHeight = frame.height()
    val data = frame.data(0)
    val linesize = frame.linesize(0)
    
    val totalBytes = frameHeight * linesize
    val byteArray = new Array[Byte](totalBytes)
    data.position(0)
    data.get(byteArray, 0, totalBytes)
    
    val buffer = MemoryUtil.memAlloc(totalBytes)
    buffer.put(byteArray)
    buffer.flip()
    
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frameWidth, frameHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, buffer)
    MemoryUtil.memFree(buffer)
  }
  
  /**
   * Read framebuffer to AVFrame (decimation disabled for now)
   */
  private def readFramebufferToFrame(): AVFrame = {
    // Read full resolution framebuffer
    val fullPixels = new Array[Byte](outputWidth * outputHeight * 3)
    val buffer = MemoryUtil.memAlloc(outputWidth * outputHeight * 3)
    
    glReadPixels(0, 0, outputWidth, outputHeight, GL_RGB, GL_UNSIGNED_BYTE, buffer)
    buffer.get(fullPixels)
    MemoryUtil.memFree(buffer)
    
    // Apply decimation: scale down framebuffer to decimated dimensions
    val (actualWidth, actualHeight, pixels) = if (decimatedWidth < outputWidth || decimatedHeight < outputHeight) {
      // Scale down: sample framebuffer at decimated resolution
      val decimatedPixels = new Array[Byte](decimatedWidth * decimatedHeight * 3)
      
      // Sample pixels from full resolution framebuffer
      // Use nearest-neighbor sampling for simplicity (can be improved with bilinear)
      for (y <- 0 until decimatedHeight) {
        for (x <- 0 until decimatedWidth) {
          // Map decimated coordinates back to full resolution
          val srcX = (x * outputWidth / decimatedWidth.toDouble).toInt
          val srcY = (y * outputHeight / decimatedHeight.toDouble).toInt
          
          // Flip vertically (OpenGL origin is bottom-left)
          val flippedSrcY = outputHeight - 1 - srcY
          val srcOffset = flippedSrcY * outputWidth * 3 + srcX * 3
          val dstOffset = y * decimatedWidth * 3 + x * 3
          
          // Copy pixel (RGB)
          decimatedPixels(dstOffset) = fullPixels(srcOffset)
          decimatedPixels(dstOffset + 1) = fullPixels(srcOffset + 1)
          decimatedPixels(dstOffset + 2) = fullPixels(srcOffset + 2)
        }
      }
      
      (decimatedWidth, decimatedHeight, decimatedPixels)
    } else {
      // No decimation - use full resolution, just flip vertically
      val flippedPixels = new Array[Byte](outputWidth * outputHeight * 3)
      for (y <- 0 until outputHeight) {
        val srcY = outputHeight - 1 - y
        System.arraycopy(fullPixels, srcY * outputWidth * 3, flippedPixels, y * outputWidth * 3, outputWidth * 3)
      }
      (outputWidth, outputHeight, flippedPixels)
    }
    
    val frame = av_frame_alloc()
    if (frame == null) {
      throw new RuntimeException("Failed to allocate output frame")
    }
    
    val numBytes = av_image_get_buffer_size(AV_PIX_FMT_RGB24, actualWidth, actualHeight, 1)
    val frameBuffer = new BytePointer(av_malloc(numBytes))
    if (frameBuffer == null || frameBuffer.isNull) {
      throw new RuntimeException("Failed to allocate frame buffer")
    }
    
    av_image_fill_arrays(frame.data(), frame.linesize(), frameBuffer, AV_PIX_FMT_RGB24, actualWidth, actualHeight, 1)
    
    // Copy pixels to frame (already flipped if decimation was applied)
    val data = frame.data(0)
    val linesize = frame.linesize(0)
    val totalBytes = actualHeight * linesize
    val byteArray = new Array[Byte](totalBytes)
    
    // Copy pixels row by row
    for (y <- 0 until actualHeight) {
      val srcOffset = y * actualWidth * 3
      val dstOffset = y * linesize
      System.arraycopy(pixels, srcOffset, byteArray, dstOffset, actualWidth * 3)
    }
    
    data.position(0)
    data.put(byteArray, 0, totalBytes)
    
    frame.width(actualWidth)
    frame.height(actualHeight)
    frame.format(AV_PIX_FMT_RGB24)
    
    // Debug: Verify frame is valid
    if (actualWidth <= 0 || actualHeight <= 0) {
      throw new RuntimeException(s"Invalid frame dimensions: ${actualWidth}x${actualHeight}")
    }
    
    frame
  }
  
  /**
   * Get decimated dimensions
   */
  def getDecimatedWidth: Int = decimatedWidth
  def getDecimatedHeight: Int = decimatedHeight
  
  def close(): Unit = {
    if (shaderProgram != null) {
      shaderProgram.close()
      shaderProgram = null
    }
    if (context != null) {
      // Only destroy window, don't terminate GLFW (for reuse across nodes)
      context.close()
      context = null
    }
  }
  
  // Warping shader that samples equirectangular based on warp map
  private val warpingVertexShader: String =
    """#version 330 core
      |layout (location = 0) in vec2 aPos;
      |layout (location = 1) in vec2 aTexCoord;
      |
      |out vec2 TexCoord;
      |
      |void main() {
      |    gl_Position = vec4(aPos, 0.0, 1.0);
      |    TexCoord = aTexCoord;
      |}
      |""".stripMargin
  
  private val warpingFragmentShader: String =
    """#version 330 core
      |out vec4 FragColor;
      |
      |in vec2 TexCoord;
      |
      |uniform sampler2D inputTexture;  // Equirectangular video
      |uniform sampler2D warpMap;        // Warp map (RGBA32F: dirX, dirY, dirZ, alpha as float)
      |uniform vec2 inputSize;          // Equirectangular video size
      |uniform vec2 outputSize;         // Projector output size
      |
      |vec2 directionToEquirectangular(vec3 dir) {
      |    // Normalize direction vector
      |    float len = length(dir);
      |    if (len < 0.0001) {
      |        return vec2(0.5, 0.5);  // Default to center if invalid
      |    }
      |    vec3 normalized = dir / len;
      |    
      |    // Convert 3D direction to equirectangular UV coordinates
      |    // Coordinate system: -z is forward, so center (0,0,-1) should map to u=0.5, v=0.5
      |    // 
      |    // Standard equirectangular mapping:
      |    // - u (longitude): maps azimuth angle [-π, π] to [0, 1]
      |    //   theta = atan2(y, x) gives [-π, π]
      |    //   u = (theta + π) / (2π)
      |    // - v (latitude): maps elevation angle [0, π] to [0, 1]
      |    //   Standard: phi = acos(z), v = phi/π where z=+1 -> v=0 (top), z=-1 -> v=1 (bottom)
      |    // 
      |    // For -z forward system:
      |    // Standard maps z=-1 to v=1 (bottom), but we want z=-1 at v=0.5 (center)
      |    // Solution: Use phi = acos(-z), then shift: v = (phi/π + 0.5) mod 1.0
      |    // But mod is expensive, so use: v = phi/π + 0.5, then clamp to [0,1]
      |    // However, this gives v in [0.5, 1.5] range for phi in [0, π]
      |    // 
      |    // Better: Use phi = acos(-z), then v = phi/π
      |    // This maps: z=-1 -> phi=0 -> v=0, z=+1 -> phi=π -> v=1
      |    // To get z=-1 at v=0.5: shift by 0.5: v = phi/π + 0.5, wrapped
      |    // Since phi/π is in [0,1], v = phi/π + 0.5 wraps to [0.5, 1.5] -> [0.5, 1.0] and [0.0, 0.5]
      |    // So: v = phi/π + 0.5, then if v > 1.0: v = v - 1.0
      |    // 
      |    // Actually, simplest correct solution:
      |    // Standard: phi = acos(z), v = phi/π maps z=-1 to v=1
      |    // To get z=-1 at v=0.5: v = phi/π - 0.5, but this gives negative for z>0
      |    // So wrap: v = (phi/π - 0.5 + 1.0) mod 1.0 = (phi/π + 0.5) mod 1.0
      |    // 
      |    // Final solution: phi = acos(-normalized.z), v = (phi/π + 0.5) mod 1.0
      |    // But mod is expensive, so: v = phi/π + 0.5, then if v > 1.0: v = v - 1.0
      |    // Or simpler: v = phi/π + 0.5, clamped to [0,1] (but this loses wrap-around)
      |    // 
      |    // Actually, let's use: phi = acos(-normalized.z), v = phi/π + 0.5
      |    // Then wrap: if v > 1.0: v = v - 1.0
      |    // This maps: z=-1 -> phi=0 -> v=0.5 ✓, z=0 -> phi=π/2 -> v=1.0, z=+1 -> phi=π -> v=1.5 -> 0.5
      |    // 
      |    // Wait, that's not right either. Let me think differently:
      |    // Standard equirectangular: phi = acos(z), v = phi/π
      |    // Maps: z=-1 -> v=1, z=0 -> v=0.5, z=+1 -> v=0
      |    // For -z forward, we want z=-1 -> v=0.5
      |    // So shift: v = phi/π - 0.5, wrapped: v = (phi/π - 0.5 + 1.0) mod 1.0 = (phi/π + 0.5) mod 1.0
      |    // 
      |    // Right-handed coordinates: +x right, -z forward, +y up
      |    // Center (uv 0.5, 0.5) should map to direction (0, 0, -1)
      |    // 
      |    // Azimuth: angle in xz plane (y is up)
      |    // For (0, 0, -1): theta = atan2(x, -z) = atan2(0, 1) = 0, u = 0.5 ✓
      |    float theta = atan(normalized.x, -normalized.z);  // [-π, π] in xz plane
      |    float u = (theta + 3.14159265) / (2.0 * 3.14159265);  // [0, 1]
      |    
      |    // Elevation: angle from horizontal plane (y component)
      |    // For (0, 0, -1): phi = asin(y) = asin(0) = 0, v = 0.5 ✓
      |    // For (0, 1, 0): phi = asin(1) = π/2, v = 0.0 (top after flip)
      |    // For (0, -1, 0): phi = asin(-1) = -π/2, v = 1.0 (bottom after flip)
      |    float phi = asin(clamp(normalized.y, -1.0, 1.0));  // [-π/2, π/2]
      |    float v = 1.0 - (phi + 1.57079633) / 3.14159265;  // Flip: [0, 1] where 0.5 is horizontal
      |    
      |    return vec2(u, v);
      |}
      |
      |void main() {
      |    // TexCoord is in [0, 1] range for fullscreen quad
      |    // This maps directly to projector pixel coordinates (output UV)
      |    vec2 outputUV = TexCoord;
      |    
      |    // Sample warp map at this output pixel location
      |    // Warp map is RGBA32F, so values are already in correct range
      |    vec4 warpData = texture(warpMap, outputUV);
      |    
      |    // Read direction directly from float texture (no decoding needed)
      |    vec3 direction = warpData.xyz;
      |    float alpha = warpData.w;
      |    
      |    // Check if direction is valid (non-zero length) and alpha is significant
      |    float dirLength = length(direction);
      |    float blendAlpha = clamp(alpha, 0.0, 1.0);
      |    
      |    // If direction is invalid or alpha is too low, output black
      |    if (dirLength < 0.001 || blendAlpha < 0.01) {
      |        FragColor = vec4(0.0, 0.0, 0.0, 1.0);
      |        return;
      |    }
      |    
      |    // Convert 3D direction to equirectangular UV coordinates (input UV)
      |    vec2 inputUV = directionToEquirectangular(direction);
      |    
      |    // Clamp input UV to valid range [0, 1] to prevent wrapping/tiling
      |    inputUV = clamp(inputUV, 0.0, 1.0);
      |    
      |    // Sample equirectangular video at the computed input UV
      |    vec4 color = texture(inputTexture, inputUV);
      |    
      |    // Use the sampled color from input texture, scaled by alpha
      |    FragColor = vec4(color.rgb * blendAlpha, 1.0);
      |}
      |""".stripMargin
}
