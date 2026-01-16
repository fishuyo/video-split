package com.videosplit.analysis

import com.videosplit.calibration.{CalibrationLoader, WarpMap}
import com.videosplit.config.{ClusterConfig, RenderNodeConfig}

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.collection.mutable

/**
 * Analyzes pixel coverage across all nodes and projectors
 * Generates statistics and heatmap visualization
 */
class PixelCoverageAnalyzer(config: ClusterConfig) {
  
  /**
   * Analyze coverage and generate report
   */
  def analyze(): CoverageReport = {
    val calibrationDir = config.calibrationDir
      .getOrElse("~/_work/ARG/calibration/calibration-current")
      .replaceFirst("^~", System.getProperty("user.home"))
    
    // Collect all projectors from all nodes
    val allProjectors = mutable.ListBuffer[(String, Int, WarpMap, Int, Int)]() // (nodeId, projIndex, warpMap, width, height)
    
    config.nodes.foreach { nodeConfig =>
      val hostname = nodeConfig.getHostname
      CalibrationLoader.loadNodeCalibration(hostname, calibrationDir) match {
        case Some(nodeCal) =>
          nodeCal.projectors.foreach { proj =>
            val warpMapPath = s"$calibrationDir/${proj.warpMapPath}"
            CalibrationLoader.loadWarpMap(warpMapPath, proj.resolution._1, proj.resolution._2) match {
              case Some(warpMap) =>
                allProjectors += ((hostname, proj.index, warpMap, proj.resolution._1, proj.resolution._2))
              case None =>
                println(s"Warning: Failed to load warp map for ${hostname}/proj${proj.index}")
            }
          }
        case None =>
          println(s"Warning: No calibration found for node $hostname")
      }
    }
    
    println(s"\n=== Pixel Coverage Analysis ===")
    println(s"Total nodes: ${config.nodes.size}")
    println(s"Total projectors: ${allProjectors.size}")
    
    // Calculate total pixels
    val totalPixels = allProjectors.map { case (_, _, _, w, h) => w * h }.sum
    println(s"Total projector pixels: $totalPixels")
    
    // Build equirectangular coverage map
    // Track both:
    // 1. Unique projectors per UV coordinate (how many different projectors sample this UV)
    // 2. Pixel density per UV coordinate (how many projector pixels map to this UV)
    // 3. Surface-based pixel density (accounting for sphere surface area distortion)
    val uvToProjectors = mutable.Map[(Int, Int), mutable.Set[(String, Int)]]() // Which projectors sample each UV
    val uvPixelDensity = mutable.Map[(Int, Int), Int]() // How many projector pixels map to this UV
    val uvSurfaceDensity = mutable.Map[(Int, Int), Double]() // Surface density: pixels per unit surface area
    
    // Precision for UV discretization (higher = more precise but more memory)
    val uvPrecision = 1000  // 0.001 precision
    
    allProjectors.foreach { case (nodeId, projIndex, warpMap, width, height) =>
      var validPixels = 0
      var invalidPixels = 0
      
      for (y <- 0 until height) {
        for (x <- 0 until width) {
          val (dirX, dirY, dirZ, alpha) = warpMap.getPixel(x, y)
          
          // Check if pixel is valid
          val dirLength = math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ)
          if (dirLength > 0.001 && alpha > 0.01) {
            validPixels += 1
            
            // Get equirectangular UV coordinates
            val (u, v) = warpMap.getEquirectangularUV(x, y)
            
            // Discretize UV for counting
            val uDiscrete = (u * uvPrecision).toInt
            val vDiscrete = (v * uvPrecision).toInt
            val key = (uDiscrete, vDiscrete)
            
            // Track which unique projector samples this UV
            uvToProjectors.getOrElseUpdate(key, mutable.Set.empty) += ((nodeId, projIndex))
            
            // Count pixel density (how many projector pixels map to this UV)
            uvPixelDensity(key) = uvPixelDensity.getOrElse(key, 0) + 1
            
            // Calculate surface-based density
            // For equirectangular projection, surface area element is: dA = cos(phi) * dtheta * dphi
            // In UV space: phi = π*(v - 0.5) maps v=[0,1] to phi=[-π/2, π/2]
            // So: dA = cos(phi) * 2π² * du * dv
            // Surface density = pixels / dA = pixels / (cos(phi) * 2π² * du * dv)
            // We'll accumulate pixels and divide by surface area later
            val vCoord = vDiscrete.toDouble / uvPrecision
            val phi = math.Pi * (vCoord - 0.5)  // Elevation angle [-π/2, π/2]
            val cosPhi = math.cos(phi)
            val surfaceAreaWeight = if (cosPhi > 0.001) 1.0 / cosPhi else 0.0  // Weight by inverse surface area
            
            // Accumulate weighted pixel density (pixels per unit surface area)
            uvSurfaceDensity(key) = uvSurfaceDensity.getOrElse(key, 0.0) + surfaceAreaWeight
          } else {
            invalidPixels += 1
          }
        }
      }
      
      println(s"  ${nodeId}/proj${projIndex}: ${width}x${height} = ${width*height} pixels (valid: $validPixels, invalid: $invalidPixels)")
    }
    
    // Calculate unique projector counts per UV coordinate
    val uvProjectorCount = uvToProjectors.map { case (k, v) => (k, v.size) }.toMap
    
    // Calculate unique pixels (non-overlapping equirectangular regions)
    val uniqueEquirectangularPixels = uvProjectorCount.size
    val maxProjectorCoverage = uvProjectorCount.values.maxOption.getOrElse(0)
    val minProjectorCoverage = uvProjectorCount.values.minOption.getOrElse(0)
    val avgProjectorCoverage = if (uvProjectorCount.nonEmpty) uvProjectorCount.values.sum.toDouble / uvProjectorCount.size else 0.0
    
    val maxPixelDensity = uvPixelDensity.values.maxOption.getOrElse(0)
    val minPixelDensity = uvPixelDensity.values.minOption.getOrElse(0)
    val avgPixelDensity = if (uvPixelDensity.nonEmpty) uvPixelDensity.values.sum.toDouble / uvPixelDensity.size else 0.0
    
    // Normalize surface density (convert from weighted sum to actual density per unit area)
    // Surface area of a UV patch: dA = cos(phi) * 2π² * du * dv
    // du = dv = 1/uvPrecision
    val uvPatchArea = (2.0 * math.Pi * math.Pi) / (uvPrecision * uvPrecision)
    val normalizedSurfaceDensity = uvSurfaceDensity.map { case (key, weightedSum) =>
      val vDiscrete = key._2
      val vCoord = vDiscrete.toDouble / uvPrecision
      val phi = math.Pi * (vCoord - 0.5)
      val cosPhi = math.cos(phi)
      val patchArea = cosPhi * uvPatchArea
      // Density = weighted pixels / actual surface area
      // weightedSum already includes 1/cos(phi) factor, so:
      // density = weightedSum / (cos(phi) * uvPatchArea) = weightedSum / (cos(phi) * cos(phi) * 2π² / precision²)
      // Actually, let's recalculate properly:
      val pixelCount = uvPixelDensity.getOrElse(key, 0)
      val density = if (patchArea > 1e-10) pixelCount / patchArea else 0.0
      (key, density)
    }.toMap
    
    val maxSurfaceDensity = normalizedSurfaceDensity.values.maxOption.getOrElse(0.0)
    val minSurfaceDensity = normalizedSurfaceDensity.values.filter(_ > 0).minOption.getOrElse(0.0)
    val avgSurfaceDensity = if (normalizedSurfaceDensity.nonEmpty) {
      val nonZero = normalizedSurfaceDensity.values.filter(_ > 0)
      if (nonZero.nonEmpty) nonZero.sum / nonZero.size else 0.0
    } else 0.0
    
    println(s"\n=== Coverage Statistics ===")
    println(s"Total projector pixels: $totalPixels")
    println(s"Unique equirectangular pixels sampled: $uniqueEquirectangularPixels")
    println(s"Redundancy ratio: ${totalPixels.toDouble / uniqueEquirectangularPixels}")
    println(s"\nProjector Coverage (unique projectors per equirectangular pixel):")
    println(s"  Range: $minProjectorCoverage - $maxProjectorCoverage projectors")
    println(s"  Average: ${avgProjectorCoverage} projectors")
    println(s"\nPixel Density (projector pixels per equirectangular pixel):")
    println(s"  Range: $minPixelDensity - $maxPixelDensity pixels")
    println(s"  Average: ${avgPixelDensity} pixels")
    println(s"\nSurface Pixel Density (projector pixels per unit sphere surface area):")
    println(s"  Range: ${minSurfaceDensity} - ${maxSurfaceDensity} pixels/unit²")
    println(s"  Average: ${avgSurfaceDensity} pixels/unit²")
    println(s"  (Higher density = more projector pixels covering same surface area)")
    
    // Calculate projector coverage distribution
    val projectorCoverageDistribution = mutable.Map[Int, Int]() // projector_count -> number_of_equirect_pixels
    uvProjectorCount.values.foreach { count =>
      projectorCoverageDistribution(count) = projectorCoverageDistribution.getOrElse(count, 0) + 1
    }
    
    println(s"\n=== Projector Coverage Distribution ===")
    println(s"(How many unique projectors sample each equirectangular pixel)")
    projectorCoverageDistribution.toSeq.sortBy(_._1).foreach { case (projectorCount, equirectPixelCount) =>
      val percentage = (equirectPixelCount.toDouble / uniqueEquirectangularPixels * 100)
      println(f"  $projectorCount projectors: $equirectPixelCount equirectangular pixels (${percentage}%.1f%%)")
    }
    
    CoverageReport(
      totalNodes = config.nodes.size,
      totalProjectors = allProjectors.size,
      totalPixels = totalPixels,
      uniqueEquirectangularPixels = uniqueEquirectangularPixels,
      redundancyRatio = totalPixels.toDouble / uniqueEquirectangularPixels,
      maxProjectorCoverage = maxProjectorCoverage,
      minProjectorCoverage = minProjectorCoverage,
      avgProjectorCoverage = avgProjectorCoverage,
      maxPixelDensity = maxPixelDensity,
      minPixelDensity = minPixelDensity,
      avgPixelDensity = avgPixelDensity,
      projectorCoverageDistribution = projectorCoverageDistribution.toMap,
      uvProjectorCount = uvProjectorCount,
      uvPixelDensity = uvPixelDensity.toMap,
      uvSurfaceDensity = normalizedSurfaceDensity,
      uvToProjectors = uvToProjectors.map { case (k, v) => (k, v.toSet) }.toMap
    )
  }
  
  /**
   * Generate heatmap image showing projector coverage (unique projectors per equirectangular pixel)
   */
  def generateProjectorCoverageHeatmap(report: CoverageReport, outputPath: String, equirectWidth: Int = 3840, equirectHeight: Int = 1920): Unit = {
    println(s"\n=== Generating Projector Coverage Heatmap ===")
    println(s"Output: $outputPath")
    println(s"Equirectangular resolution: ${equirectWidth}x${equirectHeight}")
    
    // Create image with coverage accumulator
    val coverageMap = Array.ofDim[Int](equirectHeight, equirectWidth)
    
    // Get max coverage for normalization
    val maxCoverage = report.maxProjectorCoverage
    
    // Map UV projector count to image pixels
    val uvPrecision = 1000
    report.uvProjectorCount.foreach { case ((uDiscrete, vDiscrete), projectorCount) =>
      val u = uDiscrete.toDouble / uvPrecision
      val v = vDiscrete.toDouble / uvPrecision
      
      // Convert UV to image coordinates
      val x = (u * equirectWidth).toInt
      val y = (v * equirectHeight).toInt
      
      // Clamp to image bounds
      val imgX = math.max(0, math.min(equirectWidth - 1, x))
      val imgY = math.max(0, math.min(equirectHeight - 1, y))
      
      // Use maximum projector count for this pixel
      coverageMap(imgY)(imgX) = math.max(coverageMap(imgY)(imgX), projectorCount)
    }
    
    // Create image and render heatmap
    val image = new BufferedImage(equirectWidth, equirectHeight, BufferedImage.TYPE_INT_RGB)
    
    for (y <- 0 until equirectHeight) {
      for (x <- 0 until equirectWidth) {
        val count = coverageMap(y)(x)
        
        if (count > 0) {
          // Calculate color based on projector count (heatmap)
          val intensity = count.toDouble / maxCoverage
          val rgb = heatmapColor(intensity)
          image.setRGB(x, y, rgb)
        } else {
          // No coverage - black
          image.setRGB(x, y, 0x000000)
        }
      }
    }
    
    // Save image
    val outputFile = new File(outputPath)
    ImageIO.write(image, "png", outputFile)
    
    println(s"Heatmap saved to: $outputPath")
    println(s"Projector Coverage visualization:")
    println(s"  Black = No coverage")
    println(s"  Blue = Low coverage (1-2 projectors)")
    println(s"  Green = Medium coverage (3-4 projectors)")
    println(s"  Yellow = High coverage (5-6 projectors)")
    println(s"  Red = Very high coverage (7+ projectors)")
    println(s"  Max coverage: $maxCoverage projectors")
  }
  
  /**
   * Generate heatmap image showing surface-based pixel density
   * Accounts for sphere surface area distortion in equirectangular projection
   */
  def generateSurfaceDensityHeatmap(report: CoverageReport, outputPath: String, equirectWidth: Int = 3840, equirectHeight: Int = 1920, overlapMode: String = "max"): Unit = {
    println(s"\n=== Generating Surface Density Heatmap ===")
    println(s"Output: $outputPath")
    println(s"Equirectangular resolution: ${equirectWidth}x${equirectHeight}")
    println(s"Overlap mode: $overlapMode (max = maximum density, avg = average density)")
    
    // Create image with density accumulator
    val densityMap = Array.ofDim[Double](equirectHeight, equirectWidth)
    val countMap = Array.ofDim[Int](equirectHeight, equirectWidth)  // For averaging
    
    // Get max density for normalization
    val maxDensity = report.uvSurfaceDensity.values.maxOption.getOrElse(0.0)
    
    // Map UV surface density to image pixels
    val uvPrecision = 1000
    report.uvSurfaceDensity.foreach { case ((uDiscrete, vDiscrete), density) =>
      val u = uDiscrete.toDouble / uvPrecision
      val v = vDiscrete.toDouble / uvPrecision
      
      // Convert UV to image coordinates
      val x = (u * equirectWidth).toInt
      val y = (v * equirectHeight).toInt
      
      // Clamp to image bounds
      val imgX = math.max(0, math.min(equirectWidth - 1, x))
      val imgY = math.max(0, math.min(equirectHeight - 1, y))
      
      // Handle overlaps
      overlapMode.toLowerCase match {
        case "avg" | "average" =>
          // Average density when multiple UV samples map to same pixel
          densityMap(imgY)(imgX) += density
          countMap(imgY)(imgX) += 1
        case "max" | "maximum" =>
          // Take maximum density
          densityMap(imgY)(imgX) = math.max(densityMap(imgY)(imgX), density)
        case _ =>
          // Default to max
          densityMap(imgY)(imgX) = math.max(densityMap(imgY)(imgX), density)
      }
    }
    
    // Normalize averages
    if (overlapMode.toLowerCase == "avg" || overlapMode.toLowerCase == "average") {
      for (y <- 0 until equirectHeight) {
        for (x <- 0 until equirectWidth) {
          if (countMap(y)(x) > 0) {
            densityMap(y)(x) = densityMap(y)(x) / countMap(y)(x)
          }
        }
      }
    }
    
    // Create image and render heatmap
    val image = new BufferedImage(equirectWidth, equirectHeight, BufferedImage.TYPE_INT_RGB)
    
    for (y <- 0 until equirectHeight) {
      for (x <- 0 until equirectWidth) {
        val density = densityMap(y)(x)
        
        if (density > 0) {
          // Calculate color based on surface density (heatmap)
          val intensity = math.min(1.0, density / maxDensity)
          val rgb = heatmapColor(intensity)
          image.setRGB(x, y, rgb)
        } else {
          // No coverage - black
          image.setRGB(x, y, 0x000000)
        }
      }
    }
    
    // Save image
    val outputFile = new File(outputPath)
    ImageIO.write(image, "png", outputFile)
    
    println(s"Heatmap saved to: $outputPath")
    println(s"Surface Density visualization:")
    println(s"  Black = No coverage")
    println(s"  Blue = Low density (few pixels per unit surface area)")
    println(s"  Green = Medium density")
    println(s"  Yellow = High density")
    println(s"  Red = Very high density (many pixels per unit surface area)")
    println(s"  Max density: $maxDensity pixels/unit²")
    println(s"  Note: Density varies naturally across sphere surface due to curvature")
  }
  
  /**
   * Generate heatmap image showing pixel density (projector pixels per equirectangular pixel)
   */
  def generatePixelDensityHeatmap(report: CoverageReport, outputPath: String, equirectWidth: Int = 3840, equirectHeight: Int = 1920): Unit = {
    println(s"\n=== Generating Pixel Density Heatmap ===")
    println(s"Output: $outputPath")
    println(s"Equirectangular resolution: ${equirectWidth}x${equirectHeight}")
    
    // Create image with density accumulator
    val densityMap = Array.ofDim[Int](equirectHeight, equirectWidth)
    
    // Get max density for normalization
    val maxDensity = report.maxPixelDensity
    
    // Map UV pixel density to image pixels
    val uvPrecision = 1000
    report.uvPixelDensity.foreach { case ((uDiscrete, vDiscrete), pixelCount) =>
      val u = uDiscrete.toDouble / uvPrecision
      val v = vDiscrete.toDouble / uvPrecision
      
      // Convert UV to image coordinates
      val x = (u * equirectWidth).toInt
      val y = (v * equirectHeight).toInt
      
      // Clamp to image bounds
      val imgX = math.max(0, math.min(equirectWidth - 1, x))
      val imgY = math.max(0, math.min(equirectHeight - 1, y))
      
      // Accumulate pixel density (in case multiple UV samples map to same pixel)
      densityMap(imgY)(imgX) += pixelCount
    }
    
    // Create image and render heatmap
    val image = new BufferedImage(equirectWidth, equirectHeight, BufferedImage.TYPE_INT_RGB)
    
    for (y <- 0 until equirectHeight) {
      for (x <- 0 until equirectWidth) {
        val density = densityMap(y)(x)
        
        if (density > 0) {
          // Calculate color based on pixel density (heatmap)
          val intensity = math.min(1.0, density.toDouble / maxDensity)
          val rgb = heatmapColor(intensity)
          image.setRGB(x, y, rgb)
        } else {
          // No coverage - black
          image.setRGB(x, y, 0x000000)
        }
      }
    }
    
    // Save image
    val outputFile = new File(outputPath)
    ImageIO.write(image, "png", outputFile)
    
    println(s"Heatmap saved to: $outputPath")
    println(s"Pixel Density visualization:")
    println(s"  Black = No pixels")
    println(s"  Blue = Low density")
    println(s"  Green = Medium density")
    println(s"  Yellow = High density")
    println(s"  Red = Very high density")
    println(s"  Max density: $maxDensity projector pixels per equirectangular pixel")
  }
  
  /**
   * Generate heatmap color from intensity (0.0 = black, 1.0 = bright)
   * Uses a blue -> cyan -> green -> yellow -> red heatmap
   * Intensity represents number of projectors sampling this equirectangular pixel
   */
  private def heatmapColor(intensity: Double): Int = {
    val clamped = math.max(0.0, math.min(1.0, intensity))
    
    // Heatmap: Black -> Blue -> Cyan -> Green -> Yellow -> Red -> White
    // For better visualization of coverage density
    val r = if (clamped < 0.2) {
      0
    } else if (clamped < 0.4) {
      ((clamped - 0.2) * 5 * 255).toInt
    } else if (clamped < 0.6) {
      ((clamped - 0.4) * 5 * 255).toInt
    } else if (clamped < 0.8) {
      255
    } else {
      255
    }
    
    val g = if (clamped < 0.2) {
      (clamped * 5 * 255).toInt
    } else if (clamped < 0.4) {
      255
    } else if (clamped < 0.6) {
      255
    } else if (clamped < 0.8) {
      (255 - (clamped - 0.6) * 5 * 255).toInt
    } else {
      ((clamped - 0.8) * 5 * 255).toInt
    }
    
    val b = if (clamped < 0.2) {
      255
    } else if (clamped < 0.4) {
      (255 - (clamped - 0.2) * 5 * 255).toInt
    } else if (clamped < 0.6) {
      0
    } else {
      0
    }
    
    (r << 16) | (g << 8) | b
  }
}

/**
 * Coverage analysis report
 */
case class CoverageReport(
  totalNodes: Int,
  totalProjectors: Int,
  totalPixels: Long,
  uniqueEquirectangularPixels: Int,
  redundancyRatio: Double,
  maxProjectorCoverage: Int,  // Max unique projectors per equirectangular pixel
  minProjectorCoverage: Int,
  avgProjectorCoverage: Double,
  maxPixelDensity: Int,  // Max projector pixels per equirectangular pixel
  minPixelDensity: Int,
  avgPixelDensity: Double,
  projectorCoverageDistribution: Map[Int, Int],  // projector_count -> equirect_pixel_count
  uvProjectorCount: Map[(Int, Int), Int],  // (u_discrete, v_discrete) -> unique projector count
  uvPixelDensity: Map[(Int, Int), Int],  // (u_discrete, v_discrete) -> projector pixel count
  uvSurfaceDensity: Map[(Int, Int), Double],  // (u_discrete, v_discrete) -> surface density (pixels per unit area)
  uvToProjectors: Map[(Int, Int), Set[(String, Int)]]  // Which projectors sample each UV
)
