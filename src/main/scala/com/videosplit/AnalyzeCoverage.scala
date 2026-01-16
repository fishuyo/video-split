package com.videosplit

import com.videosplit.analysis.PixelCoverageAnalyzer
import com.videosplit.config.ClusterConfig
import org.slf4j.LoggerFactory

object AnalyzeCoverage {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("Usage: AnalyzeCoverage <config.json> [output_heatmap.png]")
      sys.exit(1)
    }

    val configPath = args(0)
    val heatmapPath = if (args.length >= 2) args(1) else "coverage_heatmap.png"
    
    logger.info(s"Loading configuration from: $configPath")
    
    // Load configuration
    val configJson = scala.io.Source.fromFile(configPath).mkString
    ClusterConfig.fromJson(configJson) match {
      case Right(config) =>
        logger.info(s"Loaded configuration with ${config.nodes.size} render nodes")
        
        // Analyze coverage
        val analyzer = new PixelCoverageAnalyzer(config)
        val report = analyzer.analyze()
        
        // Generate heatmaps
        val basePath = if (heatmapPath.endsWith(".png")) {
          heatmapPath.substring(0, heatmapPath.length - 4)
        } else {
          heatmapPath
        }
        
        val projectorHeatmapPath = s"${basePath}_projector_coverage.png"
        val densityHeatmapPath = s"${basePath}_pixel_density.png"
        val surfaceDensityHeatmapPath = s"${basePath}_surface_density.png"
        val surfaceDensityAvgPath = s"${basePath}_surface_density_avg.png"
        
        analyzer.generateProjectorCoverageHeatmap(report, projectorHeatmapPath)
        analyzer.generatePixelDensityHeatmap(report, densityHeatmapPath)
        analyzer.generateSurfaceDensityHeatmap(report, surfaceDensityHeatmapPath, overlapMode = "max")
        analyzer.generateSurfaceDensityHeatmap(report, surfaceDensityAvgPath, overlapMode = "avg")
        
        println("\n=== Analysis Complete ===")
        println(s"Projector coverage heatmap saved to: $projectorHeatmapPath")
        println(s"Pixel density heatmap saved to: $densityHeatmapPath")
        println(s"Surface density heatmap (max) saved to: $surfaceDensityHeatmapPath")
        println(s"Surface density heatmap (avg) saved to: $surfaceDensityAvgPath")
        
      case Left(error) =>
        logger.error(s"Failed to parse configuration: ${error.getMessage}", error)
        sys.exit(1)
    }
  }
}
