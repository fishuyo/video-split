package com.videosplit.transform

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
import org.lwjgl.system.MemoryUtil

import scala.util.{Try, Success, Failure}

/**
 * Transforms video frames using GLSL shaders
 */
class FrameTransformer(
  width: Int,
  height: Int,
  shaderPath: Option[String] = None,
  transformParams: Map[String, Double] = Map.empty
) {
  private var context: OpenGLContext = _
  private var shaderProgram: ShaderProgram = _
  private var inputTexture: Int = 0
  private var framebuffer: Int = 0
  private var outputTexture: Int = 0
  private var vao: Int = 0
  private var vbo: Int = 0
  
  /**
   * Initialize the transformer
   */
  def initialize(): Try[Unit] = Try {
    // Create OpenGL context
    context = new OpenGLContext(width, height)
    context.initialize() match {
      case Success(_) =>
        context.makeCurrent()
      case Failure(e) =>
        throw new RuntimeException(s"Failed to initialize OpenGL context: ${e.getMessage}", e)
    }
    
    // Set up default shader or load from file
    if (shaderPath.isDefined) {
      shaderProgram = new ShaderProgram()
      val basePath = shaderPath.get
      val vertexPath = s"$basePath.vert"
      val fragmentPath = s"$basePath.frag"
      shaderProgram.loadShaders(vertexPath, fragmentPath) match {
        case Success(_) => // OK
        case Failure(e) =>
          throw new RuntimeException(s"Failed to load shaders: ${e.getMessage}", e)
      }
    } else {
      // Use default passthrough shader
      shaderProgram = new ShaderProgram()
      shaderProgram.loadShadersFromSource(
        defaultVertexShader,
        defaultFragmentShader
      ) match {
        case Success(_) => // OK
        case Failure(e) =>
          throw new RuntimeException(s"Failed to compile default shaders: ${e.getMessage}", e)
      }
    }
    
    setupBuffers()
    setupTextures()
  }
  
  /**
   * Set up vertex buffers for fullscreen quad
   */
  private def setupBuffers(): Unit = {
    // Fullscreen quad vertices (NDC coordinates)
    val vertices = Array[Float](
      // Position   // TexCoords
      -1.0f, -1.0f, 0.0f, 0.0f,  // Bottom-left
       1.0f, -1.0f, 1.0f, 0.0f,  // Bottom-right
       1.0f,  1.0f, 1.0f, 1.0f,  // Top-right
      -1.0f,  1.0f, 0.0f, 1.0f   // Top-left
    )
    
    val indices = Array[Int](
      0, 1, 2,
      2, 3, 0
    )
    
    // Create VAO
    vao = glGenVertexArrays()
    glBindVertexArray(vao)
    
    // Create VBO
    vbo = glGenBuffers()
    glBindBuffer(GL_ARRAY_BUFFER, vbo)
    val vertexBuffer = MemoryUtil.memAllocFloat(vertices.length)
    vertexBuffer.put(vertices)
    vertexBuffer.flip()
    glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW)
    MemoryUtil.memFree(vertexBuffer)
    
    // Position attribute
    glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * 4, 0)
    glEnableVertexAttribArray(0)
    
    // Texture coordinate attribute
    glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * 4, 2 * 4)
    glEnableVertexAttribArray(1)
    
    // Create EBO
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
   * Set up input and output textures
   */
  private def setupTextures(): Unit = {
    // Create input texture
    inputTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    
    // Create output texture
    outputTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, outputTexture)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, 0)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    
    // Create framebuffer
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
   * Transform a frame using the shader
   */
  def transformFrame(inputFrame: AVFrame): Try[AVFrame] = Try {
    context.makeCurrent()
    
    // Upload input frame to texture
    uploadFrameToTexture(inputFrame)
    
    // Bind framebuffer for rendering
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    glViewport(0, 0, width, height)
    
    // Clear
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Use shader
    shaderProgram.use()
    
    // Set uniforms from transformParams
    transformParams.foreach { case (name, value) =>
      shaderProgram.setUniform(name, value.toFloat)
    }
    
    // Set input texture
    glActiveTexture(GL_TEXTURE0)
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    shaderProgram.setUniform("inputTexture", 0)
    
    // Draw quad
    glBindVertexArray(vao)
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)
    glBindVertexArray(0)
    
    // Read pixels from framebuffer
    val outputFrame = readFramebufferToFrame()
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    
    outputFrame
  }
  
  /**
   * Upload AVFrame (RGB24) to OpenGL texture
   */
  private def uploadFrameToTexture(frame: AVFrame): Unit = {
    val frameWidth = frame.width()
    val frameHeight = frame.height()
    val data = frame.data(0)
    val linesize = frame.linesize(0)
    
    // Copy data to Java byte array
    val totalBytes = frameHeight * linesize
    val byteArray = new Array[Byte](totalBytes)
    data.position(0)
    data.get(byteArray, 0, totalBytes)
    
    // Create ByteBuffer for OpenGL
    val buffer = MemoryUtil.memAlloc(totalBytes)
    buffer.put(byteArray)
    buffer.flip()
    
    glBindTexture(GL_TEXTURE_2D, inputTexture)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frameWidth, frameHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, buffer)
    
    MemoryUtil.memFree(buffer)
  }
  
  /**
   * Read framebuffer pixels to AVFrame (RGB24)
   */
  private def readFramebufferToFrame(): AVFrame = {
    val pixels = new Array[Byte](width * height * 3)
    val buffer = MemoryUtil.memAlloc(width * height * 3)
    
    glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, buffer)
    
    buffer.get(pixels)
    MemoryUtil.memFree(buffer)
    
    // Create AVFrame (caller will need to allocate properly)
    // For now, we'll create a frame structure that can be used
    // Note: This is a simplified version - in practice, you'd want to
    // reuse frames and manage memory properly
    val frame = av_frame_alloc()
    if (frame == null) {
      throw new RuntimeException("Failed to allocate output frame")
    }
    
    val numBytes = av_image_get_buffer_size(
      AV_PIX_FMT_RGB24,
      width,
      height,
      1
    )
    
    val frameBuffer = new BytePointer(av_malloc(numBytes))
    if (frameBuffer == null || frameBuffer.isNull) {
      throw new RuntimeException("Failed to allocate frame buffer")
    }
    
    av_image_fill_arrays(
      frame.data(),
      frame.linesize(),
      frameBuffer,
      AV_PIX_FMT_RGB24,
      width,
      height,
      1
    )
    
    // Copy pixels (flip vertically)
    val data = frame.data(0)
    val linesize = frame.linesize(0)
    
    // Copy to Java byte array first, then to BytePointer
    val totalBytes = height * linesize
    val byteArray = new Array[Byte](totalBytes)
    
    for (y <- 0 until height) {
      val srcY = height - 1 - y  // Flip vertically
      val srcOffset = srcY * width * 3
      val dstOffset = y * linesize
      
      // Copy row
      System.arraycopy(pixels, srcOffset, byteArray, dstOffset, width * 3)
    }
    
    // Copy from Java array to BytePointer
    data.position(0)
    data.put(byteArray, 0, totalBytes)
    
    frame.width(width)
    frame.height(height)
    frame.format(AV_PIX_FMT_RGB24)
    
    frame
  }
  
  /**
   * Cleanup resources
   */
  def close(): Unit = {
    if (shaderProgram != null) {
      shaderProgram.close()
    }
    if (context != null) {
      context.close()
    }
    // Note: OpenGL resources will be cleaned up when context is destroyed
  }
  
  // Default passthrough shaders
  private val defaultVertexShader: String =
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
  
  private val defaultFragmentShader: String =
    """#version 330 core
      |out vec4 FragColor;
      |
      |in vec2 TexCoord;
      |
      |uniform sampler2D inputTexture;
      |
      |void main() {
      |    FragColor = texture(inputTexture, TexCoord);
      |}
      |""".stripMargin
}
