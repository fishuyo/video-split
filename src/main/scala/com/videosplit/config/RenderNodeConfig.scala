package com.videosplit.config

import io.circe.generic.auto.*
import io.circe.*

/**
 * Configuration for a single render node in the cluster
 * hostname is used as the node identifier
 */
case class RenderNodeConfig(
  hostname: String,  // Hostname (used as node ID)
  outputPath: Option[String] = None,  // Optional output path (defaults to inputName_hostname.mp4)
  calibrationDir: Option[String] = None,  // Directory containing calibration files
  shaderPath: Option[String] = None,
  transformParams: Option[Map[String, Double]] = None,
  cropRegion: Option[CropRegion] = None,
  scale: Option[Scale] = None
) {
  def getTransformParams: Map[String, Double] = transformParams.getOrElse(Map.empty)
  
  // Hostname is the node ID
  def getHostname: String = hostname
  def getNodeId: String = hostname
  
  // Generate default output path from input video name
  def getOutputPath(inputVideoPath: String): String = {
    outputPath.getOrElse {
      val inputFile = new java.io.File(inputVideoPath)
      val inputName = inputFile.getName
      val baseName = if (inputName.contains(".")) {
        inputName.substring(0, inputName.lastIndexOf("."))
      } else {
        inputName
      }
      val extension = if (inputName.contains(".")) {
        inputName.substring(inputName.lastIndexOf("."))
      } else {
        ".mp4"
      }
      s"${baseName}_${hostname}${extension}"
    }
  }
}

case class CropRegion(
  x: Int,
  y: Int,
  width: Int,
  height: Int
)

case class Scale(
  width: Int,
  height: Int
)

/**
 * Cluster configuration containing all render nodes
 */
case class ClusterConfig(
  inputVideoPath: String,
  calibrationDir: Option[String] = None,  // Default calibration directory
  nodes: Seq[RenderNodeConfig]
)

object ClusterConfig {
  def fromJson(json: String): Either[Error, ClusterConfig] = {
    parser.decode[ClusterConfig](json)
  }
}
