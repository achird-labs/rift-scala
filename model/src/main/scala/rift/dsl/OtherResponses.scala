package rift.dsl

import rift.model.*

/** Proxy, TCP fault, raw inject, and rift-script response kinds — the non-`is` members of
  * `rift.model.Response` (DESIGN.md §5.1.3).
  */
final class ProxyResponseBuilder private[dsl] (
    private val to: String,
    private val modeValue: ProxyMode = ProxyMode.ProxyAlways,
    private val generatorsValue: Vector[RequestField] = Vector.empty
) extends ResponseBuilder:
  def proxyOnce: ProxyResponseBuilder =
    new ProxyResponseBuilder(to, ProxyMode.ProxyOnce, generatorsValue)
  def proxyAlways: ProxyResponseBuilder =
    new ProxyResponseBuilder(to, ProxyMode.ProxyAlways, generatorsValue)
  def proxyTransparent: ProxyResponseBuilder =
    new ProxyResponseBuilder(to, ProxyMode.ProxyTransparent, generatorsValue)

  def generateBy(fields: RequestField*): ProxyResponseBuilder =
    new ProxyResponseBuilder(to, modeValue, fields.toVector)

  def build: Response =
    Response.Proxy(ProxyResponse(to, modeValue, generatorsValue.map(_.toGeneratorJson)))

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
