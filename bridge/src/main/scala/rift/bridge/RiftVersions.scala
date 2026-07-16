package rift.bridge

import io.github.etacassiopeia.rift.RiftVersion

/** The three version pins a consumer needs to reason about compatibility (D9): this build, the
  * pinned rift-java facade, and the engine rift-java transitively pins.
  */
object RiftVersions:
  val riftJava: String = RiftVersion.get()
  val engine: String = RiftVersion.engineVersion()
  val riftScala: String = readRiftScalaVersion()

  /** Read from a build-generated resource (`Compile / resourceGenerators` in build.sbt) rather than
    * hand-maintained, so it can never drift from the actual build version. Falls back to a
    * placeholder only if the generator didn't run (e.g. a stale classpath) — always non-empty.
    */
  private def readRiftScalaVersion(): String =
    val in = getClass.getResourceAsStream("/rift-scala-version.properties")
    if in == null then "0.0.0-SNAPSHOT"
    else
      try
        val props = new java.util.Properties()
        props.load(in)
        Option(props.getProperty("version")).filter(_.nonEmpty).getOrElse("0.0.0-SNAPSHOT")
      finally in.close()
