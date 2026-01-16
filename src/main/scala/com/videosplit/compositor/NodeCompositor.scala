package com.videosplit.compositor

import com.videosplit.transform.SphereProjectionTransformer
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.system.MemoryUtil
import scala.util.{Try, Success, Failure}

import scala.util.{Try, Success, Failure}

/**
 * Composites multiple projector frames side-by-side using GPU
 * Much more efficient than CPU-side combining (no intermediate GPU→CPU transfers)
 */
class NodeCompositor(
  transformers: List[SphereProjectionTransformer],
  combinedWidth: Int,
  combinedHeight: Int,
  perProjectorWidth: Int,
  perProjectorHeight: Int
) {
  private var combinedFramebuffer: Int = 0
  private var combinedTexture: Int = 0
  
  /**
   * Initialize the compositor (create combined framebuffer)
   */
  def initialize(): Try[Unit] = Try {
    // Create combined texture
    combinedTexture = glGenTextures()
    glBindTexture(GL_TEXTURE_2D, combinedTexture)
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, combinedWidth, combinedHeight, 0, GL_RGB, GL_UNSIGNED_BYTE, 0)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    
    // Create combined framebuffer
    combinedFramebuffer = glGenFramebuffers()
    glBindFramebuffer(GL_FRAMEBUFFER, combinedFramebuffer)
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, combinedTexture, 0)
    
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
      throw new RuntimeException("Combined framebuffer is not complete!")
    }
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    glBindTexture(GL_TEXTURE_2D, 0)
  }
  
  /**
   * Render all projectors side-by-side and return combined frame
   * This is the main method - renders directly to GPU, then reads back once
   */
  def renderCombined(inputFrame: AVFrame): Try[AVFrame] = Try {
    // Bind combined framebuffer
    glBindFramebuffer(GL_FRAMEBUFFER, combinedFramebuffer)
    
    // Set full viewport for clearing
    glViewport(0, 0, combinedWidth, combinedHeight)
    
    // Clear entire framebuffer to black
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    glClear(GL_COLOR_BUFFER_BIT)
    
    // Disable scissor test (if enabled, it would clip rendering)
    import org.lwjgl.opengl.GL11.*
    val scissorEnabled = glIsEnabled(GL_SCISSOR_TEST)
    if (scissorEnabled) {
      glDisable(GL_SCISSOR_TEST)
    }
    
    // Render each projector to its viewport region
    transformers.zipWithIndex.foreach { case (transformer, idx) =>
      val xOffset = idx * perProjectorWidth
      
      // Get this transformer's actual decimated dimensions
      val transformerDecimatedWidth = transformer.getDecimatedWidth
      val transformerDecimatedHeight = transformer.getDecimatedHeight
      
      // IMPORTANT: We render to the max viewport size (perProjectorWidth x perProjectorHeight)
      // to maintain alignment, but the transformer renders at its actual decimated size.
      // If the transformer's size is smaller, it will render in the top-left of the viewport,
      // leaving the right/bottom edges black (which is correct for alignment).
      glViewport(xOffset, 0, perProjectorWidth, perProjectorHeight)
      
      // Render this projector's warped frame directly to viewport
      // Note: renderToViewport will NOT clear (we already cleared above)
      // The shader uses outputSize uniform which is the transformer's actual decimated size,
      // so it will render correctly sized content within the viewport
      transformer.renderToViewport(inputFrame, clearViewport = false)
    }
    
    // Re-enable scissor test if it was enabled
    if (scissorEnabled) {
      glEnable(GL_SCISSOR_TEST)
    }
    
    // Flush to ensure all rendering completes
    glFinish()
    
    // Read back combined result (single GPU→CPU transfer)
    val combinedFrame = readFramebufferToFrame()
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0)
    
    combinedFrame
  }
  
  /**
   * Read framebuffer to AVFrame
   * IMPORTANT: Must account for stride/padding that FFmpeg may add for alignment
   */
  private def readFramebufferToFrame(): AVFrame = {
    // Set pixel pack alignment to 1 (tightly packed, no padding)
    // This ensures glReadPixels reads data without extra padding
    val oldAlignment = new Array[Int](1)
    glGetIntegerv(GL_PACK_ALIGNMENT, oldAlignment)
    glPixelStorei(GL_PACK_ALIGNMENT, 1)
    
    try {
      // Read pixels from framebuffer (tightly packed, no stride)
      val pixels = new Array[Byte](combinedWidth * combinedHeight * 3)
      val buffer = MemoryUtil.memAlloc(combinedWidth * combinedHeight * 3)
      
      glReadPixels(0, 0, combinedWidth, combinedHeight, GL_RGB, GL_UNSIGNED_BYTE, buffer)
      
      buffer.get(pixels)
      MemoryUtil.memFree(buffer)
      
      // Create AVFrame
      val frame = av_frame_alloc()
      if (frame == null) {
        throw new RuntimeException("Failed to allocate output frame")
      }
      
      // Set frame properties BEFORE allocating buffer
      frame.width(combinedWidth)
      frame.height(combinedHeight)
      frame.format(AV_PIX_FMT_RGB24)
      
      // Use av_frame_get_buffer() so FFmpeg manages the buffer lifecycle
      // This ensures buffers are freed when av_frame_unref() is called
      val ret = av_frame_get_buffer(frame, 1)  // 1 = alignment
      if (ret < 0) {
        av_frame_free(frame)
        throw new RuntimeException(s"Failed to allocate frame buffer: $ret")
      }
      
      // Get actual linesize (may have padding for alignment)
      val data = frame.data(0)
      val linesize = frame.linesize(0)
      val expectedRowBytes = combinedWidth * 3
      
      // Debug: Check if linesize matches expected (warn if padding detected)
      if (linesize != expectedRowBytes) {
        System.err.println(s"WARNING: Linesize mismatch! Width=$combinedWidth, Expected=$expectedRowBytes, Actual=$linesize (padding=${linesize - expectedRowBytes} bytes)")
      }
      
      // Copy pixels (flip vertically - OpenGL origin is bottom-left)
      // Account for potential stride/padding in destination
      val totalBytes = combinedHeight * linesize
      val byteArray = new Array[Byte](totalBytes)
      
      for (y <- 0 until combinedHeight) {
        val srcY = combinedHeight - 1 - y  // Flip vertically
        val srcOffset = srcY * expectedRowBytes  // Source is tightly packed
        val dstOffset = y * linesize  // Destination may have padding
        
        // Copy row (only copy actual pixel data, padding stays as-is)
        System.arraycopy(pixels, srcOffset, byteArray, dstOffset, expectedRowBytes)
      }
      
      // Copy from Java array to BytePointer
      data.position(0)
      data.put(byteArray, 0, totalBytes)
      
      // Frame properties already set above
      frame
    } finally {
      // Restore original pixel pack alignment
      glPixelStorei(GL_PACK_ALIGNMENT, oldAlignment(0))
    }
  }
  
  /**
   * Cleanup resources
   */
  def close(): Unit = {
    if (combinedFramebuffer != 0) {
      glDeleteFramebuffers(combinedFramebuffer)
      combinedFramebuffer = 0
    }
    if (combinedTexture != 0) {
      glDeleteTextures(combinedTexture)
      combinedTexture = 0
    }
  }
}
