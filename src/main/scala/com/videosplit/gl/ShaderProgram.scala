package com.videosplit.gl

import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.*
import org.lwjgl.opengl.GL30.*

import scala.io.Source
import scala.util.{Try, Success, Failure}

/**
 * Manages a GLSL shader program
 */
class ShaderProgram {
  private var programId: Int = 0
  private var vertexShaderId: Int = 0
  private var fragmentShaderId: Int = 0
  
  /**
   * Load and compile shaders from files
   */
  def loadShaders(vertexPath: String, fragmentPath: String): Try[Unit] = Try {
    val vertexSource = Source.fromFile(vertexPath).mkString
    val fragmentSource = Source.fromFile(fragmentPath).mkString
    
    loadShadersFromSource(vertexSource, fragmentSource)
  }
  
  /**
   * Load and compile shaders from source strings
   */
  def loadShadersFromSource(vertexSource: String, fragmentSource: String): Try[Unit] = Try {
    // Compile vertex shader
    vertexShaderId = glCreateShader(GL_VERTEX_SHADER)
    glShaderSource(vertexShaderId, vertexSource)
    glCompileShader(vertexShaderId)
    
    if (glGetShaderi(vertexShaderId, GL_COMPILE_STATUS) == GL_FALSE) {
      val log = glGetShaderInfoLog(vertexShaderId)
      glDeleteShader(vertexShaderId)
      throw new RuntimeException(s"Vertex shader compilation failed:\n$log")
    }
    
    // Compile fragment shader
    fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER)
    glShaderSource(fragmentShaderId, fragmentSource)
    glCompileShader(fragmentShaderId)
    
    if (glGetShaderi(fragmentShaderId, GL_COMPILE_STATUS) == GL_FALSE) {
      val log = glGetShaderInfoLog(fragmentShaderId)
      glDeleteShader(vertexShaderId)
      glDeleteShader(fragmentShaderId)
      throw new RuntimeException(s"Fragment shader compilation failed:\n$log")
    }
    
    // Create program
    programId = glCreateProgram()
    glAttachShader(programId, vertexShaderId)
    glAttachShader(programId, fragmentShaderId)
    glLinkProgram(programId)
    
    if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
      val log = glGetProgramInfoLog(programId)
      glDeleteProgram(programId)
      glDeleteShader(vertexShaderId)
      glDeleteShader(fragmentShaderId)
      throw new RuntimeException(s"Shader program linking failed:\n$log")
    }
    
    // Detach shaders (they're linked into the program)
    glDetachShader(programId, vertexShaderId)
    glDetachShader(programId, fragmentShaderId)
  }
  
  /**
   * Use this shader program
   */
  def use(): Unit = {
    glUseProgram(programId)
  }
  
  /**
   * Get uniform location
   */
  def getUniformLocation(name: String): Int = {
    glGetUniformLocation(programId, name)
  }
  
  /**
   * Set uniform float
   */
  def setUniform(name: String, value: Float): Unit = {
    val location = getUniformLocation(name)
    if (location >= 0) {
      glUniform1f(location, value)
    }
  }
  
  /**
   * Set uniform int
   */
  def setUniform(name: String, value: Int): Unit = {
    val location = getUniformLocation(name)
    if (location >= 0) {
      glUniform1i(location, value)
    }
  }
  
  /**
   * Set uniform vec2
   */
  def setUniform(name: String, x: Float, y: Float): Unit = {
    val location = getUniformLocation(name)
    if (location >= 0) {
      glUniform2f(location, x, y)
    }
  }
  
  /**
   * Set uniform vec3
   */
  def setUniform(name: String, x: Float, y: Float, z: Float): Unit = {
    val location = getUniformLocation(name)
    if (location >= 0) {
      glUniform3f(location, x, y, z)
    }
  }
  
  /**
   * Set uniform vec4
   */
  def setUniform(name: String, x: Float, y: Float, z: Float, w: Float): Unit = {
    val location = getUniformLocation(name)
    if (location >= 0) {
      glUniform4f(location, x, y, z, w)
    }
  }
  
  /**
   * Cleanup resources
   */
  def close(): Unit = {
    if (programId != 0) {
      glDeleteProgram(programId)
      programId = 0
    }
    if (vertexShaderId != 0) {
      glDeleteShader(vertexShaderId)
      vertexShaderId = 0
    }
    if (fragmentShaderId != 0) {
      glDeleteShader(fragmentShaderId)
      fragmentShaderId = 0
    }
  }
}
