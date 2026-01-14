name := "video-split"

version := "0.1.0-SNAPSHOT"

scalaVersion := "3.3.4"

// JavaCPP and FFmpeg versions
// Current: JavaCPP 1.5.10, FFmpeg 6.1.1 (stable, known to work)
// Latest: Try JavaCPP 1.5.11+ and FFmpeg 7.x or 8.x for potential memory leak fixes
// To try latest, uncomment the lines below and comment out the current versions
// val javacppVersion = "1.5.10"
// val ffmpegVersion = "6.1.1"
val javacppVersion = "1.5.12" // Latest
val ffmpegVersion = "7.1.1" // Latest FFmpeg 7.x
//val ffmpegVersion = "8.0.1" // Latest FFmpeg 8.x (may require newer JavaCPP)

// Detect OS and architecture for LWJGL natives
lazy val os = System.getProperty("os.name").toLowerCase match {
  case x if x.contains("mac") => "macos"
  case x if x.contains("win") => "windows"
  case _                      => "linux"
}

lazy val arch = System.getProperty("os.arch").toLowerCase match {
  case x if x.contains("aarch64") || x.contains("arm64") => "arm64"
  case _                                                 => "x86_64"
}

val lwjglVersion = "3.3.3"
val lwjglNatives = s"natives-$os-$arch"

libraryDependencies ++= Seq(
  // JavaCPP FFmpeg - platform-specific (includes all native libraries)
  "org.bytedeco" % "ffmpeg-platform" % s"$ffmpegVersion-$javacppVersion",

  // Optional: GPL builds with (almost) everything enabled
  // "org.bytedeco" % "ffmpeg-platform-gpl" % s"$ffmpegVersion-$javacppVersion",

  // JSON parsing for configuration
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  "org.slf4j" % "slf4j-api" % "2.0.9",

  // LWJGL core
  "org.lwjgl" % "lwjgl" % lwjglVersion,
  "org.lwjgl" % "lwjgl-glfw" % lwjglVersion,
  "org.lwjgl" % "lwjgl-opengl" % lwjglVersion,
  "org.lwjgl" % "lwjgl-stb" % lwjglVersion,

  // LWJGL natives
  "org.lwjgl" % "lwjgl" % lwjglVersion classifier lwjglNatives,
  "org.lwjgl" % "lwjgl-glfw" % lwjglVersion classifier lwjglNatives,
  "org.lwjgl" % "lwjgl-opengl" % lwjglVersion classifier lwjglNatives,
  "org.lwjgl" % "lwjgl-stb" % lwjglVersion classifier lwjglNatives
)

// JavaCPP memory limit - configurable via system property
// Set via: sbt -Djavacpp.maxphysicalbytes=4G run
// Or export JAVA_OPTS="-Dorg.bytedeco.javacpp.maxphysicalbytes=4G"
// Lower values (e.g., 2G, 1G) can help identify memory leaks earlier
val maxPhysicalBytes = sys.props.getOrElse("javacpp.maxphysicalbytes", "8G")
javaOptions += s"-Dorg.bytedeco.javacpp.maxphysicalbytes=$maxPhysicalBytes"

// macOS: GLFW requires running on main thread
javaOptions ++= {
  if (System.getProperty("os.name").toLowerCase.contains("mac")) {
    Seq("-XstartOnFirstThread")
  } else {
    Seq.empty
  }
}

// Scala compiler options
scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xfatal-warnings"
)

// Fork JVM to set system properties
fork := true
