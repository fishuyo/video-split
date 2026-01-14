package com.videosplit.gl

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil.NULL

import scala.util.{Try, Success, Failure}

/**
 * Manages an offscreen OpenGL context using GLFW
 * GLFW initialization is shared across contexts (only initialized once)
 */
object OpenGLContext {
  private var glfwInitialized = false
  private var errorCallback: GLFWErrorCallback = null
  
  private def ensureGLFWInitialized(): Unit = synchronized {
    if (!glfwInitialized) {
      // Set up error callback
      errorCallback = GLFWErrorCallback.createPrint(System.err)
      errorCallback.set()
      
      // Initialize GLFW
      if (!glfwInit()) {
        throw new IllegalStateException("Unable to initialize GLFW")
      }
      
      glfwInitialized = true
    }
  }
  
  def terminateGLFW(): Unit = synchronized {
    if (glfwInitialized) {
      glfwTerminate()
      if (errorCallback != null) {
        errorCallback.free()
        errorCallback = null
      }
      glfwInitialized = false
    }
  }
}

class OpenGLContext(width: Int, height: Int) {
  private var window: Long = NULL
  private var initialized = false
  
  /**
   * Initialize the offscreen OpenGL context
   */
  def initialize(): Try[Unit] = Try {
    if (initialized) {
      throw new IllegalStateException("OpenGL context already initialized")
    }
    
    // Ensure GLFW is initialized (shared across all contexts)
    OpenGLContext.ensureGLFWInitialized()
    
    // Configure GLFW for offscreen rendering
    glfwDefaultWindowHints()
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)  // Hide window
    glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
    
    // Create hidden window
    window = glfwCreateWindow(width, height, "Offscreen Context", NULL, NULL)
    
    if (window == NULL) {
      throw new RuntimeException("Failed to create GLFW window")
    }
    
    // Make the OpenGL context current
    glfwMakeContextCurrent(window)
    
    // Create OpenGL capabilities
    GL.createCapabilities()
    
    // Set viewport
    glViewport(0, 0, width, height)
    
    initialized = true
  }
  
  /**
   * Get the OpenGL context window handle
   */
  def getWindow: Long = {
    if (!initialized) {
      throw new IllegalStateException("OpenGL context not initialized")
    }
    window
  }
  
  /**
   * Make this context current
   */
  def makeCurrent(): Unit = {
    if (!initialized) {
      throw new IllegalStateException("OpenGL context not initialized")
    }
    glfwMakeContextCurrent(window)
  }
  
  /**
   * Get the width of the context
   */
  def getWidth: Int = width
  
  /**
   * Get the height of the context
   */
  def getHeight: Int = height
  
  /**
   * Cleanup resources
   * Only destroys this window, does NOT terminate GLFW (for reuse across nodes)
   */
  def close(): Unit = {
    if (initialized && window != NULL) {
      glfwDestroyWindow(window)
      window = NULL
      initialized = false
    }
  }
  
  /**
   * Terminate GLFW (call this only when all contexts are done)
   */
  def terminateGLFW(): Unit = {
    OpenGLContext.terminateGLFW()
  }
}
