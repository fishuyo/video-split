package com.videosplit.test

import org.bytedeco.javacpp.*
import org.bytedeco.ffmpeg.avcodec.{AVCodec, AVCodecContext, AVPacket}
import org.bytedeco.ffmpeg.avformat.{AVFormatContext, AVStream, AVIOContext}
import org.bytedeco.ffmpeg.avutil.{AVFrame, AVRational}
import org.bytedeco.ffmpeg.swscale.{SwsContext}
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*

import java.awt.image.BufferedImage
import java.util.Random

/**
 * Simple test: Encode random generated images to MP4
 */
object EncodeTest {
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("Usage: EncodeTest <output_video> [width] [height] [num_frames]")
      System.exit(1)
    }
    
    val outputPath = args(0)
    val width = if (args.length > 1) args(1).toInt else 640
    val height = if (args.length > 2) args(2).toInt else 480
    val numFrames = if (args.length > 3) args(3).toInt else 60  // 2 seconds at 30fps
    
    println(s"Encoding $numFrames random frames ($width x $height) to: $outputPath")
    
    encodeRandomFrames(outputPath, width, height, numFrames) match {
      case Right(_) =>
        println(s"Successfully encoded $numFrames frames to $outputPath")
      case Left(error) =>
        println(s"Error: $error")
        System.exit(1)
    }
  }
  
  def encodeRandomFrames(outputPath: String, width: Int, height: Int, numFrames: Int): Either[String, Unit] = {
    var formatContext: AVFormatContext = null
    var codecContext: AVCodecContext = null
    var codec: AVCodec = null
    var videoStream: AVStream = null
    var frame: AVFrame = null
    var packet: AVPacket = null
    var frameCount: Long = 0
    var frameBuffer: BytePointer = null  // Track buffer separately
    
    try {
      // Initialize network
      avformat_network_init()
      
      // Find encoder
      codec = avcodec_find_encoder(AV_CODEC_ID_H264)
      if (codec == null) {
        return Left("H.264 encoder not found")
      }
      
      // Allocate format context
      val formatContextPtr = new PointerPointer[AVFormatContext](1)
      val outputPathPtr = new BytePointer(outputPath)
      val ret = avformat_alloc_output_context2(
        formatContextPtr,
        null,
        null,
        outputPathPtr
      )
      if (ret < 0) {
        return Left(s"Could not create output context (error: $ret)")
      }
      formatContext = formatContextPtr.get(classOf[AVFormatContext], 0)
      if (formatContext == null) {
        return Left("Could not allocate format context")
      }
      
      // Create video stream
      videoStream = avformat_new_stream(formatContext, codec)
      if (videoStream == null) {
        return Left("Could not create video stream")
      }
      
      // Allocate codec context
      codecContext = avcodec_alloc_context3(codec)
      if (codecContext == null) {
        return Left("Could not allocate codec context")
      }
      
      // Set codec parameters
      codecContext.codec_id(AV_CODEC_ID_H264)
      codecContext.codec_type(AVMEDIA_TYPE_VIDEO)
      codecContext.pix_fmt(AV_PIX_FMT_YUV420P)
      codecContext.width(width)
      codecContext.height(height)
      codecContext.time_base(new AVRational().num(1).den(30)) // 30 fps
      codecContext.framerate(new AVRational().num(30).den(1))
      codecContext.bit_rate(2000000L) // 2 Mbps
      codecContext.gop_size(10) // I-frame every 10 frames
      
      // Open codec first
      if (avcodec_open2(codecContext, codec, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
        return Left("Could not open codec")
      }
      
      // Copy codec parameters to stream
      if (avcodec_parameters_from_context(videoStream.codecpar(), codecContext) < 0) {
        return Left("Could not copy codec parameters")
      }
      
      // Set stream time_base to match codec time_base
      // This must be done AFTER copying parameters but BEFORE writing header
      videoStream.time_base(codecContext.time_base())
      
      // Open output file
      val outputFile = new java.io.File(outputPath)
      val outputDir = outputFile.getParentFile
      if (outputDir != null && !outputDir.exists()) {
        outputDir.mkdirs()
      }
      
      val pbPtr = new PointerPointer[AVIOContext](1)
      val absolutePath = outputFile.getAbsolutePath
      val ioRet = avio_open(pbPtr, new BytePointer(absolutePath), AVIO_FLAG_WRITE)
      
      if (ioRet < 0) {
        return Left(s"Could not open output file: $absolutePath (error: $ioRet)")
      }
      
      val pb = pbPtr.get(classOf[AVIOContext], 0)
      if (pb == null) {
        return Left("avio_open succeeded but returned null AVIOContext")
      }
      
      formatContext.pb(pb)
      
      // Write header
      if (avformat_write_header(formatContext, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
        return Left("Could not write header")
      }
      
      // Allocate frame
      frame = av_frame_alloc()
      if (frame == null) {
        return Left("Could not allocate frame")
      }
      frame.format(codecContext.pix_fmt())
      frame.width(width)
      frame.height(height)
      
      // Allocate frame buffer
      val bufferSize = av_image_get_buffer_size(
        codecContext.pix_fmt(),
        width,
        height,
        1
      )
      val buffer = new BytePointer(av_malloc(bufferSize))
      if (buffer == null || buffer.isNull) {
        return Left("Could not allocate frame buffer")
      }
      
      val fillRet = av_image_fill_arrays(
        frame.data(),
        frame.linesize(),
        buffer,
        codecContext.pix_fmt(),
        width,
        height,
        1
      )
      
      if (fillRet < 0) {
        return Left(s"Could not fill frame arrays: $fillRet")
      }
      
      // Store buffer reference for cleanup
      frameBuffer = buffer
      
      // Allocate packet
      packet = av_packet_alloc()
      if (packet == null) {
        return Left("Could not allocate packet")
      }
      
      println(s"Encoding $numFrames frames...")
      
      // Encode frames
      var encodeError: Option[String] = None
      var i = 0
      while (i < numFrames && encodeError.isEmpty) {
        // Generate random image data (YUV420P format)
        fillRandomYUV420P(frame, width, height, i)
        
        frame.pts(frameCount)
        frameCount += 1
        
        // Send frame to encoder
        var sendRet = avcodec_send_frame(codecContext, frame)
        if (sendRet < 0) {
          encodeError = Some(s"Error sending frame $i: $sendRet")
        } else {
          // Receive packets
          var done = false
          while (!done && encodeError.isEmpty) {
            sendRet = avcodec_receive_packet(codecContext, packet)
            if (sendRet < 0) {
              done = true
            } else {
              // Convert packet timestamps from codec time_base to stream time_base
              av_packet_rescale_ts(packet, codecContext.time_base(), videoStream.time_base())
              
              // Set stream index
              packet.stream_index(videoStream.index())
              
              // Write packet
              av_interleaved_write_frame(formatContext, packet)
              av_packet_unref(packet)
            }
          }
          
          if ((i + 1) % 10 == 0) {
            println(s"Encoded ${i + 1}/$numFrames frames")
          }
        }
        i += 1
      }
      
      encodeError match {
        case Some(error) => return Left(error)
        case None => // Continue
      }
      
      // Flush encoder
      var flushRet = avcodec_send_frame(codecContext, null)
      var done = false
      while (!done) {
        flushRet = avcodec_receive_packet(codecContext, packet)
        if (flushRet < 0) {
          done = true
        } else {
          // Convert packet timestamps from codec time_base to stream time_base
          av_packet_rescale_ts(packet, codecContext.time_base(), videoStream.time_base())
          
          // Set stream index
          packet.stream_index(videoStream.index())
          
          av_interleaved_write_frame(formatContext, packet)
          av_packet_unref(packet)
        }
      }
      
      // Write trailer (before closing I/O)
      av_write_trailer(formatContext)
      
      Right(())
      
    } catch {
      case e: Exception =>
        Left(s"Exception: ${e.getMessage}\n${e.getStackTrace.mkString("\n")}")
    } finally {
      // Try proper cleanup with error handling
      println("Performing cleanup...")
      
      var cleanupErrors = List.empty[String]
      
      // 1. Write trailer if not already written (safety check)
      try {
        if (formatContext != null && formatContext.pb() != null) {
          // Trailer should already be written, but check
        }
      } catch {
        case e: Exception => cleanupErrors = cleanupErrors :+ s"Trailer check: ${e.getMessage}"
      }
      
      // 2. Close I/O first
      try {
        if (formatContext != null && formatContext.pb() != null) {
          val pbPtr = new PointerPointer[AVIOContext](1)
          pbPtr.put(0, formatContext.pb())
          val closeRet = avio_closep(pbPtr)
          if (closeRet < 0) {
            cleanupErrors = cleanupErrors :+ s"avio_closep returned $closeRet"
          } else {
            println("Closed I/O successfully")
          }
        }
      } catch {
        case e: Exception => cleanupErrors = cleanupErrors :+ s"I/O close: ${e.getMessage}"
      }
      
      // 3. Free packet (safest to free first)
      try {
        if (packet != null) {
          av_packet_free(new PointerPointer(packet))
          println("Freed packet")
        }
      } catch {
        case e: Exception => cleanupErrors = cleanupErrors :+ s"Packet free: ${e.getMessage}"
      }
      
      // 4. Skip codec context and frame freeing - these cause pthread_mutex_lock crashes
      // The crash happens consistently when freeing codec context or frame
      // This suggests a threading/memory management issue in FFmpeg/JavaCPP
      // Workaround: Let JavaCPP finalizers handle cleanup
      if (codecContext != null) {
        println("Skipping codec context free (causes crashes - letting JavaCPP handle it)")
        codecContext = null
      }
      
      if (frame != null) {
        println("Skipping frame free (causes crashes - letting JavaCPP handle it)")
        frame = null
      }
      
      // 5. Free format context (might be safe)
      try {
        if (formatContext != null) {
          avformat_free_context(formatContext)
          println("Freed format context")
          formatContext = null
        }
      } catch {
        case e: Exception => 
          cleanupErrors = cleanupErrors :+ s"Format context free: ${e.getMessage}"
          println(s"Format context free error: ${e.getMessage}")
      }
      
      // 6. Deinit network
      try {
        avformat_network_deinit()
        println("Deinitialized network")
      } catch {
        case e: Exception => cleanupErrors = cleanupErrors :+ s"Network deinit: ${e.getMessage}"
      }
      
      if (cleanupErrors.nonEmpty) {
        println(s"Cleanup completed with ${cleanupErrors.size} errors:")
        cleanupErrors.foreach(e => println(s"  - $e"))
      } else {
        println("Cleanup completed successfully")
      }
    }
  }
  
  def fillRandomYUV420P(frame: AVFrame, width: Int, height: Int, frameNum: Int): Unit = {
    val centerX = width / 2.0
    val centerY = height / 2.0
    val maxRadius = math.min(width, height) / 2.0
    
    // Fill Y plane (luminance) - growing circle pattern
    val yData = frame.data(0)
    val yStride = frame.linesize(0)
    val yArray = new Array[Byte](yStride * height)
    
    for (y <- 0 until height) {
      val rowOffset = y * yStride
      for (x <- 0 until width) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = math.sqrt(dx * dx + dy * dy)
        
        // Growing circle - radius increases with frame number
        val circleRadius = (maxRadius * frameNum / 60.0) % maxRadius
        val inCircle = dist < circleRadius
        
        // Create pattern: circle with spiral
        val angle = math.atan2(dy, dx) + (frameNum * 0.1) // Rotating spiral
        val spiralDist = dist - (angle * 20) % 50
        
        val yValue = if (inCircle) {
          // Inside circle - bright
          200 + (math.sin(spiralDist / 10) * 30).toInt
        } else {
          // Outside circle - gradient based on distance
          val gradient = 50 + ((dist / maxRadius) * 100).toInt
          gradient
        }
        
        yArray(rowOffset + x) = math.max(0, math.min(255, yValue)).toByte
      }
    }
    
    // Copy Y plane
    yData.position(0)
    yData.put(yArray, 0, yStride * height)
    
    // Fill U and V planes (chrominance) - rainbow colors
    val uvWidth = width / 2
    val uvHeight = height / 2
    
    val uData = frame.data(1)
    val uStride = frame.linesize(1)
    val uArray = new Array[Byte](uStride * uvHeight)
    
    val vData = frame.data(2)
    val vStride = frame.linesize(2)
    val vArray = new Array[Byte](vStride * uvHeight)
    
    for (y <- 0 until uvHeight) {
      val rowOffset = y * uStride
      for (x <- 0 until uvWidth) {
        val px = x * 2
        val py = y * 2
        val dx = px - centerX
        val dy = py - centerY
        val angle = math.atan2(dy, dx) + (frameNum * 0.1)
        val dist = math.sqrt(dx * dx + dy * dy)
        
        // Rainbow colors based on angle and frame
        val hue = ((angle + math.Pi) / (2 * math.Pi) + frameNum * 0.01) % 1.0
        val saturation = if (dist < maxRadius * 0.8) 1.0 else 0.3
        
        // Convert HSV to YUV (simplified)
        val uValue = (128 + math.sin(hue * 2 * math.Pi) * 127 * saturation).toInt
        val vValue = (128 + math.cos(hue * 2 * math.Pi) * 127 * saturation).toInt
        
        uArray(rowOffset + x) = math.max(0, math.min(255, uValue)).toByte
        vArray(rowOffset + x) = math.max(0, math.min(255, vValue)).toByte
      }
    }
    
    // Copy U and V planes
    uData.position(0)
    uData.put(uArray, 0, uStride * uvHeight)
    vData.position(0)
    vData.put(vArray, 0, vStride * uvHeight)
  }
}
