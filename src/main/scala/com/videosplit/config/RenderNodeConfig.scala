package com.videosplit.config

import io.circe.generic.auto.*
import io.circe.*

/**
 * Configuration for a single render node in the cluster
 */
case class RenderNodeConfig(
  nodeId: String,
  outputPath: String,
  hostname: Option[String] = None,  // Hostname for calibration file lookup
  calibrationDir: Option[String] = None,  // Directory containing calibration files
  shaderPath: Option[String] = None,
  transformParams: Option[Map[String, Double]] = None,
  cropRegion: Option[CropRegion] = None,
  scale: Option[Scale] = None
) {
  def getTransformParams: Map[String, Double] = transformParams.getOrElse(Map.empty)
  
  // Use hostname if provided, otherwise use nodeId
  def getHostname: String = hostname.getOrElse(nodeId)
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
