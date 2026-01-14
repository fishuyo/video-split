package com.videosplit.video

import org.bytedeco.javacpp.*
import org.bytedeco.ffmpeg.avcodec.{AVCodec, AVCodecContext, AVPacket}
import org.bytedeco.ffmpeg.avformat.{AVFormatContext, AVStream, AVIOContext}
import org.bytedeco.ffmpeg.avutil.{AVFrame, AVRational}
import org.bytedeco.ffmpeg.swscale.{SwsContext}
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*

import scala.util.{Try, Success, Failure}

/**
 * Video encoder using JavaCPP FFmpeg
 */
class VideoEncoder(
  outputPath: String,
  width: Int,
  height: Int,
  pixelFormat: Int = AV_PIX_FMT_YUV420P,
  frameRate: AVRational
) {
  private var formatContext: AVFormatContext = null
  private var codecContext: AVCodecContext = null
  private var codec: AVCodec = null
  private var videoStream: AVStream = null
  private var frame: AVFrame = null
  private var packet: AVPacket = null
  private var frameCount: Long = 0
  private var swsContext: SwsContext = null  // For format conversion

  /**
   * Initialize the encoder
   */
  def initialize(): Try[Unit] = Try {
    // Ensure native libraries are loaded (Loader is already imported via javacpp.*)
    // The libraries should auto-load, but explicit loading can help with initialization order
    
    // Find the H.264 encoder
    codec = avcodec_find_encoder(AV_CODEC_ID_H264)
    if (codec == null) {
      throw new RuntimeException("H.264 encoder not found")
    }

    // Allocate format context - pass filename to let it determine format
    val formatContextPtr = new PointerPointer[AVFormatContext](1)
    val outputPathPtr = new BytePointer(outputPath)
    val ret = avformat_alloc_output_context2(
      formatContextPtr,
      null,
      null,  // Let it guess format from filename
      outputPathPtr
    )
    if (ret < 0) {
      throw new RuntimeException(s"Could not create output context (error: $ret)")
    }
    formatContext = formatContextPtr.get(classOf[AVFormatContext], 0)
    if (formatContext == null) {
      throw new RuntimeException("Could not allocate format context")
    }
    
    // Ensure formatContext is properly initialized
    if (formatContext.oformat() == null) {
      throw new RuntimeException("Format context has no output format")
    }

    // Create video stream
    videoStream = avformat_new_stream(formatContext, codec)
    if (videoStream == null) {
      throw new RuntimeException("Could not create video stream")
    }

    // Allocate codec context
    codecContext = avcodec_alloc_context3(codec)
    if (codecContext == null) {
      throw new RuntimeException("Could not allocate codec context")
    }

    // Set codec parameters
    codecContext.codec_id(codec.id())
    codecContext.codec_type(AVMEDIA_TYPE_VIDEO)
    codecContext.pix_fmt(pixelFormat)
    codecContext.width(width)
    codecContext.height(height)
    // time_base should be den/num (inverse of framerate)
    // framerate = num/den, so time_base = den/num
    codecContext.time_base(new AVRational().num(frameRate.den()).den(frameRate.num()))
    codecContext.framerate(frameRate)
    codecContext.bit_rate(2000000) // 2 Mbps
    codecContext.gop_size(10) // Group of pictures
    
    // Set profile to baseline for better compatibility (avoids OpenH264 warnings)
    // AV_PROFILE_H264_BASELINE = 66
    codecContext.profile(66)

    // Set codec options
    if ((formatContext.oformat().flags() & AVFMT_GLOBALHEADER) != 0) {
      codecContext.flags(codecContext.flags() | AV_CODEC_FLAG_GLOBAL_HEADER)
    }

    // Open codec
    if (avcodec_open2(codecContext, codec, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
      throw new RuntimeException("Could not open codec")
    }

    // Copy codec context to stream
    if (avcodec_parameters_from_context(videoStream.codecpar(), codecContext) < 0) {
      throw new RuntimeException("Could not copy codec parameters")
    }
    
    // Set stream time_base to match codec time_base
    // This must be done AFTER copying parameters but BEFORE writing header
    videoStream.time_base(codecContext.time_base())

    // Open output file - ensure output directory exists
    val outputFile = new java.io.File(outputPath)
    val outputDir = outputFile.getParentFile
    if (outputDir != null && !outputDir.exists()) {
      outputDir.mkdirs()
    }
    
    // Open output file
    // Try using the formatContext.pb() field directly via PointerPointer
    // We need to pass a pointer to where formatContext.pb() is stored
    val absolutePath = outputFile.getAbsolutePath
    
    // Create a PointerPointer that points to formatContext.pb()
    // Since formatContext.pb() returns the value (which may be null), we need to
    // create a PointerPointer that avio_open can write to
    val pbPtr = new PointerPointer[AVIOContext](1)
    
    // Initialize to null
    pbPtr.put(null.asInstanceOf[Pointer])
    
    val pathPtr = new BytePointer(absolutePath)
    
    // Call avio_open - this should allocate an AVIOContext and store it in pbPtr[0]
    // The crash might be due to threading issues in FFmpeg's internal mutex handling
    // Try synchronizing the call
    val ioRet = synchronized {
      avio_open(pbPtr, pathPtr, AVIO_FLAG_WRITE)
    }
    
    if (ioRet < 0) {
      throw new RuntimeException(s"Could not open output file: $absolutePath (error code: $ioRet)")
    }
    
    // Retrieve the AVIOContext
    val pb = pbPtr.get(classOf[AVIOContext], 0)
    if (pb == null) {
      throw new RuntimeException("avio_open succeeded but AVIOContext is null")
    }
    
    // Set in formatContext
    formatContext.pb(pb)

    // Write header
    if (avformat_write_header(formatContext, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
      throw new RuntimeException("Could not write header")
    }

    // Allocate frame
    frame = av_frame_alloc()
    if (frame == null) {
      throw new RuntimeException("Could not allocate frame")
    }
    frame.format(pixelFormat)
    frame.width(width)
    frame.height(height)

    // Allocate frame buffer
    val bufferSize = av_image_get_buffer_size(
      pixelFormat,
      width,
      height,
      1
    )
    val buffer = new BytePointer(av_malloc(bufferSize))
    av_image_fill_arrays(
      frame.data(),
      frame.linesize(),
      buffer,
      pixelFormat,
      width,
      height,
      1
    )

    // Allocate packet
    packet = av_packet_alloc()
    if (packet == null) {
      throw new RuntimeException("Could not allocate packet")
    }
  }

  /**
   * Encode a frame
   * Note: inputFrame is in RGB24 format from decoder, but encoder expects YUV420P
   */
  def encodeFrame(inputFrame: AVFrame): Try[Unit] = Try {
    // Get input frame properties
    val inputFormat = inputFrame.format()
    val inputWidth = inputFrame.width()
    val inputHeight = inputFrame.height()
    
    // Validate input frame
    if (inputWidth <= 0 || inputHeight <= 0) {
      throw new RuntimeException(s"Invalid frame dimensions: ${inputWidth}x${inputHeight}")
    }
    
    // Check if dimensions match - if not, recreate swscale context
    if (swsContext == null || inputWidth != width || inputHeight != height) {
      // Free old context if exists
      if (swsContext != null) {
        sws_freeContext(swsContext)
      }
      
      swsContext = sws_getContext(
        inputWidth,
        inputHeight,
        inputFormat,  // RGB24 from decoder/transformer
        width,        // Output width (encoder target)
        height,       // Output height (encoder target)
        pixelFormat,  // YUV420P for encoder
        SWS_BILINEAR,
        null,
        null,
        null.asInstanceOf[Array[Double]]
      )
      if (swsContext == null) {
        throw new RuntimeException(s"Could not initialize swscale context for ${inputWidth}x${inputHeight} ${inputFormat} -> ${width}x${height} ${pixelFormat}")
      }
    }
    
    // Validate dimensions match encoder expectations
    if (inputWidth != width || inputHeight != height) {
      throw new RuntimeException(s"Frame dimensions mismatch: input ${inputWidth}x${inputHeight} != encoder ${width}x${height}")
    }
    
    // Convert frame format using swscale
    sws_scale(
      swsContext,
      inputFrame.data(),
      inputFrame.linesize(),
      0,
      inputHeight,
      frame.data(),
      frame.linesize()
    )
    
    // Set presentation timestamp
    frame.pts(frameCount)
    frameCount += 1

    // Send frame to encoder
    var ret = avcodec_send_frame(codecContext, frame)
    if (ret < 0) {
      throw new RuntimeException(s"Error sending frame: $ret")
    }

    // Receive packets from encoder
    var done = false
    while (!done && ret >= 0) {
      ret = avcodec_receive_packet(codecContext, packet)
      if (ret < 0) {
        done = true
      } else {
        // Convert packet timestamps from codec time_base to stream time_base
        av_packet_rescale_ts(packet, codecContext.time_base(), videoStream.time_base())
        
        // Set stream index
        packet.stream_index(videoStream.index())
        
        // Write packet
        av_interleaved_write_frame(formatContext, packet)
        av_packet_unref(packet)  // Unref after writing
      }
    }
  }

  /**
   * Finalize encoding (flush encoder and write trailer)
   */
  def finish(): Try[Unit] = Try {
    // Flush encoder
    var ret = avcodec_send_frame(codecContext, null)
    var done = false
    while (!done && ret >= 0) {
      ret = avcodec_receive_packet(codecContext, packet)
      if (ret < 0) {
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

    // Write trailer
    av_write_trailer(formatContext)
  }

  /**
   * Cleanup resources
   * Note: Minimal cleanup to avoid crashes. JavaCPP finalizers will handle most cleanup.
   * Only close I/O to ensure file is written, then let finalizers handle the rest.
   */
  def close(): Unit = {
    // Only close I/O to ensure file is fully written
    // Everything else will be cleaned up by JavaCPP finalizers
    if (formatContext != null && formatContext.pb() != null) {
      try {
        val pbPtr = new PointerPointer[AVIOContext](1)
        pbPtr.put(0, formatContext.pb())
        avio_closep(pbPtr)
      } catch {
        case e: Exception =>
          // Ignore errors - file might already be closed
      }
    }
    
    // Skip all other cleanup - let JavaCPP finalizers handle it
    // Manual cleanup causes crashes (pthread_mutex_lock, strcmp, etc.)
    swsContext = null
    packet = null
    frame = null
    codecContext = null
    formatContext = null
  }
}
