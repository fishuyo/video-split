package com.videosplit

import com.videosplit.calibration.{CalibrationLoader, WarpMap}
import com.videosplit.config.{ClusterConfig, RenderNodeConfig}
import com.videosplit.transform.SphereProjectionTransformer
import com.videosplit.video.{VideoDecoder, VideoEncoder}
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.{BytePointer, PointerScope}
import org.slf4j.LoggerFactory

import scala.util.{Try, Success, Failure}

/**
 * Main video processor that coordinates decoding, transformation, and encoding
 */
class VideoProcessor(config: ClusterConfig) {
  private val logger = LoggerFactory.getLogger(getClass)
  
  // Reusable buffers for frame combining (to avoid allocations per frame)
  private var combinedFrameBuffer: BytePointer = null
  private var combinedFrame: AVFrame = null
  private var combinedFrameSize: Long = 0
  private var blackArrayBuffer: Array[Byte] = null
  private var pixelBuffer: Array[Byte] = null
  
  // Shared OpenGL context for all nodes (reused to avoid initialization overhead)
  // Note: We'll create it lazily when first needed, with max dimensions
  private var sharedGLContext: Option[com.videosplit.gl.OpenGLContext] = None

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
          
          // Process each render node sequentially
          // Note: Cannot parallelize because OpenGL/LWJGL must run on main thread
          // GLFW is initialized once and reused across all nodes
          try {
            config.nodes.zipWithIndex.foreach { case (nodeConfig, nodeIdx) =>
              logger.info(s"Processing node ${nodeIdx + 1}/${config.nodes.size}: ${nodeConfig.getNodeId}")
              
              // Each node needs its own decoder (seeking to start independently)
              val nodeDecoder = new VideoDecoder(config.inputVideoPath)
              nodeDecoder.initialize() match {
                case Success(_) =>
                  try {
                    processNode(nodeDecoder, nodeConfig, width, height, frameRate, config.inputVideoPath) match {
                      case Success(_) =>
                        logger.info(s"Successfully processed node: ${nodeConfig.getNodeId}")
                      case Failure(e) =>
                        logger.error(s"Failed to process node ${nodeConfig.getNodeId}: ${e.getMessage}", e)
                    }
                  } finally {
                    nodeDecoder.close()
                    // Force GC between nodes to free native memory
                    // This helps release AVFrames and other native resources
                    System.gc()
                    Thread.sleep(300)  // Give GC time to run and finalizers to execute
                  }
                case Failure(e) =>
                  logger.error(s"Failed to initialize decoder for node ${nodeConfig.getNodeId}: ${e.getMessage}", e)
              }
            }
          } finally {
            // Clean up reusable buffers
            if (combinedFrameBuffer != null) {
              av_free(combinedFrameBuffer)
              combinedFrameBuffer = null
            }
            if (combinedFrame != null) {
              av_frame_unref(combinedFrame)
              combinedFrame = null
            }
            blackArrayBuffer = null
            pixelBuffer = null
            
            // Terminate GLFW only after all nodes are processed
            com.videosplit.gl.OpenGLContext.terminateGLFW()
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
    frameRate: org.bytedeco.ffmpeg.avutil.AVRational,
    inputVideoPath: String
  ): Try[Unit] = {
    val outputPath = nodeConfig.getOutputPath(inputVideoPath)
    logger.info(s"Processing node: ${nodeConfig.getNodeId} -> $outputPath")
    
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
      processNodeWithSphereProjection(decoder, nodeConfig, inputWidth, inputHeight, frameRate, nodeCalibration.get, calibrationDir, outputPath)
    } else {
      processNodeSimple(decoder, nodeConfig, inputWidth, inputHeight, frameRate, outputPath)
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
    calibrationDir: String,
    outputPath: String
  ): Try[Unit] = {
    logger.info(s"Using sphere projection for node ${nodeConfig.getNodeId} with ${nodeCal.projectors.length} projectors")
    
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
      return Failure(new RuntimeException(s"No valid warp maps loaded for node ${nodeConfig.getNodeId}"))
    }
    
    // Create transformers for all projectors
    val transformers = warpMaps.map { case (proj, warpMap) =>
      val transformer = new SphereProjectionTransformer(
        inputWidth,
        inputHeight,
        warpMap,
        proj.resolution._1,
        proj.resolution._2
      )
      (proj, transformer)
    }
    
    // Initialize all transformers
    val initializedTransformers = transformers.map { case (proj, transformer) =>
      transformer.initialize() match {
        case Success(_) => Some((proj, transformer))
        case Failure(e) =>
          logger.error(s"Failed to initialize transformer for projector ${proj.index}: ${e.getMessage}", e)
          None
      }
    }.flatten
    
    if (initializedTransformers.isEmpty) {
      return Failure(new RuntimeException(s"No transformers initialized for node ${nodeConfig.getNodeId}"))
    }
    
    try {
      // Calculate decimated dimensions for each projector
      val projectorWidths = initializedTransformers.map { case (_, transformer) => transformer.getDecimatedWidth }
      val projectorHeights = initializedTransformers.map { case (_, transformer) => transformer.getDecimatedHeight }
      
      // Use maximum dimensions for all projectors to maintain alignment
      // This ensures warped pixels align correctly across projectors
      val maxDecimatedWidth = projectorWidths.max
      val maxDecimatedHeight = projectorHeights.max
      
      logger.info(s"Decimated dimensions per projector: ${maxDecimatedWidth}x${maxDecimatedHeight} (using max to maintain alignment)")
      initializedTransformers.zipWithIndex.foreach { case ((proj, transformer), idx) =>
        val w = transformer.getDecimatedWidth
        val h = transformer.getDecimatedHeight
        if (w != maxDecimatedWidth || h != maxDecimatedHeight) {
          logger.warn(s"  Projector ${idx}: ${w}x${h} will be scaled to ${maxDecimatedWidth}x${maxDecimatedHeight} to maintain alignment")
        } else {
          logger.info(s"  Projector ${idx}: ${w}x${h} (matches max)")
        }
      }
      
      // Combined output dimensions (side-by-side)
      val combinedWidth = maxDecimatedWidth * initializedTransformers.length
      val combinedHeight = maxDecimatedHeight
      
      logger.info(s"Combined output dimensions: ${combinedWidth}x${combinedHeight} (${initializedTransformers.length} projectors side-by-side)")
      
      // Create encoder with combined dimensions
      val encoder = new VideoEncoder(
        outputPath,
        combinedWidth,
        combinedHeight,
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
                    
                    logger.info(s"Starting sphere projection processing for node ${nodeConfig.getNodeId}")
                    
                    while (consecutiveNoneCount < 10) {
                      val frameOpt = decoder.readFrame()
                      frameOpt match {
                        case Some(frame) =>
                          consecutiveNoneCount = 0
                          try {
                            if (frame.width() <= 0 || frame.height() <= 0) {
                              logger.warn(s"Skipping invalid frame: ${frame.width()}x${frame.height()}")
                            } else {
                              // Transform frame for all projectors
                              val transformedFrames = initializedTransformers.map { case (proj, transformer) =>
                                transformer.transformFrame(frame) match {
                                  case Success(tf) => Some(tf)
                                  case Failure(e) =>
                                    logger.error(s"Failed to transform frame for projector ${proj.index}: ${e.getMessage}", e)
                                    None
                                }
                              }.flatten
                              
                              if (transformedFrames.nonEmpty && transformedFrames.length == initializedTransformers.length) {
                                // Combine frames side-by-side
                                // Note: Each frame may have different decimated dimensions, but we'll use maxDecimatedWidth x maxDecimatedHeight
                                // and center each frame within that space to maintain alignment
                                combineFramesSideBySide(transformedFrames, maxDecimatedWidth, maxDecimatedHeight, combinedWidth, combinedHeight) match {
                                  case Success(combinedFrame) =>
                                    try {
                                      val combinedWidth_actual = combinedFrame.width()
                                      val combinedHeight_actual = combinedFrame.height()
                                      
                                      if (frameCount == 0) {
                                        logger.info(s"First combined frame: ${combinedWidth_actual}x${combinedHeight_actual}")
                                      }
                                      
                                      // Encode combined frame
                                      if (combinedWidth_actual > 0 && combinedHeight_actual > 0) {
                                        encoder.encodeFrame(combinedFrame) match {
                                          case Success(_) =>
                                            frameCount += 1
                                            if (frameCount % 30 == 0) {
                                              logger.info(s"Processed $frameCount frames for node ${nodeConfig.getNodeId} (${combinedWidth_actual}x${combinedHeight_actual})")
                                            }
                                          case Failure(e) =>
                                            logger.error(s"Failed to encode frame $frameCount: ${e.getMessage}", e)
                                            return Failure(e)
                                        }
                                      } else {
                                        logger.warn(s"Skipping invalid combined frame: ${combinedWidth_actual}x${combinedHeight_actual}")
                                      }
                                    } finally {
                                      // Unref combined frame after encoding
                                      // The encoder copies the frame data, so we can unref it here
                                      // Rely on JavaCPP finalizers for actual cleanup to avoid crashes
                                      import org.bytedeco.ffmpeg.global.avutil.*
                                      if (combinedFrame != null) {
                                        av_frame_unref(combinedFrame)
                                      }
                                    }
                                  case Failure(e) =>
                                    logger.error(s"Failed to combine frames: ${e.getMessage}", e)
                                    return Failure(e)
                                }
                                
                                // Unref transformed frames after combining
                                // Rely on JavaCPP finalizers for actual cleanup to avoid crashes
                                import org.bytedeco.ffmpeg.global.avutil.*
                                transformedFrames.foreach { frame =>
                                  if (frame != null) {
                                    av_frame_unref(frame)
                                  }
                                }
                              } else {
                                logger.warn(s"Failed to transform frames for all projectors (got ${transformedFrames.length}/${initializedTransformers.length})")
                                // Unref any frames we did get
                                import org.bytedeco.ffmpeg.global.avutil.*
                                transformedFrames.foreach { frame =>
                                  if (frame != null) {
                                    av_frame_unref(frame)
                                  }
                                }
                              }
                            }
                          } finally {
                            // Use PointerScope to ensure temporary frames are cleaned up promptly
                            // This helps prevent memory accumulation
                          }
                        case None =>
                          consecutiveNoneCount += 1
                          if (consecutiveNoneCount >= 3) {
                            logger.info(s"EOF detected after $frameCount frames, flushing encoder...")
                            encoder.finish() match {
                              case Success(_) =>
                                logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.getNodeId}")
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
                        logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.getNodeId}")
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
            // Close transformers (destroy windows but keep GLFW alive for next node)
            // Clean up OpenGL resources explicitly
            initializedTransformers.foreach { case (_, transformer) =>
              transformer.close()
            }
          }
  }
  
  /**
   * Process node with simple passthrough (no transformation)
   */
  /**
   * Combine multiple frames side-by-side into a single frame
   * 
   * @param frames List of frames to combine (one per projector)
   * @param perProjectorWidth Maximum width to use per projector (for alignment)
   * @param perProjectorHeight Maximum height to use per projector (for alignment)
   * @param combinedWidth Total combined width (perProjectorWidth * numProjectors)
   * @param combinedHeight Total combined height (perProjectorHeight)
   * 
   * Note: Each frame may have different actual dimensions due to decimation.
   * We center each frame within its allocated space (perProjectorWidth x perProjectorHeight)
   * to maintain alignment. Boundaries won't bleed because:
   * 1. We're copying pixels directly (not sampling textures)
   * 2. Warp map edges are blended to alpha=0 anyway
   */
  private def combineFramesSideBySide(
    frames: List[AVFrame],
    perProjectorWidth: Int,
    perProjectorHeight: Int,
    combinedWidth: Int,
    combinedHeight: Int
  ): Try[AVFrame] = Try {
    if (frames.isEmpty) {
      throw new RuntimeException("No frames to combine")
    }
    
    // All frames should be RGB24 format
    val frameFormat = AV_PIX_FMT_RGB24
    
    // Allocate combined frame
    val combinedFrame = av_frame_alloc()
    if (combinedFrame == null) {
      throw new RuntimeException("Failed to allocate combined frame")
    }
    
    val numBytes = av_image_get_buffer_size(frameFormat, combinedWidth, combinedHeight, 1)
    val frameBuffer = new BytePointer(av_malloc(numBytes))
    if (frameBuffer == null || frameBuffer.isNull) {
      throw new RuntimeException("Failed to allocate frame buffer")
    }
    
    av_image_fill_arrays(combinedFrame.data(), combinedFrame.linesize(), frameBuffer, frameFormat, combinedWidth, combinedHeight, 1)
    
    // Initialize combined frame to black
    val combinedData = combinedFrame.data(0)
    val combinedLinesize = combinedFrame.linesize(0)
    val totalBytes = combinedHeight * combinedLinesize
    val blackArray = new Array[Byte](totalBytes)
    combinedData.position(0)
    combinedData.put(blackArray, 0, totalBytes)
    
      // Copy pixels from each frame side-by-side
      // IMPORTANT: All frames should have the same dimensions (perProjectorWidth x perProjectorHeight)
      // If a frame is smaller, we need to scale it up to match, not center it
      // This preserves warp calibration alignment
      var xOffset = 0
      frames.foreach { frame =>
        val frameWidth = frame.width()
        val frameHeight = frame.height()
        val frameLinesize = frame.linesize(0)
        
        // Verify frame matches expected dimensions
        if (frameWidth != perProjectorWidth || frameHeight != perProjectorHeight) {
          // Scale frame to match expected dimensions using nearest-neighbor
          // This preserves pixel-to-pixel mapping from warp calibration
          val frameData = frame.data(0)
          
          for (y <- 0 until perProjectorHeight) {
            // Map destination y to source y (scale up)
            val srcY = (y * frameHeight / perProjectorHeight.toDouble).toInt
            val srcOffset = srcY * frameLinesize
            
            for (x <- 0 until perProjectorWidth) {
              // Map destination x to source x (scale up)
              val srcX = (x * frameWidth / perProjectorWidth.toDouble).toInt
              
              val dstOffset = y * combinedLinesize + (xOffset + x) * 3
              val srcPixelOffset = srcOffset + srcX * 3
              
              frameData.position(srcPixelOffset)
              val pixel = new Array[Byte](3)
              frameData.get(pixel, 0, 3)
              
              combinedData.position(dstOffset)
              combinedData.put(pixel, 0, 3)
            }
          }
        } else {
          // Frame already matches expected dimensions - copy directly
          val frameData = frame.data(0)
          
          for (y <- 0 until frameHeight) {
            val srcOffset = y * frameLinesize
            val dstOffset = y * combinedLinesize + xOffset * 3
            val rowBytes = frameWidth * 3
            val srcArray = new Array[Byte](rowBytes)
            frameData.position(srcOffset)
            frameData.get(srcArray, 0, rowBytes)
            combinedData.position(dstOffset)
            combinedData.put(srcArray, 0, rowBytes)
          }
        }
        
        xOffset += perProjectorWidth  // Move to next projector's allocated space
      }
    
    combinedFrame.width(combinedWidth)
    combinedFrame.height(combinedHeight)
    combinedFrame.format(frameFormat)
    
    combinedFrame
  }
  
  private def processNodeSimple(
    decoder: VideoDecoder,
    nodeConfig: RenderNodeConfig,
    inputWidth: Int,
    inputHeight: Int,
    frameRate: org.bytedeco.ffmpeg.avutil.AVRational,
    outputPath: String
  ): Try[Unit] = {
    logger.info(s"Using simple passthrough for node ${nodeConfig.getNodeId}")
    
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
      outputPath,
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
              
              logger.info(s"Starting frame processing for node ${nodeConfig.getNodeId}")
              
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
                              logger.info(s"Processed $frameCount frames for node ${nodeConfig.getNodeId}")
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
                          logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.getNodeId}")
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
                  logger.info(s"Completed processing ${frameCount} frames for node ${nodeConfig.getNodeId}")
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
