package com.videosplit.test

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Offscreen OpenGL test: Create an offscreen context, draw a triangle, and save as image
 */
object OffscreenOpenGLTest {
  def main(args: Array[String]): Unit = {
    val outputPath = if (args.length > 0) args(0) else "offscreen_triangle.png"
    val width = if (args.length > 1) args(1).toInt else 800
    val height = if (args.length > 2) args(2).toInt else 600
    
    println(s"Creating offscreen OpenGL context (${width}x${height})...")
    
    // Set up error callback
    GLFWErrorCallback.createPrint(System.err).set()
    
    // Initialize GLFW
    if (!glfwInit()) {
      throw new IllegalStateException("Unable to initialize GLFW")
    }
    
    // Configure GLFW for offscreen rendering
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)  // Hide window
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)
    
    // Create hidden window
    val window = glfwCreateWindow(width, height, "Offscreen Test", NULL, NULL)
    
    if (window == NULL) {
      glfwTerminate()
      throw new RuntimeException("Failed to create GLFW window")
    }
    
    // Make the OpenGL context current
    glfwMakeContextCurrent(window)
    
    // Create OpenGL capabilities
    GL.createCapabilities()
    
    println("OpenGL context created successfully")
    println(s"OpenGL Version: ${glGetString(GL_VERSION)}")
    println(s"OpenGL Vendor: ${glGetString(GL_VENDOR)}")
    println(s"OpenGL Renderer: ${glGetString(GL_RENDERER)}")
    
    // Set viewport
    glViewport(0, 0, width, height)
    
    // Set clear color (dark blue)
    glClearColor(0.1f, 0.1f, 0.2f, 1.0f)
    
    // Clear the framebuffer
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Draw triangle
    glBegin(GL_TRIANGLES)
    glColor3f(1.0f, 0.0f, 0.0f)  // Red
    glVertex2f(0.0f, 0.5f)        // Top vertex
    glColor3f(0.0f, 1.0f, 0.0f)  // Green
    glVertex2f(-0.5f, -0.5f)     // Bottom left
    glColor3f(0.0f, 0.0f, 1.0f)  // Blue
    glVertex2f(0.5f, -0.5f)      // Bottom right
    glEnd()
    
    // Flush OpenGL commands
    glFinish()
    
    println("Triangle drawn, reading pixels...")
    
    // Read pixels from framebuffer
    // OpenGL uses bottom-left origin, so we need to flip vertically
    val pixels = new Array[Byte](width * height * 3)  // RGB
    val buffer = java.nio.ByteBuffer.allocateDirect(width * height * 3)
    
    glReadPixels(0, 0, width, height, GL_RGB, GL_UNSIGNED_BYTE, buffer)
    
    // Copy from ByteBuffer to array
    buffer.rewind()
    buffer.get(pixels)
    
    // Create BufferedImage (top-left origin)
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    
    // Flip vertically and convert RGB bytes to BufferedImage
    for (y <- 0 until height) {
      val srcY = height - 1 - y  // Flip vertically
      for (x <- 0 until width) {
        val srcIndex = (srcY * width + x) * 3
        val r = pixels(srcIndex) & 0xFF
        val g = pixels(srcIndex + 1) & 0xFF
        val b = pixels(srcIndex + 2) & 0xFF
        val rgb = (0xFF << 24) | (r << 16) | (g << 8) | b
        image.setRGB(x, y, rgb)
      }
    }
    
    // Save image
    val outputFile = new File(outputPath)
    ImageIO.write(image, "png", outputFile)
    
    println(s"Image saved to: ${outputFile.getAbsolutePath} (${outputFile.length()} bytes)")
    
    // Cleanup
    glfwDestroyWindow(window)
    glfwTerminate()
    glfwSetErrorCallback(null).free()
    
    println("Test completed successfully!")
  }
}
