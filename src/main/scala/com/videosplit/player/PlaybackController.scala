package com.videosplit.player

import org.bytedeco.ffmpeg.avutil.AVRational

/**
 * Controls video playback state and timing
 */
class PlaybackController(frameRate: AVRational) {
  private var isPlaying = false  // Start paused
  private var currentFrameIndex = 0L
  private var playbackStartTime = 0L  // System time when playback started
  private var pausedAtFrame = 0L      // Frame index when paused
  
  // Calculate frame duration in milliseconds
  private val frameDurationMs: Double = {
    val fps = frameRate.num().toDouble / frameRate.den().toDouble
    1000.0 / fps
  }
  
  /**
   * Start or resume playback
   */
  def play(): Unit = {
    if (!isPlaying) {
      isPlaying = true
      playbackStartTime = System.currentTimeMillis() - (pausedAtFrame * frameDurationMs).toLong
    }
  }
  
  /**
   * Pause playback
   */
  def pause(): Unit = {
    if (isPlaying) {
      isPlaying = false
      pausedAtFrame = currentFrameIndex
    }
  }
  
  /**
   * Toggle play/pause
   */
  def togglePlayPause(): Unit = {
    if (isPlaying) pause() else play()
  }
  
  /**
   * Seek to specific frame index
   */
  def seekToFrame(frameIndex: Long): Unit = {
    currentFrameIndex = math.max(0, frameIndex)
    pausedAtFrame = currentFrameIndex
    if (isPlaying) {
      playbackStartTime = System.currentTimeMillis() - (currentFrameIndex * frameDurationMs).toLong
    }
  }
  
  /**
   * Seek to specific timestamp (seconds)
   */
  def seekToTime(seconds: Double): Unit = {
    val fps = frameRate.num().toDouble / frameRate.den().toDouble
    val frameIndex = (seconds * fps).toLong
    seekToFrame(frameIndex)
  }
  
  /**
   * Get current playback position in seconds
   */
  def getCurrentTime: Double = {
    currentFrameIndex * frameDurationMs / 1000.0
  }
  
  /**
   * Get current frame index
   */
  def getCurrentFrameIndex: Long = currentFrameIndex
  
  /**
   * Check if we should advance to next frame based on timing
   * Returns true if it's time to show the next frame
   */
  def shouldAdvanceFrame: Boolean = {
    if (!isPlaying) return false
    
    val elapsed = System.currentTimeMillis() - playbackStartTime
    val expectedFrame = (elapsed / frameDurationMs).toLong
    
    expectedFrame > currentFrameIndex
  }
  
  /**
   * Advance to next frame (call when rendering a frame)
   */
  def advanceFrame(): Unit = {
    if (isPlaying) {
      val elapsed = System.currentTimeMillis() - playbackStartTime
      currentFrameIndex = (elapsed / frameDurationMs).toLong
    }
  }
  
  /**
   * Get playback state
   */
  def isPlaybackPlaying: Boolean = isPlaying
  
  /**
   * Get frame duration in milliseconds
   */
  def getFrameDurationMs: Double = frameDurationMs
}
