package com.videosplit.player

import com.videosplit.video.VideoDecoder
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.*
import org.lwjgl.glfw.GLFW.*
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/** Main video player class Handles video decoding, rendering, and playback
  * control
  */
class VideoPlayer(videoPath: String, fullscreen: Boolean = true) {
  private val logger = LoggerFactory.getLogger(getClass)

  private var decoder: VideoDecoder = _
  private var renderer: FrameRenderer = _
  private var controller: PlaybackController = _
  private var isRunning = false
  private var needsSeek = false
  private var seekTargetFrame = 0L

  /** Initialize player
    */
  def initialize(): Try[Unit] = Try {
    logger.info(s"Initializing video player for: $videoPath")

    // Initialize decoder
    decoder = new VideoDecoder(videoPath)
    decoder.initialize() match {
      case Success(_) =>
        val width = decoder.getWidth
        val height = decoder.getHeight
        val frameRate = decoder.getFrameRate

        logger.info(
          s"Video: ${width}x${height}, frame rate: ${frameRate.num()}/${frameRate.den()}"
        )

        // Initialize playback controller
        controller = new PlaybackController(frameRate)

        // Initialize renderer
        renderer = new FrameRenderer()
        renderer.initialize(fullscreen) match {
          case Success(_) =>
            // Setup input handlers
            setupInputHandlers()
            Success(())
          case Failure(e) =>
            throw new RuntimeException(
              s"Failed to initialize renderer: ${e.getMessage}",
              e
            )
        }
      case Failure(e) =>
        throw new RuntimeException(
          s"Failed to initialize decoder: ${e.getMessage}",
          e
        )
    }
  }

  /** Setup keyboard input handlers
    */
  private def setupInputHandlers(): Unit = {
    val window = renderer.getWindow

    glfwSetKeyCallback(
      window,
      (window: Long, key: Int, scancode: Int, action: Int, mods: Int) => {
        if (action == GLFW_PRESS || action == GLFW_REPEAT) {
          key match {
            case GLFW_KEY_SPACE =>
              controller.togglePlayPause()
              logger.info(s"Playback: ${
                  if (controller.isPlaybackPlaying) "PLAYING" else "PAUSED"
                }")

            case GLFW_KEY_LEFT =>
              val currentTime = controller.getCurrentTime
              val newTime =
                math.max(0, currentTime - 5.0) // Seek back 5 seconds
              controller.seekToTime(newTime)
              seekTargetFrame = controller.getCurrentFrameIndex
              needsSeek = true
              logger.info(s"Seeking to: ${newTime}s (frame ${seekTargetFrame})")

            case GLFW_KEY_RIGHT =>
              val currentTime = controller.getCurrentTime
              val newTime = currentTime + 5.0 // Seek forward 5 seconds
              controller.seekToTime(newTime)
              seekTargetFrame = controller.getCurrentFrameIndex
              needsSeek = true
              logger.info(s"Seeking to: ${newTime}s (frame ${seekTargetFrame})")

            case GLFW_KEY_HOME =>
              controller.seekToTime(0.0) // Seek to start
              seekTargetFrame = 0
              needsSeek = true
              logger.info("Seeking to start")

            case GLFW_KEY_ESCAPE | GLFW_KEY_Q =>
              isRunning = false
              logger.info("Exit requested")

            case _ =>
          }
        }
      }
    )
  }

  /** Run playback loop
    */
  def run(): Unit = {
    isRunning = true

    // Seek to start
    seekToFrame(0)

    // Start paused (showing black screen)
    // controller.pause()

    logger.info(
      "Starting playback loop (paused). Controls: SPACE=play/pause, LEFT/RIGHT=seek, ESC/Q=exit"
    )

    var currentFrame: Option[AVFrame] = None
    var atEndOfVideo = false

    while (isRunning && !renderer.shouldClose) {
      // Poll events (keyboard input)
      renderer.pollEvents()

      // Handle seek if requested
      if (needsSeek) {
        seekToFrame(seekTargetFrame)
        needsSeek = false
        // Clear current frame so we decode fresh after seek
        currentFrame.foreach(av_frame_unref)
        currentFrame = None
        atEndOfVideo = false // Reset EOF flag on seek
      }

      // Check if we should advance frame
      if (controller.shouldAdvanceFrame && !atEndOfVideo) {
        // Decode next frame
        decoder.readFrame() match {
          case Some(frame) =>
            // Unref previous frame
            currentFrame.foreach(av_frame_unref)
            currentFrame = Some(frame)

            // Render frame
            renderer.renderFrame(frame)

            // Advance controller
            controller.advanceFrame()

            // Log progress every 30 frames
            if (controller.getCurrentFrameIndex % 30 == 0) {
              logger.debug(
                s"Frame: ${controller.getCurrentFrameIndex}, Time: ${controller.getCurrentTime}s"
              )
            }

          case None =>
            // EOF - pause instead of exiting
            if (!atEndOfVideo) {
              logger.info("End of video reached - pausing")
              controller.pause()
              atEndOfVideo = true
            }
        }
      } else {
        // Not time for next frame yet (or paused/EOF) - render current frame again
        if (currentFrame.isDefined) {
          renderer.renderFrame(currentFrame.get)
        } else {
          // No frame loaded yet - render black screen
          renderer.renderBlack()
        }

        // Small sleep to avoid busy-waiting
        Thread.sleep(1)
      }
    }

    // Cleanup
    currentFrame.foreach(av_frame_unref)
    logger.info("Playback finished")
  }

  /** Seek decoder to specific frame
    */
  private def seekToFrame(frameIndex: Long): Unit = {
    // For now, seek to start and skip frames
    // TODO: Implement proper seeking using timestamps
    decoder.seekToStart() match {
      case Success(_) =>
        // Skip to desired frame
        var skipped = 0L
        while (skipped < frameIndex) {
          decoder.readFrame() match {
            case Some(frame) =>
              av_frame_unref(frame)
              skipped += 1
            case None =>
              // EOF reached before target frame
              logger.warn(s"EOF reached while seeking to frame $frameIndex")
              return
          }
        }
      case Failure(e) =>
        logger.error(s"Failed to seek: ${e.getMessage}", e)
    }
  }

  /** Cleanup resources
    */
  def close(): Unit = {
    if (renderer != null) {
      renderer.close()
    }
    if (decoder != null) {
      decoder.close()
    }
  }
}
