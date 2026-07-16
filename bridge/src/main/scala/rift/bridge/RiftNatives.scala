package rift.bridge

import rift.RiftError

/** Maps `os.name`/`os.arch` to one of the six published `rift-java-natives` classifiers (DESIGN.md
  * §5.2). Fails closed: an unrecognised platform yields `None` rather than a guess that would
  * silently fetch the wrong native library.
  */
object RiftNatives:

  def classifierFor(osName: String, osArch: String): Option[String] =
    val os = osName.toLowerCase
    val arch = normalizeArch(osArch)
    if os.contains("mac") || os.contains("darwin") then darwinClassifier(arch)
    else if os.contains("linux") then linuxClassifier(arch)
    else if os.contains("windows") then windowsClassifier(arch)
    else None

  private def darwinClassifier(arch: String): Option[String] = arch match
    case "aarch64" => Some("darwin-aarch64")
    case "x86_64" => Some("darwin-x86_64")
    case _ => None

  /** Glibc only. The `linux-musl-x86_64` classifier also exists but musl is not detectable from
    * `os.arch`/`os.name` alone — a caller on musl (e.g. Alpine) must pass the classifier explicitly
    * rather than relying on this heuristic guessing wrong.
    */
  private def linuxClassifier(arch: String): Option[String] = arch match
    case "aarch64" => Some("linux-aarch64")
    case "x86_64" => Some("linux-x86_64")
    case _ => None

  private def windowsClassifier(arch: String): Option[String] = arch match
    case "x86_64" => Some("windows-x86_64")
    case _ => None

  private def normalizeArch(arch: String): String = arch.toLowerCase match
    case "arm64" => "aarch64"
    case "x64" | "amd64" => "x86_64"
    case other => other

  /** The classifier for the JVM this code is running on, for wiring the natives dependency
    * (DESIGN.md §5.2's recommended sbt snippet). Throws rather than returning a wrong guess when
    * the platform isn't one of the six published targets.
    */
  def currentClassifier: String =
    val osName = System.getProperty("os.name")
    val osArch = System.getProperty("os.arch")
    classifierFor(osName, osArch).getOrElse(
      throw RiftError.EngineUnavailable(
        s"no published rift-java-natives classifier for os.name=$osName, os.arch=$osArch",
        None
      )
    )
