package rift.bridge

import java.net.URI

import munit.FunSuite

import rift.model.Protocol

/** #199 — `ConnectConfig.hostResolver` reaches rift-java 0.3.2's protocol-aware `HostResolver`
  * seam. Each case is read back through the built `ConnectOptions`, so these pin what rift-java
  * will actually report from an imposter's `uri()`, not what the Scala side intends.
  */
class HostResolverSpec extends FunSuite:

  private val admin = URI.create("http://127.0.0.1:2525")

  private def resolve(config: ConnectConfig, protocol: String, port: Int): URI =
    config.toOptions.hostResolver().resolve(protocol, port)

  test("no resolver: rift-java's default follows the imposter's protocol"):
    val c = ConnectConfig(adminUri = admin)
    assertEquals(resolve(c, "https", 4545), URI.create("https://127.0.0.1:4545"))
    assertEquals(resolve(c, "http", 4546), URI.create("http://127.0.0.1:4546"))

  test("ByProtocol hands the resolver the imposter's protocol and port"):
    val c = ConnectConfig(
      adminUri = admin,
      hostResolver = Some(HostResolver.ByProtocol { (protocol, port) =>
        val scheme = protocol match
          case Protocol.Http => "http"
          case Protocol.Https => "https"
        URI.create(s"$scheme://sut-visible.example:${port + 10000}")
      })
    )
    assertEquals(resolve(c, "https", 4545), URI.create("https://sut-visible.example:14545"))
    assertEquals(resolve(c, "http", 4545), URI.create("http://sut-visible.example:14545"))

  test("ByPort's URI is used verbatim, scheme included, even for an https imposter"):
    val gateway = HostResolver.ByPort(port => URI.create(s"http://127.0.0.1:2525/__rift/$port"))
    val c = ConnectConfig(adminUri = admin, hostResolver = Some(gateway))
    assertEquals(resolve(c, "https", 4545), URI.create("http://127.0.0.1:2525/__rift/4545"))

  test("ByProtocol refuses a protocol no engine can report, instead of guessing a scheme"):
    val c = ConnectConfig(
      adminUri = admin,
      hostResolver = Some(HostResolver.ByProtocol((_, port) => URI.create(s"http://h:$port")))
    )
    val e = intercept[IllegalStateException](resolve(c, "tcp", 4545))
    assert(e.getMessage.contains("tcp"), e.getMessage)
