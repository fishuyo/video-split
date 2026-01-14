package com.videosplit.player

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.*
import org.lwjgl.opengl.GL13.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import com.videosplit.gl.ShaderProgram

import scala.util.{Try, Success, Failure}

/**
 * Renders video frames fullscreen using OpenGL
 */
class FrameRenderer {
  private var window: Long = 0
  private var shaderProgram: ShaderProgram = _
  private var vao: Int = 0
  private var texture: Int = 0
  private var windowWidth: Int = 0
  private var windowHeight: Int = 0
  
  /**
   * Initialize fullscreen window and OpenGL context
   */
  def initialize(fullscreen: Boolean = true): Try[Unit] = Try {
    // Initialize GLFW
    if (!glfwInit()) {
      throw new RuntimeException("Failed to initialize GLFW")
    }
    
    // Configure window hints
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    
    // Get primary monitor for fullscreen
    val monitor = if (fullscreen) glfwGetPrimaryMonitor() else 0
    
    if (fullscreen && monitor == 0) {
      throw new RuntimeException("Failed to get primary monitor")
    }
    
    // Get monitor video mode for fullscreen
    val vidmode = if (fullscreen) glfwGetVideoMode(monitor) else null
    
    if (fullscreen && vidmode == null) {
      throw new RuntimeException("Failed to get video mode")
    }
    
    val width = if (fullscreen) vidmode.width() else 1920
    val height = if (fullscreen) vidmode.height() else 1080
    
    windowWidth = width
    windowHeight = height
    
    // Create window
    System.out.println(s"Creating window: ${width}x${height}, fullscreen: $fullscreen, monitor: $monitor")
    window = glfwCreateWindow(width, height, "Video Player", monitor, 0)
    
    if (window == 0) {
      throw new RuntimeException("Failed to create GLFW window. Check GLFW error callback for details.")
    }
    
    System.out.println(s"Window created successfully: $window")
    
    // Make context current
    glfwMakeContextCurrent(window)
    
    // Create OpenGL capabilities
    GL.createCapabilities()
    
    System.out.println("OpenGL context created, version: " + glGetString(GL_VERSION))
    
    // Show window (important - make sure it's visible)
    // Note: Fullscreen windows are automatically shown, but we'll call it anyway
    glfwShowWindow(window)
    
    if (!fullscreen) {
      // Center window in windowed mode
      val primaryMonitor = glfwGetPrimaryMonitor()
      val vidmode = if (primaryMonitor != 0) glfwGetVideoMode(primaryMonitor) else null
      if (vidmode != null) {
        val xpos = (vidmode.width() - width) / 2
        val ypos = (vidmode.height() - height) / 2
        glfwSetWindowPos(window, xpos, ypos)
        System.out.println(s"Window positioned at: ($xpos, $ypos)")
      }
    }
    
    // Make sure window is focused
    glfwFocusWindow(window)
    
    // Bring window to front
    glfwRequestWindowAttention(window)
    
    // Enable VSync
    glfwSwapInterval(1)
    
    // Setup rendering
    setupRendering()
    
    // Set window close callback
    glfwSetWindowCloseCallback(window, (window: Long) => {
      System.out.println("Window close requested")
    })
    
    System.out.println(s"Window setup complete: ${width}x${height}, fullscreen: $fullscreen, visible: ${glfwGetWindowAttrib(window, GLFW_VISIBLE) == GLFW_TRUE}")
  }
  
  /**
   * Setup OpenGL rendering (shader, VAO, texture)
   */
  private def setupRendering(): Unit = {
    // Simple passthrough shader
    val vertexShaderSource = """
      #version 330 core
      layout (location = 0) in vec2 aPos;
      layout (location = 1) in vec2 aTexCoord;
      
      out vec2 TexCoord;
      
      void main() {
          gl_Position = vec4(aPos, 0.0, 1.0);
          TexCoord = aTexCoord;
      }
    """
    
    val fragmentShaderSource = """
      #version 330 core
      out vec4 FragColor;
      
      in vec2 TexCoord;
      uniform sampler2D videoTexture;
      
      void main() {
          // Flip texture vertically (OpenGL origin is bottom-left, video is top-left)
          vec2 flippedCoord = vec2(TexCoord.x, 1.0 - TexCoord.y);
          FragColor = texture(videoTexture, flippedCoord);
      }
    """
    
    // Use ShaderProgram class to compile shaders
    shaderProgram = new ShaderProgram()
    shaderProgram.loadShadersFromSource(vertexShaderSource, fragmentShaderSource) match {
      case Success(_) => // OK
      case Failure(e) =>
        throw new RuntimeException(s"Failed to compile shaders: ${e.getMessage}", e)
    }
    
    // Create VAO for fullscreen quad
    vao = glGenVertexArrays()
    glBindVertexArray(vao)
    
    val vertices = Array[Float](
      -1.0f, -1.0f, 0.0f, 0.0f,  // Bottom-left
       1.0f, -1.0f, 1.0f, 0.0f,  // Bottom-right
       1.0f,  1.0f, 1.0f, 1.0f,  // Top-right
      -1.0f,  1.0f, 0.0f, 1.0f   // Top-left
    )
    
    val indices = Array[Int](0, 1, 2, 2, 3, 0)
    
    val vbo = glGenBuffers()
    glBindBuffer(GL_ARRAY_BUFFER, vbo)
    val vertexBuffer = MemoryUtil.memAllocFloat(vertices.length)
    vertexBuffer.put(vertices)
    vertexBuffer.flip()
    glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW)
    MemoryUtil.memFree(vertexBuffer)
    
    val ebo = glGenBuffers()
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
    val indexBuffer = MemoryUtil.memAllocInt(indices.length)
    indexBuffer.put(indices)
    indexBuffer.flip()
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW)
    MemoryUtil.memFree(indexBuffer)
    
    // Set vertex attributes
    glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * 4, 0)
    glEnableVertexAttribArray(0)
    glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * 4, 2 * 4)
    glEnableVertexAttribArray(1)
    
    glBindVertexArray(0)
    
    // Create texture for video frame
    texture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, texture)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    glBindTexture(GL_TEXTURE_2D, 0)
  }
  
  /**
   * Render a video frame
   */
  def renderFrame(frame: AVFrame): Unit = {
    val frameWidth = frame.width()
    val frameHeight = frame.height()
    
    // Upload frame to texture
    uploadFrameToTexture(frame)
    
    // Clear screen
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Use shader
    shaderProgram.use()
    
    // Bind texture
    glActiveTexture(GL_TEXTURE0)
    glBindTexture(GL_TEXTURE_2D, texture)
    shaderProgram.setUniform("videoTexture", 0)
    
    // Render quad
    glBindVertexArray(vao)
    glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0)
    glBindVertexArray(0)
    
    // Swap buffers
    glfwSwapBuffers(window)
  }
  
  /**
   * Render black screen (when paused at start or no frame loaded)
   */
  def renderBlack(): Unit = {
    // Clear to black
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Swap buffers
    glfwSwapBuffers(window)
  }
  
  /**
   * Upload AVFrame to OpenGL texture
   * IMPORTANT: Must handle stride/padding correctly
   * linesize may be larger than frameWidth * 3 due to alignment
   */
  private def uploadFrameToTexture(frame: AVFrame): Unit = {
    val frameWidth = frame.width()
    val frameHeight = frame.height()
    val data = frame.data(0)
    val linesize = frame.linesize(0)
    
    // Calculate actual row bytes (RGB24 = 3 bytes per pixel)
    val expectedRowBytes = frameWidth * 3
    val actualRowBytes = linesize
    
    // Check if there's padding
    if (actualRowBytes != expectedRowBytes) {
      // Stride mismatch - need to copy row-by-row, skipping padding
      val totalBytes = frameHeight * expectedRowBytes
      val packedArray = new Array[Byte](totalBytes)
      
      // Copy each row, skipping padding
      for (y <- 0 until frameHeight) {
        val srcOffset = y * actualRowBytes
        val dstOffset = y * expectedRowBytes
        
        data.position(srcOffset)
        data.get(packedArray, dstOffset, expectedRowBytes)
      }
      
      // Upload tightly packed data
      val buffer = MemoryUtil.memAlloc(totalBytes)
      buffer.put(packedArray)
      buffer.flip()
      
      glBindTexture(GL_TEXTURE_2D, texture)
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frameWidth, frameHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, buffer)
      MemoryUtil.memFree(buffer)
    } else {
      // No padding - can copy directly
      val totalBytes = frameHeight * linesize
      val byteArray = new Array[Byte](totalBytes)
      data.position(0)
      data.get(byteArray, 0, totalBytes)
      
      val buffer = MemoryUtil.memAlloc(totalBytes)
      buffer.put(byteArray)
      buffer.flip()
      
      glBindTexture(GL_TEXTURE_2D, texture)
      glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, frameWidth, frameHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, buffer)
      MemoryUtil.memFree(buffer)
    }
    
    glBindTexture(GL_TEXTURE_2D, 0)
  }
  
  /**
   * Check if window should close
   */
  def shouldClose: Boolean = {
    glfwWindowShouldClose(window)
  }
  
  /**
   * Poll events (call in main loop)
   */
  def pollEvents(): Unit = {
    glfwPollEvents()
  }
  
  /**
   * Cleanup resources
   */
  def close(): Unit = {
    if (texture != 0) {
      glDeleteTextures(texture)
      texture = 0
    }
    if (vao != 0) {
      glDeleteVertexArrays(vao)
      vao = 0
    }
    if (shaderProgram != null) {
      shaderProgram.close()
      shaderProgram = null
    }
    if (window != 0) {
      glfwDestroyWindow(window)
      window = 0
    }
    glfwTerminate()
  }
  
  /**
   * Get window handle (for input handling)
   */
  def getWindow: Long = window
}
