package com.videosplit.test

import com.videosplit.calibration.{CalibrationLoader, WarpMap}

/**
 * Test program to load and inspect calibration files
 */
object CalibrationTest {
  def main(args: Array[String]): Unit = {
    val calibrationDir = if (args.length > 0) args(0) else "~/_work/ARG/calibration/calibration-current"
    val hostname = if (args.length > 1) args(1) else "gr01"
    
    val expandedDir = calibrationDir.replaceFirst("^~", System.getProperty("user.home"))
    
    println(s"Loading calibration for hostname: $hostname")
    println(s"Calibration directory: $expandedDir")
    println()
    
    // Load node calibration
    CalibrationLoader.loadNodeCalibration(hostname, expandedDir) match {
      case Some(nodeCal) =>
        println(s"Found calibration for: ${nodeCal.hostname}")
        println(s"Number of projectors: ${nodeCal.projectors.length}")
        println()
        
        nodeCal.projectors.zipWithIndex.foreach { case (proj, idx) =>
          println(s"Projector ${idx + 1}:")
          println(s"  ID: ${proj.index}")
          println(s"  Resolution: ${proj.resolution._1}x${proj.resolution._2}")
          println(s"  Warp map path: ${proj.warpMapPath}")
          
          // Load warp map
          val warpMapPath = s"$expandedDir/${proj.warpMapPath}"
          println(s"  Loading warp map from: $warpMapPath")
          
          CalibrationLoader.loadWarpMap(warpMapPath, proj.resolution._1, proj.resolution._2) match {
            case Some(warpMap) =>
              println(s"  Successfully loaded warp map: ${warpMap.width}x${warpMap.height}")
              println()
              
              // Print first few pixels
              println(s"  First 10 pixels (x, y, z, alpha):")
              val printCount = math.min(10, warpMap.width * warpMap.height)
              var printed = 0
              
              for (y <- 0 until warpMap.height if printed < printCount) {
                for (x <- 0 until warpMap.width if printed < printCount) {
                  val (dirX, dirY, dirZ, alpha) = warpMap.getPixel(x, y)
                  val (u, v) = warpMap.getEquirectangularUV(x, y)
                  
                  println(f"    Pixel ($x%4d, $y%4d): dir=($dirX%8.4f, $dirY%8.4f, $dirZ%8.4f), alpha=$alpha%6.4f, uv=($u%6.4f, $v%6.4f)")
                  printed += 1
                }
              }
              
              // Print some statistics
              println()
              println("  Statistics:")
              
              var minX, maxX, minY, maxY, minZ, maxZ = Float.MaxValue
              var minAlpha, maxAlpha = Float.MaxValue
              var minU, maxU, minV, maxV = Float.MaxValue
              
              for (y <- 0 until warpMap.height) {
                for (x <- 0 until warpMap.width) {
                  val (dirX, dirY, dirZ, alpha) = warpMap.getPixel(x, y)
                  val (u, v) = warpMap.getEquirectangularUV(x, y)
                  
                  minX = math.min(minX, dirX)
                  maxX = math.max(maxX, dirX)
                  minY = math.min(minY, dirY)
                  maxY = math.max(maxY, dirY)
                  minZ = math.min(minZ, dirZ)
                  maxZ = math.max(maxZ, dirZ)
                  minAlpha = math.min(minAlpha, alpha)
                  maxAlpha = math.max(maxAlpha, alpha)
                  minU = math.min(minU, u)
                  maxU = math.max(maxU, u)
                  minV = math.min(minV, v)
                  maxV = math.max(maxV, v)
                }
              }
              
              println(f"    Direction X: [$minX%8.4f, $maxX%8.4f]")
              println(f"    Direction Y: [$minY%8.4f, $maxY%8.4f]")
              println(f"    Direction Z: [$minZ%8.4f, $maxZ%8.4f]")
              println(f"    Alpha: [$minAlpha%6.4f, $maxAlpha%6.4f]")
              println(f"    Equirectangular U: [$minU%6.4f, $maxU%6.4f]")
              println(f"    Equirectangular V: [$minV%6.4f, $maxV%6.4f]")
              println()
              
            case None =>
              println(s"  Failed to load warp map")
              println()
          }
        }
        
      case None =>
        println(s"No calibration found for hostname: $hostname")
        println(s"Looking for file: $expandedDir/$hostname.txt")
        System.exit(1)
    }
    
    println("Test completed successfully!")
  }
}
