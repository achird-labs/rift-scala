/** Host OS/arch → `rift-java-natives` classifier (DESIGN.md §5.2), for the build.
  *
  * A build-time twin of the runtime `rift.bridge.RiftNatives`: the meta-build resolves the natives
  * classifier when wiring the `conformance` module's `Test` dependency, and cannot reach the bridge
  * module it compiles. Kept minimal and deliberately fail-loud (a build must not silently pick the
  * wrong native jar), where the runtime one returns an `Option` for its callers.
  *
  * The embedded transport loads `librift_ffi` from the natives classifier jar matching the host.
  * The published classifiers for `rift-java-natives` are `darwin-aarch64`, `darwin-x86_64`,
  * `linux-aarch64`, `linux-x86_64`, `linux-musl-x86_64`, and `windows-x86_64` (verified on Maven
  * Central for the pinned version). This resolves the glibc `linux-*` variant for Linux hosts (CI
  * runners and typical dev boxes); musl is not auto-detected — a musl host must override.
  */
object RiftNatives {

  def currentClassifier: String =
    classifierFor(sys.props.getOrElse("os.name", ""), sys.props.getOrElse("os.arch", ""))

  /** Pure OS-string/arch-string → classifier, so the mapping is testable without a real host. */
  def classifierFor(osName: String, osArch: String): String = {
    val os = osName.toLowerCase match {
      case n if n.contains("mac") || n.contains("darwin") => "darwin"
      case n if n.contains("win") => "windows"
      case n if n.contains("linux") => "linux"
      case _ => sys.error(s"rift-java-natives: unsupported os.name '$osName'")
    }
    val arch = osArch.toLowerCase match {
      case "aarch64" | "arm64" => "aarch64"
      case "x86_64" | "amd64" | "x64" => "x86_64"
      case _ => sys.error(s"rift-java-natives: unsupported os.arch '$osArch'")
    }
    // Only `windows-x86_64` is published — a windows-aarch64 request has no jar to resolve.
    if (os == "windows" && arch != "x86_64")
      sys.error(s"rift-java-natives: no 'windows-$arch' classifier is published")
    s"$os-$arch"
  }
}
