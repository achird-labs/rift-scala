package io.github.achirdlabs.rift.testcontainers

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Reads what a `RiftContainer` would start with, without Docker (#194). `RiftContainer` applies
  * its upstream trust in `configure()`, which testcontainers calls from `start()`; this runs that
  * same step directly and reads back the env and the CA copy. It lives in rift-java's package
  * because `configure()` is protected and `upstreamCaCopy()` package-private — the same seam
  * rift-java's own `RiftContainerTest` observes through.
  */
object RiftContainerProbe:

  final case class Configured(env: Map[String, String], caCopy: Option[String])

  /** The in-container path rift-java copies a CA PEM to and names in `RIFT_UPSTREAM_CA_FILE`. */
  val UpstreamCaPath: String = RiftContainer.UPSTREAM_CA_PATH

  def configured(container: RiftContainer): Configured =
    container.configure()
    Configured(
      container.getEnvMap.asScala.toMap,
      container
        .upstreamCaCopy()
        .toScala
        .map(t => new String(t.getBytes, StandardCharsets.UTF_8))
    )
