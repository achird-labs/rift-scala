addSbtPlugin(
  "io.github.achird-labs" % "sbt-rift" % sys.props
    .getOrElse("plugin.version", sys.error("plugin.version is not set"))
)
