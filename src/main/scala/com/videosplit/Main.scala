package com.videosplit

import com.videosplit.config.ClusterConfig
import com.videosplit.player.VideoPlayer
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

object Main {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      printUsage()
      sys.exit(1)
    }

    // Parse command line arguments
    val mode = if (args(0).startsWith("--")) {
      args(0).substring(2)
    } else {
      // Default to process mode if no flag
      "process"
    }
    
    mode match {
      case "process" | "p" =>
        val configPath = if (args(0).startsWith("--")) {
          if (args.length < 2) {
            println("Error: config file required for process mode")
            printUsage()
            sys.exit(1)
          }
          args(1)
        } else {
          args(0)
        }
        processVideo(configPath)
        
      case "play" | "player" =>
        println("Note: Use 'Player' main class for video playback:")
        println("  sbt \"runMain com.videosplit.Player <video.mp4>\"")
        println("Or use the video-player script if available.")
        sys.exit(1)
        
      case "help" | "h" =>
        printUsage()
        
      case _ =>
        println(s"Unknown mode: $mode")
        printUsage()
        sys.exit(1)
    }
  }
  
  def printUsage(): Unit = {
    println("Video Split - Cluster Video Processing and Playback")
    println()
    println("Usage:")
    println("  Process mode:")
    println("    video-split <config.json>")
    println("    video-split --process <config.json>")
    println()
    println("  Playback mode:")
    println("    video-split --play <video.mp4> [--windowed]")
    println("    video-split --player <video.mp4> [--windowed]")
    println()
    println("Playback Controls:")
    println("  SPACE     - Play/Pause")
    println("  LEFT      - Seek backward 5 seconds")
    println("  RIGHT     - Seek forward 5 seconds")
    println("  HOME      - Seek to start")
    println("  ESC / Q   - Exit")
  }
  
  def processVideo(configPath: String): Unit = {
    logger.info(s"Loading configuration from: $configPath")
    
    // Load configuration
    val configJson = scala.io.Source.fromFile(configPath).mkString
    ClusterConfig.fromJson(configJson) match {
      case Right(config) =>
        logger.info(s"Loaded configuration with ${config.nodes.size} render nodes")
        
        // Process video
        val processor = new VideoProcessor(config)
        processor.process() match {
          case Success(_) =>
            logger.info("Video processing completed successfully")
          case Failure(e) =>
            logger.error(s"Video processing failed: ${e.getMessage}", e)
            sys.exit(1)
        }
      case Left(error) =>
        logger.error(s"Failed to parse configuration: ${error.getMessage}", error)
        sys.exit(1)
    }
  }
  
  def playVideo(videoPath: String, fullscreen: Boolean): Unit = {
    logger.info(s"Starting video player: $videoPath (fullscreen: $fullscreen)")
    
    val player = new VideoPlayer(videoPath, fullscreen)
    
    try {
      player.initialize() match {
        case Success(_) =>
          player.run()
        case Failure(e) =>
          logger.error(s"Failed to initialize player: ${e.getMessage}", e)
          sys.exit(1)
      }
    } finally {
      player.close()
    }
  }
}
