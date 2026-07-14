package rift.dsl

import rift.model.*

/** Proxy, TCP fault, raw inject, and rift-script response kinds — the non-`is` members of
  * `rift.model.Response` (DESIGN.md §5.1.3).
  */
final class ProxyResponseBuilder private[dsl] (
    private val to: String,
    private val modeValue: ProxyMode = ProxyMode.ProxyAlways,
    private val generatorsValue: Vector[RequestField] = Vector.empty,
    private val addWaitValue: Boolean = false,
    private val injectHeadersValue: Vector[(String, String)] = Vector.empty,
    private val decorateValue: Option[String] = None,
    private val rewriteValue: Option[PathRewrite] = None
) extends ResponseBuilder:
  private def withState(
      modeValue: ProxyMode = this.modeValue,
      generatorsValue: Vector[RequestField] = this.generatorsValue,
      addWaitValue: Boolean = this.addWaitValue,
      injectHeadersValue: Vector[(String, String)] = this.injectHeadersValue,
      decorateValue: Option[String] = this.decorateValue,
      rewriteValue: Option[PathRewrite] = this.rewriteValue
  ): ProxyResponseBuilder =
    new ProxyResponseBuilder(
      to,
      modeValue,
      generatorsValue,
      addWaitValue,
      injectHeadersValue,
      decorateValue,
      rewriteValue
    )

  def proxyOnce: ProxyResponseBuilder = withState(modeValue = ProxyMode.ProxyOnce)
  def proxyAlways: ProxyResponseBuilder = withState(modeValue = ProxyMode.ProxyAlways)
  def proxyTransparent: ProxyResponseBuilder = withState(modeValue = ProxyMode.ProxyTransparent)

  def generateBy(fields: RequestField*): ProxyResponseBuilder =
    withState(generatorsValue = fields.toVector)

  /** Records the observed upstream latency as a `wait` behavior on each recorded stub. */
  def addWaitBehavior: ProxyResponseBuilder = withState(addWaitValue = true)

  /** Adds a header to the request sent upstream (not to the recorded stub's response). */
  def injectHeader(name: String, value: String): ProxyResponseBuilder =
    withState(injectHeadersValue = injectHeadersValue :+ (name -> value))

  /** Attaches a `decorate` behavior to each recorded stub. */
  def decorateWith(js: String): ProxyResponseBuilder = withState(decorateValue = Some(js))

  /** Rewrites the recorded stub's path, e.g. `rewritePath("^/api", "/v2")`. */
  def rewritePath(from: String, to: String): ProxyResponseBuilder =
    withState(rewriteValue = Some(PathRewrite(from, to)))

  def build: Response =
    Response.Proxy(
      ProxyResponse(
        to,
        modeValue,
        generatorsValue.map(_.toGeneratorJson),
        addWaitValue,
        injectHeadersValue,
        decorateValue,
        rewriteValue
      )
    )

def proxyTo(url: String): ProxyResponseBuilder = new ProxyResponseBuilder(url)

final case class FaultResponseBuilder private[dsl] (kind: TcpFaultKind) extends ResponseBuilder:
  def build: Response = Response.Fault(kind)

def fault(kind: TcpFaultKind): FaultResponseBuilder = FaultResponseBuilder(kind)

final case class InjectResponseBuilder private[dsl] (scriptBody: String) extends ResponseBuilder:
  def build: Response = Response.Inject(scriptBody)

def inject(scriptBody: String): InjectResponseBuilder = InjectResponseBuilder(scriptBody)

final case class ScriptResponseBuilder private[dsl] (source: ScriptSource) extends ResponseBuilder:
  def build: Response = Response.RiftScript(RiftResponseExt(script = Some(source)))

def script(source: ScriptSource): ScriptResponseBuilder = ScriptResponseBuilder(source)

object Script:
  def rhai(code: String): ScriptSource = ScriptSource.Inline(ScriptEngine.Rhai, code)
  def rhaiFile(path: String): ScriptSource = ScriptSource.File(ScriptEngine.Rhai, path)
  def javascript(code: String): ScriptSource = ScriptSource.Inline(ScriptEngine.JavaScript, code)
  def javascriptFile(path: String): ScriptSource = ScriptSource.File(ScriptEngine.JavaScript, path)
  def ref(name: String): ScriptSource = ScriptSource.Ref(name)
