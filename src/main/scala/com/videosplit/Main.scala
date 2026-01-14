package com.videosplit

import com.videosplit.config.ClusterConfig
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

object Main {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("Usage: video-split <config.json>")
      sys.exit(1)
    }

    val configPath = args(0)
    
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
}
