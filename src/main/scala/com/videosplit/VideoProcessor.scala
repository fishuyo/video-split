package com.videosplit

import com.videosplit.calibration.{CalibrationLoader, WarpMap}
import com.videosplit.config.{ClusterConfig, RenderNodeConfig}
import com.videosplit.transform.SphereProjectionTransformer
import com.videosplit.video.{VideoDecoder, VideoEncoder}
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Main video processor that coordinates decoding, transformation, and encoding
 */
class VideoProcessor(config: ClusterConfig) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Process video for all render nodes
   */
  def process(): Try[Unit] = {
    logger.info(s"Starting video processing for ${config.nodes.size} render nodes")
    
    // Initialize decoder
    val decoder = new VideoDecoder(config.inputVideoPath)
    decoder.initialize() match {
      case Success(_) =>
        try {
          val width = decoder.getWidth
          val height = decoder.getHeight
          val frameRate = decoder.getFrameRate
          
          logger.info(s"Input video: ${width}x${height}, frame rate: ${frameRate.num()}/${frameRate.den()}")
          
          // Process each render node
          config.nodes.foreach { nodeConfig =>
            processNode(decoder, nodeConfig, width, height, frameRate) match {
              case Success(_) =>
                logger.info(s"Successfully processed node: ${nodeConfig.nodeId}")
              case Failure(e) =>
                logger.error(s"Failed to process node ${nodeConfig.nodeId}: ${e.getMessage}", e)
            }
          }
          
          Success(())
        } finally {
          decoder.close()
        }
      case Failure(e) =>
        logger.error(s"Failed to initialize decoder: ${e.getMessage}", e)
        Failure(e)
    }
  }

  /**
   * Process video for a single render node
   */
  private def processNode(
    decoder: VideoDecoder,
    nodeConfig: RenderNodeConfig,
    inputWidth: Int,
    inputHeight: Int,
    frameRate: org.bytedeco.ffmpeg.avutil.AVRational
  ): Try[Unit] = {
    logger.info(s"Processing node: ${nodeConfig.nodeId} -> ${nodeConfig.outputPath}")
    
    // Get calibration directory (node-specific or global)
    val calibrationDir = nodeConfig.calibrationDir
      .orElse(config.calibrationDir)
      .getOrElse("~/_work/ARG/calibration/calibration-current")
      .replaceFirst("^~", System.getProperty("user.home"))
    
    val hostname = nodeConfig.getHostname
    
    // Load calibration data if hostname is provided
    val nodeCalibration = CalibrationLoader.loadNodeCalibration(hostname, calibrationDir)
    
    // Determine if we're using sphere projection or simple passthrough
    val useSphereProjection = nodeCalibration.isDefined
    
    if (useSphereProjection) {
      processNodeWithSphereProjection(decoder, nodeConfig, inputWidth, inputHeight, frameRate, nodeCalibration.get, calibrationDir)
    } else {
      processNodeSimple(decoder, nodeConfig, inputWidth, inputHeight, frameRate)
    }
  }
  
  /**
   * Process node with sphere projection (warp maps)
   */
  private def processNodeWithSphereProjection(
    decoder: VideoDecoder,
    nodeConfig: RenderNodeConfig,
    inputWidth: Int,
    inputHeight: Int,
    frameRate: org.bytedeco.ffmpeg.avutil.AVRational,
    nodeCal: com.videosplit.calibration.CalibrationLoader.NodeCalibration,
    calibrationDir: String
  ): Try[Unit] = {
    logger.info(s"Using sphere projection for node ${nodeConfig.nodeId} with ${nodeCal.projectors.length} projectors")
    
    // Load warp maps for all projectors
    val warpMaps = nodeCal.projectors.map { proj =>
      val warpMapPath = s"$calibrationDir/${proj.warpMapPath}"
      CalibrationLoader.loadWarpMap(warpMapPath, proj.resolution._1, proj.resolution._2) match {
        case Some(map) => Some((proj, map))
        case None =>
          logger.error(s"Failed to load warp map for projector ${proj.index}: $warpMapPath")
          None
      }
    }.flatten
    
    if (warpMaps.isEmpty) {
      return Failure(new RuntimeException(s"No valid warp maps loaded for node ${nodeConfig.nodeId}"))
    }
    
    // For now, process first projector (will extend to multiple projectors side-by-side)
    val firstProj = warpMaps.head._1
    val firstWarpMap = warpMaps.head._2
    
    // Create transformer
    val transformer = new SphereProjectionTransformer(
      inputWidth,
      inputHeight,
      firstWarpMap,
      firstProj.resolution._1,
      firstProj.resolution._2
    )
    
    transformer.initialize() match {
      case Success(_) =>
        try {
          // Get output dimensions from transformer (decimation disabled, so full resolution)
          val outputWidth = transformer.getDecimatedWidth
          val outputHeight = transformer.getDecimatedHeight
          
          logger.info(s"Output dimensions: ${outputWidth}x${outputHeight} (projector: ${firstProj.resolution._1}x${firstProj.resolution._2})")
          
          // Create encoder with output dimensions
          val encoder = new VideoEncoder(
            nodeConfig.outputPath,
            outputWidth,
            outputHeight,
            org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P,
            frameRate
          )
          
          encoder.initialize() match {
            case Success(_) =>
              try {
                decoder.seekToStart() match {
                  case Success(_) =>
                    var frameCount = 0
                    var consecutiveNoneCount = 0
                    
                    logger.info(s"Starting sphere projection processing for node ${nodeConfig.nodeId}")
                    
                    while (consecutiveNoneCount < 10) {
                      val frameOpt = decoder.readFrame()
                      frameOpt match {
                        case Some(frame) =>
                          consecutiveNoneCount = 0
                          try {
                            if (frame.width() <= 0 || frame.height() <= 0) {
                              logger.warn(s"Skipping invalid frame: ${frame.width()}x${frame.height()}")
                            } else {
                              // Transform frame using sphere projection
                              transformer.transformFrame(frame) match {
                                case Success(transformedFrame) =>
                                  val transformedWidth = transformedFrame.width()
                                  val transformedHeight = transformedFrame.height()
                                  
                                  if (frameCount == 0) {
                                    logger.info(s"First transformed frame: ${transformedWidth}x${transformedHeight}")
                                  }
                                  
                                  // Validate transformed frame dimensions
                                  if (transformedWidth > 0 && transformedHeight > 0) {
                                    encoder.encodeFrame(transformedFrame) match {
                                      case Success(_) =>
                                        frameCount += 1
                                        if (frameCount % 30 == 0) {
                                          logger.info(s"Processed $frameCount frames for node ${nodeConfig.nodeId} (${transformedWidth}x${transformedHeight})")
                                        }
                                      case Failure(e) =>
                                        logger.error(s"Failed to encode frame $frameCount: ${e.getMessage}", e)
                                        return Failure(e)
                                    }
                                  } else {
                                    logger.warn(s"Skipping invalid transformed frame: ${transformedWidth}x${transformedHeight}")
                                  }
                                case Failure(e) =>
                                  logger.error(s"Failed to transform frame $frameCount: ${e.getMessage}", e)
                                  return Failure(e)
                              }
                            }
                          } finally {
                            // Frames will be cleaned up by JavaCPP finalizers
                          }
                        case None =>
                          consecutiveNoneCount += 1
                          if (consecutiveNoneCount >= 3) {
                            logger.info(s"EOF detected after $frameCount frames, flushing encoder...")
                            encoder.finish() match {
                              case Success(_) =>
                                logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.nodeId}")
                                return Success(())
                              case Failure(e) =>
                                logger.error(s"Failed to finalize encoding: ${e.getMessage}", e)
                                return Failure(e)
                            }
                          }
                      }
                    }
                    
                    encoder.finish() match {
                      case Success(_) =>
                        logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.nodeId}")
                        Success(())
                      case Failure(e) =>
                        logger.error(s"Failed to finalize encoding: ${e.getMessage}", e)
                        Failure(e)
                    }
                  case Failure(e) =>
                    logger.error(s"Failed to seek decoder: ${e.getMessage}", e)
                    Failure(e)
                }
              } finally {
                encoder.close()
              }
            case Failure(e) =>
              logger.error(s"Failed to initialize encoder: ${e.getMessage}", e)
              Failure(e)
          }
        } finally {
          transformer.close()
        }
      case Failure(e) =>
        logger.error(s"Failed to initialize transformer: ${e.getMessage}", e)
        Failure(e)
    }
  }
  
  /**
   * Process node with simple passthrough (no transformation)
   */
  private def processNodeSimple(
    decoder: VideoDecoder,
    nodeConfig: RenderNodeConfig,
    inputWidth: Int,
    inputHeight: Int,
    frameRate: org.bytedeco.ffmpeg.avutil.AVRational
  ): Try[Unit] = {
    logger.info(s"Using simple passthrough for node ${nodeConfig.nodeId}")
    
    // Determine output dimensions
    val (outputWidth, outputHeight) = nodeConfig.scale match {
      case Some(scale) => (scale.width, scale.height)
      case None => nodeConfig.cropRegion match {
        case Some(crop) => (crop.width, crop.height)
        case None => (inputWidth, inputHeight)
      }
    }
    
    // Create encoder
    val encoder = new VideoEncoder(
      nodeConfig.outputPath,
      outputWidth,
      outputHeight,
      org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P,
      frameRate
    )
    
    // Initialize encoder
    encoder.initialize() match {
      case Success(_) =>
        try {
          decoder.seekToStart() match {
            case Success(_) =>
              var frameCount = 0
              var consecutiveNoneCount = 0
              
              logger.info(s"Starting frame processing for node ${nodeConfig.nodeId}")
              
              while (consecutiveNoneCount < 10) {
                val frameOpt = decoder.readFrame()
                frameOpt match {
                  case Some(frame) =>
                    consecutiveNoneCount = 0
                    try {
                      if (frame.width() <= 0 || frame.height() <= 0) {
                        logger.warn(s"Skipping invalid frame: ${frame.width()}x${frame.height()}")
                      } else {
                        encoder.encodeFrame(frame) match {
                          case Success(_) =>
                            frameCount += 1
                            if (frameCount % 30 == 0) {
                              logger.info(s"Processed $frameCount frames for node ${nodeConfig.nodeId}")
                            }
                          case Failure(e) =>
                            logger.error(s"Failed to encode frame $frameCount: ${e.getMessage}", e)
                            return Failure(e)
                        }
                      }
                    } finally {
                      // Frames cleaned up by JavaCPP finalizers
                    }
                  case None =>
                    consecutiveNoneCount += 1
                    if (consecutiveNoneCount >= 3) {
                      logger.info(s"EOF detected after $frameCount frames, flushing encoder...")
                      encoder.finish() match {
                        case Success(_) =>
                          logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.nodeId}")
                          return Success(())
                        case Failure(e) =>
                          logger.error(s"Failed to finalize encoding: ${e.getMessage}", e)
                          return Failure(e)
                      }
                    }
                }
              }
              
              encoder.finish() match {
                case Success(_) =>
                  logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.nodeId}")
                  Success(())
                case Failure(e) =>
                  logger.error(s"Failed to finalize encoding: ${e.getMessage}", e)
                  Failure(e)
              }
            case Failure(e) =>
              logger.error(s"Failed to seek decoder: ${e.getMessage}", e)
              Failure(e)
          }
        } finally {
          encoder.close()
        }
      case Failure(e) =>
        Failure(e)
    }
  }
}
