package com.videosplit.video

import org.bytedeco.javacpp.*
import org.bytedeco.ffmpeg.avcodec.{AVCodec, AVCodecContext, AVPacket}
import org.bytedeco.ffmpeg.avformat.{AVFormatContext, AVStream}
import org.bytedeco.ffmpeg.avutil.{AVFrame, AVRational}
import org.bytedeco.ffmpeg.swscale.{SwsContext}
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*

import scala.util.{Try, Success, Failure}

/**
 * Video decoder using JavaCPP FFmpeg
 * Based on examples from https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg
 */
class VideoDecoder(inputPath: String) {
  private var formatContext: AVFormatContext = null
  private var codecContext: AVCodecContext = null
  private var codec: AVCodec = null
  private var videoStreamIndex: Int = -1
  private var frame: AVFrame = null
  private var frameRGB: AVFrame = null
  private var packet: AVPacket = null
  private var swsContext: SwsContext = null

  /**
   * Initialize the decoder
   */
  def initialize(): Try[Unit] = Try {
    // Native libraries should auto-load via JavaCPP
    
    // Register all formats and codecs
    avformat_network_init()
    
    // Open video file - avformat_open_input expects a PointerPointer that it will fill
    val formatContextPtr = new PointerPointer[AVFormatContext](1)
    val ret = avformat_open_input(formatContextPtr, new BytePointer(inputPath), null, null)
    if (ret != 0) {
      throw new RuntimeException(s"Could not open file: $inputPath (error code: $ret)")
    }
    formatContext = formatContextPtr.get(classOf[AVFormatContext], 0)
    if (formatContext == null) {
      throw new RuntimeException("Could not allocate format context")
    }

    // Retrieve stream information
    if (avformat_find_stream_info(formatContext, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
      throw new RuntimeException("Could not find stream information")
    }

    // Find the first video stream
    videoStreamIndex = -1
    for (i <- 0 until formatContext.nb_streams()) {
      if (formatContext.streams(i).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
        videoStreamIndex = i
      }
    }

    if (videoStreamIndex == -1) {
      throw new RuntimeException("Could not find video stream")
    }

    // Get a pointer to the codec parameters for the video stream
    val codecParameters = formatContext.streams(videoStreamIndex).codecpar()
    
    // Find the decoder for the video stream
    codec = avcodec_find_decoder(codecParameters.codec_id())
    if (codec == null) {
      throw new RuntimeException("Unsupported codec")
    }

    // Allocate a codec context for the decoder
    codecContext = avcodec_alloc_context3(codec)
    if (codecContext == null) {
      throw new RuntimeException("Could not allocate codec context")
    }

    // Copy codec parameters to codec context
    if (avcodec_parameters_to_context(codecContext, codecParameters) < 0) {
      throw new RuntimeException("Could not copy codec parameters to context")
    }

    // Open codec
    if (avcodec_open2(codecContext, codec, null.asInstanceOf[PointerPointer[Pointer]]) < 0) {
      throw new RuntimeException("Could not open codec")
    }

    // Allocate video frame
    frame = av_frame_alloc()
    frameRGB = av_frame_alloc()
    if (frame == null || frameRGB == null) {
      throw new RuntimeException("Could not allocate frame")
    }

    // Determine required buffer size and allocate buffer
    val numBytes = av_image_get_buffer_size(
      AV_PIX_FMT_RGB24,
      codecContext.width(),
      codecContext.height(),
      1
    )
    val buffer = new BytePointer(av_malloc(numBytes))

    // Assign appropriate parts of buffer to image planes in frameRGB
    av_image_fill_arrays(
      frameRGB.data(),
      frameRGB.linesize(),
      buffer,
      AV_PIX_FMT_RGB24,
      codecContext.width(),
      codecContext.height(),
      1
    )

    // Initialize SWS context for software scaling
    swsContext = sws_getContext(
      codecContext.width(),
      codecContext.height(),
      codecContext.pix_fmt(),
      codecContext.width(),
      codecContext.height(),
      AV_PIX_FMT_RGB24,
      SWS_BILINEAR,
      null,
      null,
      null.asInstanceOf[Array[Double]]
    )

    // Allocate packet
    packet = av_packet_alloc()
    if (packet == null) {
      throw new RuntimeException("Could not allocate packet")
    }
  }

  /**
   * Get video metadata
   */
  def getWidth: Int = if (codecContext != null) codecContext.width() else 0
  def getHeight: Int = if (codecContext != null) codecContext.height() else 0
  def getPixelFormat: Int = if (codecContext != null) codecContext.pix_fmt() else 0
  def getFrameRate: AVRational = {
    if (formatContext != null && videoStreamIndex >= 0) {
      formatContext.streams(videoStreamIndex).r_frame_rate()
    } else {
      new AVRational().num(1).den(1)
    }
  }

  /**
   * Read next frame from video
   * Returns Some(frame) if successful, None if EOF
   */
  def readFrame(): Option[AVFrame] = {
    // Read frames in a loop until we get a video frame or EOF
    var readRet = av_read_frame(formatContext, packet)
    var packetsRead = 0
    var videoPacketsRead = 0
    
    while (readRet >= 0) {
      packetsRead += 1
      
      // Is this a packet from the video stream?
      if (packet.stream_index() == videoStreamIndex) {
        videoPacketsRead += 1
        
        // Decode video frame
        var ret = avcodec_send_packet(codecContext, packet)
        av_packet_unref(packet)  // Unref immediately after sending
        
        if (ret >= 0) {
          // Try to receive frame
          ret = avcodec_receive_frame(codecContext, frame)
          if (ret == 0) {
            // Successfully decoded a frame
            // Convert the image from its native format to RGB
            sws_scale(
              swsContext,
              frame.data(),
              frame.linesize(),
              0,
              codecContext.height(),
              frameRGB.data(),
              frameRGB.linesize()
            )
            
            // Set frame properties for cloned frame
            frameRGB.width(codecContext.width())
            frameRGB.height(codecContext.height())
            frameRGB.format(AV_PIX_FMT_RGB24)
            
            // Create a new frame and copy data to avoid memory leaks
            // Use FFmpeg's buffer management instead of manual allocation
            val clonedFrame = av_frame_alloc()
            if (clonedFrame == null) {
              return None
            }
            
            // Set frame properties BEFORE allocating buffer
            clonedFrame.width(codecContext.width())
            clonedFrame.height(codecContext.height())
            clonedFrame.format(AV_PIX_FMT_RGB24)
            
            // Use av_frame_get_buffer() so FFmpeg manages the buffer lifecycle
            val ret = av_frame_get_buffer(clonedFrame, 1)  // 1 = alignment
            if (ret < 0) {
              av_frame_free(clonedFrame)
              return None
            }
            
            // Copy pixel data from frameRGB to clonedFrame
            val srcData = frameRGB.data(0)
            val dstData = clonedFrame.data(0)
            val srcLinesize = frameRGB.linesize(0)
            val dstLinesize = clonedFrame.linesize(0)
            val height = codecContext.height()
            val width = codecContext.width()
            
            for (y <- 0 until height) {
              val srcOffset = y * srcLinesize
              val dstOffset = y * dstLinesize
              val rowBytes = width * 3
              val rowData = new Array[Byte](rowBytes)
              srcData.position(srcOffset)
              srcData.get(rowData, 0, rowBytes)
              dstData.position(dstOffset)
              dstData.put(rowData, 0, rowBytes)
            }
            
            // Frame properties already set above before buffer allocation
            
            return Some(clonedFrame)
          }
          // If ret != 0, we need more packets, so continue the loop
        }
        // If send failed, continue to next packet
      } else {
        // Not a video packet, skip it
        av_packet_unref(packet)
      }
      
      // Read next packet
      readRet = av_read_frame(formatContext, packet)
    }
    
    // EOF or error
    None
  }

  /**
   * Seek to the beginning of the video
   */
  def seekToStart(): Try[Unit] = Try {
    if (formatContext == null || videoStreamIndex < 0) {
      throw new RuntimeException("Decoder not initialized")
    }
    
    // Flush decoder buffers first to clear any pending frames
    // This ensures we don't have stale frames in the buffer
    avcodec_flush_buffers(codecContext)
    
    // Seek to timestamp 0 (beginning)
    // Use AVSEEK_FLAG_BACKWARD to seek to the first keyframe before timestamp 0
    val ret = av_seek_frame(formatContext, videoStreamIndex, 0, AVSEEK_FLAG_BACKWARD)
    if (ret < 0) {
      throw new RuntimeException(s"Could not seek to start: $ret")
    }
    
    // Flush again after seeking to ensure clean state
    avcodec_flush_buffers(codecContext)
    
    // Unref any packets that might be lingering
    av_packet_unref(packet)
  }

  /**
   * Cleanup resources
   * Note: Minimal cleanup to avoid crashes. JavaCPP finalizers will handle most cleanup.
   */
  def close(): Unit = {
    // Free swsContext explicitly to prevent memory leak
    if (swsContext != null) {
      sws_freeContext(swsContext)
      swsContext = null
    }
    
    // Skip all manual cleanup - let JavaCPP finalizers handle everything
    // Manual cleanup causes crashes (pthread_mutex_lock, strcmp, etc.)
    
    // Just null out references to help GC
    packet = null
    frameRGB = null
    frame = null
    codecContext = null
    formatContext = null
  }
}
