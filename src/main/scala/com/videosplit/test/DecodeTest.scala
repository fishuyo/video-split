package com.videosplit.test

import org.bytedeco.javacpp.*
import org.bytedeco.ffmpeg.avcodec.{AVCodec, AVCodecContext, AVPacket}
import org.bytedeco.ffmpeg.avformat.{AVFormatContext, AVStream}
import org.bytedeco.ffmpeg.avutil.{AVFrame, AVRational}
import org.bytedeco.ffmpeg.swscale.{SwsContext}
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Simple test: Decode first few frames from video and save as images
 */
object DecodeTest {
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("Usage: DecodeTest <input_video> [num_frames]")
      System.exit(1)
    }
    
    val inputPath = args(0)
    val numFrames = if (args.length > 1) args(1).toInt else 5
    
    println(s"Decoding first $numFrames frames from: $inputPath")
    
    decodeAndSaveFrames(inputPath, numFrames) match {
      case Right(count) =>
        println(s"Successfully decoded and saved $count frames")
      case Left(error) =>
        println(s"Error: $error")
        System.exit(1)
    }
  }
  
  def decodeAndSaveFrames(inputPath: String, numFrames: Int): Either[String, Int] = {
    var formatContext: AVFormatContext = null
    var codecContext: AVCodecContext = null
    var codec: AVCodec = null
    var frame: AVFrame = null
    var frameRGB: AVFrame = null
    var packet: AVPacket = null
    var swsContext: SwsContext = null
    var videoStreamIndex = -1
    
    try {
      // Initialize network
      avformat_network_init()
      
      // Open input file
      val formatContextPtr = new PointerPointer[AVFormatContext](1)
      val ret = avformat_open_input(formatContextPtr, new BytePointer(inputPath), null, null)
      if (ret != 0) {
        return Left(s"Could not open file: $inputPath (error: $ret)")
      }
      formatContext = formatContextPtr.get(classOf[AVFormatContext], 0)
      if (formatContext == null) {
        return Left("Could not allocate format context")
      }
      
      // Find stream info
      val streamInfoRet = avformat_find_stream_info(formatContext, null.asInstanceOf[PointerPointer[Pointer]])
      if (streamInfoRet < 0) {
        return Left("Could not find stream info")
      }
      
      // Find video stream
      videoStreamIndex = -1
      for (i <- 0 until formatContext.nb_streams().toInt) {
        val stream = formatContext.streams(i)
        if (stream != null && stream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
          videoStreamIndex = i
        }
      }
      
      if (videoStreamIndex < 0) {
        return Left("Could not find video stream")
      }
      
      val videoStream = formatContext.streams(videoStreamIndex)
      val codecParams = videoStream.codecpar()
      
      // Find decoder
      codec = avcodec_find_decoder(codecParams.codec_id())
      if (codec == null) {
        return Left("Codec not found")
      }
      
      // Allocate codec context
      codecContext = avcodec_alloc_context3(codec)
      if (codecContext == null) {
        return Left("Could not allocate codec context")
      }
      
      // Copy codec parameters
      if (avcodec_parameters_to_context(codecContext, codecParams) < 0) {
        return Left("Could not copy codec parameters")
      }
      
      // Open codec
      if (avcodec_open2(codecContext, codec, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
        return Left("Could not open codec")
      }
      
      // Allocate frames
      frame = av_frame_alloc()
      frameRGB = av_frame_alloc()
      packet = av_packet_alloc()
      
      if (frame == null || frameRGB == null || packet == null) {
        return Left("Could not allocate frames/packet")
      }
      
      // Allocate RGB frame buffer
      val frameWidth = codecContext.width()
      val frameHeight = codecContext.height()
      val numBytes = av_image_get_buffer_size(
        AV_PIX_FMT_RGB24,
        frameWidth,
        frameHeight,
        1
      )
      val buffer = new BytePointer(av_malloc(numBytes))
      val fillRet = av_image_fill_arrays(
        frameRGB.data(),
        frameRGB.linesize(),
        buffer,
        AV_PIX_FMT_RGB24,
        frameWidth,
        frameHeight,
        1
      )
      
      if (fillRet < 0) {
        return Left(s"Could not fill RGB arrays: $fillRet")
      }
      
      // Set frame properties AFTER filling arrays
      frameRGB.width(frameWidth)
      frameRGB.height(frameHeight)
      frameRGB.format(AV_PIX_FMT_RGB24)
      
      val rgbLinesize = frameRGB.linesize(0)
      println(s"RGB frame setup: ${frameRGB.width()}x${frameRGB.height()}, format=${frameRGB.format()}, linesize=$rgbLinesize")
      
      // Initialize swscale - convert to RGB24
      // RGB24 in FFmpeg is stored as R, G, B bytes (not BGR)
      val inputPixFmt = codecContext.pix_fmt()
      println(s"Input pixel format: $inputPixFmt (0=yuv420p)")
      println(s"Target pixel format: ${AV_PIX_FMT_RGB24}")
      
      swsContext = sws_getContext(
        codecContext.width(),
        codecContext.height(),
        inputPixFmt,
        codecContext.width(),
        codecContext.height(),
        AV_PIX_FMT_RGB24,
        SWS_BILINEAR,
        null,
        null,
        null.asInstanceOf[Array[Double]]
      )
      
      if (swsContext == null) {
        return Left("Could not initialize swscale context")
      }
      
      println(s"Video: ${codecContext.width()}x${codecContext.height()}, format: ${codecContext.pix_fmt()}")
      
      // Read and decode frames
      var frameCount = 0
      var savedCount = 0
      
      while (savedCount < numFrames && av_read_frame(formatContext, packet) >= 0) {
        if (packet.stream_index() == videoStreamIndex) {
          // Send packet to decoder
          var ret = avcodec_send_packet(codecContext, packet)
          av_packet_unref(packet)
          
          if (ret >= 0) {
            // Receive frame from decoder
            ret = avcodec_receive_frame(codecContext, frame)
            if (ret == 0) {
              frameCount += 1
              
              // Check decoded frame properties
              val decodedWidth = frame.width()
              val decodedHeight = frame.height()
              val decodedFormat = frame.format()
              val decodedPts = frame.pts()
              println(s"Decoded frame $frameCount: ${decodedWidth}x${decodedHeight}, format=$decodedFormat, pts=$decodedPts")
              
              // Convert to RGB using swscale
              val frameHeight = codecContext.height()
              val frameWidth = codecContext.width()
              
              // Convert using swscale
              val scaleRet = sws_scale(
                swsContext,
                frame.data(),
                frame.linesize(),
                0,
                frameHeight,
                frameRGB.data(),
                frameRGB.linesize()
              )
              
              if (scaleRet != frameHeight) {
                println(s"Warning: sws_scale returned $scaleRet, expected $frameHeight")
              } else {
                println(s"sws_scale converted $scaleRet lines successfully")
              }
              
              // Verify RGB frame format
              val rgbFormat = frameRGB.format()
              val rgbWidth = frameRGB.width()
              val rgbHeight = frameRGB.height()
              val rgbDataPtr = frameRGB.data(0)
              println(s"RGB frame: ${rgbWidth}x${rgbHeight}, format=$rgbFormat")
              
              // Check data pointer and first few bytes
              println(s"RGB data pointer address: 0x${java.lang.Long.toHexString(rgbDataPtr.address())}")
              val byte0 = rgbDataPtr.get(0) & 0xFF
              val byte1 = rgbDataPtr.get(1) & 0xFF
              val byte2 = rgbDataPtr.get(2) & 0xFF
              val byte3 = rgbDataPtr.get(3) & 0xFF
              val byte4 = rgbDataPtr.get(4) & 0xFF
              val byte5 = rgbDataPtr.get(5) & 0xFF
              println(s"First 6 bytes: $byte0, $byte1, $byte2, $byte3, $byte4, $byte5 (should be R,G,B,R,G,B for first 2 pixels)")
              
              // Save as image
              val image = frameToBufferedImage(frameRGB, frameWidth, frameHeight)
              val outputFile = new File(s"frame_${savedCount + 1}.png")
              val written = ImageIO.write(image, "png", outputFile)
              if (!written) {
                println(s"Warning: ImageIO.write returned false for ${outputFile.getName}")
              }
              println(s"Saved frame ${savedCount + 1} to ${outputFile.getName} (${outputFile.length()} bytes)")
              savedCount += 1
            }
          }
        } else {
          av_packet_unref(packet)
        }
      }
      
      Right(savedCount)
      
    } catch {
      case e: Exception =>
        Left(s"Exception: ${e.getMessage}\n${e.getStackTrace.mkString("\n")}")
    } finally {
      // Minimal cleanup - test if crashes are from cleanup
      // Comment out cleanup to see if program exits cleanly
      /*
      if (swsContext != null) {
        sws_freeContext(swsContext)
      }
      if (frameRGB != null) {
        av_frame_free(new PointerPointer(frameRGB))
      }
      if (frame != null) {
        av_frame_free(new PointerPointer(frame))
      }
      if (packet != null) {
        av_packet_free(new PointerPointer(packet))
      }
      if (codecContext != null) {
        avcodec_free_context(new PointerPointer(codecContext))
      }
      if (formatContext != null) {
        avformat_close_input(new PointerPointer(formatContext))
      }
      avformat_network_deinit()
      */
      println("Skipping cleanup to test crash source")
    }
  }
  
  def frameToBufferedImage(frame: AVFrame, width: Int, height: Int): BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    
    // RGB24 format: packed RGB, 3 bytes per pixel
    // Format: AV_PIX_FMT_RGB24 = 2
    // Storage: R, G, B bytes for each pixel
    val data = frame.data(0)  // Get pointer to first plane (RGB24 has only one plane)
    val linesize = frame.linesize(0)  // Get linesize for first plane
    val format = frame.format()
    
    println(s"Converting to image: ${width}x${height}, linesize=$linesize, format=$format")
    println(s"Expected linesize: ${width * 3}, actual: $linesize")
    println(s"Data pointer: $data, isNull: ${if (data != null) data.isNull else "null"}")
    
    if (format != AV_PIX_FMT_RGB24) {
      println(s"WARNING: Frame format is $format, expected ${AV_PIX_FMT_RGB24} (RGB24)")
    }
    
    // Verify data pointer
    if (data == null || data.isNull) {
      throw new RuntimeException("Frame data pointer is null")
    }
    
    // Debug: Check if data pointer is actually pointing to different memory locations
    val baseAddress = data.address()
    println(s"Data base address: 0x${java.lang.Long.toHexString(baseAddress)}")
    data.position(0)
    val firstByte = data.get() & 0xFF
    data.position(linesize)
    val secondRowFirstByte = data.get() & 0xFF
    println(s"First row first byte: $firstByte, Second row first byte: $secondRowFirstByte (should differ if rows are different)")
    
    // Read RGB data row by row
    // RGB24 format: each pixel is 3 bytes: R, G, B (not BGR)
    // linesize may be padded/aligned, so we use it for row positioning
    
    // Debug: Check multiple positions to verify data variation
    data.position(0)
    val firstR = data.get() & 0xFF
    val firstG = data.get() & 0xFF
    val firstB = data.get() & 0xFF
    
    // Check a few different positions
    val checkPositions = List(
      (0, 0, "Top-left"),
      (width / 4, height / 4, "Quarter"),
      (width / 2, height / 2, "Center"),
      (width - 1, 0, "Top-right"),
      (0, height - 1, "Bottom-left")
    )
    
    val sampleValues = checkPositions.map { case (x, y, name) =>
      val offset = y * linesize + x * 3
      data.position(offset)
      val r = data.get() & 0xFF
      val g = data.get() & 0xFF
      val b = data.get() & 0xFF
      (name, r, g, b)
    }
    
    println(s"First pixel RGB: R=$firstR, G=$firstG, B=$firstB")
    sampleValues.foreach { case (name, r, g, b) =>
      println(s"  $name (R=$r, G=$g, B=$b)")
    }
    
    // Read image data row by row
    // RGB24 format: packed RGB, 3 bytes per pixel: R, G, B
    // linesize may be >= width*3 due to alignment/padding
    // We use linesize to skip to next row, but only read width*3 bytes
    
    val expectedRowBytes = width * 3
    val actualLinesize = linesize
    
    if (actualLinesize != expectedRowBytes) {
      println(s"Note: linesize ($actualLinesize) != width*3 ($expectedRowBytes), may have padding")
    }
    
    // Copy data to Java byte array for reliable access
    // BytePointer.get(offset) might not work correctly, so copy to array
    val totalBytes = height * linesize
    val byteArray = new Array[Byte](totalBytes)
    data.position(0)
    data.get(byteArray, 0, totalBytes)
    
    println(s"Copied $totalBytes bytes to Java array")
    println("First 12 bytes from array: " + byteArray.take(12).map(_ & 0xFF).mkString(", "))
    
    // Read image data from byte array
    for (y <- 0 until height) {
      val rowOffset = y * linesize
      val row = new Array[Int](width)
      
      for (x <- 0 until width) {
        // Calculate byte index in array
        val pixelIndex = rowOffset + (x * 3)
        
        // Read R, G, B bytes from array
        val r = byteArray(pixelIndex) & 0xFF
        val g = byteArray(pixelIndex + 1) & 0xFF
        val b = byteArray(pixelIndex + 2) & 0xFF
        
        // Pack into ARGB format: 0xAARRGGBB
        val rgb = 0xFF000000 | (r << 16) | (g << 8) | b
        row(x) = rgb
      }
      
      // Verify row has variation (for first few rows)
      if (y < 3 && width > 10) {
        val leftR = (row(0) >> 16) & 0xFF
        val midR = (row(width / 2) >> 16) & 0xFF
        val rightR = (row(width - 1) >> 16) & 0xFF
        if (leftR == midR && midR == rightR) {
          println(s"Row $y: All pixels have R=$leftR (horizontal band)")
        } else {
          println(s"Row $y: R values vary - Left=$leftR, Mid=$midR, Right=$rightR ✓")
        }
      }
      
      // Set entire row at once
      image.setRGB(0, y, width, 1, row, 0, width)
    }
    
    // Sample a few rows to check for horizontal banding
    if (height >= 10) {
      val sampleRows = List(0, height / 4, height / 2, 3 * height / 4, height - 1)
      println("Row samples (checking for horizontal bands):")
      sampleRows.foreach { rowY =>
        val leftPixel = image.getRGB(0, rowY)
        val midPixel = image.getRGB(width / 2, rowY)
        val rightPixel = image.getRGB(width - 1, rowY)
        val rLeft = (leftPixel >> 16) & 0xFF
        val rMid = (midPixel >> 16) & 0xFF
        val rRight = (rightPixel >> 16) & 0xFF
        println(s"  Row $rowY: Left R=$rLeft, Mid R=$rMid, Right R=$rRight")
      }
    }
    
    // Verify final image pixels match what we read
    val topLeft = image.getRGB(0, 0)
    val center = image.getRGB(width / 2, height / 2)
    val bottomLeft = image.getRGB(0, height - 1)
    println(s"Final image pixels - Top-left: 0x${Integer.toHexString(topLeft)}, Center: 0x${Integer.toHexString(center)}, Bottom-left: 0x${Integer.toHexString(bottomLeft)}")
    
    image
  }
}
