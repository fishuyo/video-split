package com.videosplit.test

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL

/**
 * Simple LWJGL test: Create a window and draw a triangle
 */
object OpenGLTest {
  def main(args: Array[String]): Unit = {
    println("Starting LWJGL OpenGL test...")
    
    // Set up error callback
    GLFWErrorCallback.createPrint(System.err).set()
    
    // Initialize GLFW
    if (!glfwInit()) {
      throw new IllegalStateException("Unable to initialize GLFW")
    }
    
    // Configure GLFW
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
    glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
    
    // Create window
    val width = 800
    val height = 600
    val window = glfwCreateWindow(width, height, "LWJGL Triangle Test", NULL, NULL)
    
    if (window == NULL) {
      glfwTerminate()
      throw new RuntimeException("Failed to create GLFW window")
    }
    
    // Set up key callback
    glfwSetKeyCallback(window, (window, key, scancode, action, mods) => {
      if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
        glfwSetWindowShouldClose(window, true)
      }
    })
    
    // Get thread stack and push a new frame
    val stack = org.lwjgl.system.MemoryStack.stackPush()
    try {
      val pWidth = stack.mallocInt(1)
      val pHeight = stack.mallocInt(1)
      
      glfwGetWindowSize(window, pWidth, pHeight)
      val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor())
      
      glfwSetWindowPos(
        window,
        (vidmode.width() - pWidth.get(0)) / 2,
        (vidmode.height() - pHeight.get(0)) / 2
      )
    } finally {
      stack.pop()
    }
    
    // Make the OpenGL context current
    glfwMakeContextCurrent(window)
    // Enable v-sync
    glfwSwapInterval(1)
    // Make the window visible
    glfwShowWindow(window)
    
    // Create OpenGL capabilities
    GL.createCapabilities()
    
    // Set clear color (dark blue)
    glClearColor(0.1f, 0.1f, 0.2f, 1.0f)
    
    println("Window created successfully. Press ESC to close.")
    
    // Main loop
    while (!glfwWindowShouldClose(window)) {
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
      
      // Swap buffers
      glfwSwapBuffers(window)
      
      // Poll events
      glfwPollEvents()
    }
    
    // Cleanup
    glfwDestroyWindow(window)
    glfwTerminate()
    glfwSetErrorCallback(null).free()
    
    println("Test completed successfully!")
  }
}
