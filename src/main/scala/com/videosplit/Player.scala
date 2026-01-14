package com.videosplit

import com.videosplit.player.VideoPlayer
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

object Player {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      printUsage()
      sys.exit(1)
    }

    val videoPath = args(0)
    val fullscreen = !args.contains("--windowed")
    
    logger.info(s"Starting video player: $videoPath (fullscreen: $fullscreen)")
    
    val player = new VideoPlayer(videoPath, fullscreen)
    
    try {
      player.initialize() match {
        case Success(_) =>
          player.run()
        case Failure(e) =>
          logger.error(s"Failed to initialize player: ${e.getMessage}", e)
          e.printStackTrace()
          sys.exit(1)
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error during playback: ${e.getMessage}", e)
        e.printStackTrace()
        sys.exit(1)
    } finally {
      player.close()
    }
  }
  
  def printUsage(): Unit = {
    println("Video Player - Cluster Video Playback")
    println()
    println("Usage:")
    println("  video-player <video.mp4> [--windowed]")
    println()
    println("Options:")
    println("  --windowed  Play in windowed mode (default: fullscreen)")
    println()
    println("Controls:")
    println("  SPACE     - Play/Pause")
    println("  LEFT      - Seek backward 5 seconds")
    println("  RIGHT     - Seek forward 5 seconds")
    println("  HOME      - Seek to start")
    println("  ESC / Q   - Exit")
  }
}
