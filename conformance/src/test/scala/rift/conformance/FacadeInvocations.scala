package rift.conformance

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import org.objectweb.asm.{ClassReader, Handle, Opcodes, Type}
import org.objectweb.asm.tree.{
  AbstractInsnNode,
  ClassNode,
  FieldInsnNode,
  InvokeDynamicInsnNode,
  MethodInsnNode,
  MethodNode
}

/** What a bridge method actually *calls*, read from its bytecode (#130).
  *
  * `FacadeParitySpec`'s check (c) used to ask only whether a `Wrapped.by` pointer still named a
  * declared method. That catches a rename but not a lie: a plausible-but-wrong pointer passed
  * silently. It is not a hypothetical failure — seeding the allow-list for #98 produced eight rows
  * mislabelled `Excluded("… never sets it")` for setters the bridge demonstrably calls, and the
  * only reason they were caught was a hand-run `javap` sweep. Nothing kept them honest afterwards.
  *
  * So this computes, for a named bridge method, the set of facade members reachable from it, in the
  * same key format the gate enumerates (`Owner#method(ParamSimpleName,...)`, or `Owner#CONSTANT`
  * for an enum constant read). Check (c) then asserts the key a row claims is in that set.
  *
  * '''Why a closure rather than one method body.''' Almost every bridge call is wrapped in
  * `FacadeBoundary.run(...)`, whose argument is by-name — so the actual facade invocation compiles
  * into a synthetic lambda body, not the named method. Translation also routes through helpers
  * (`FacadeEncode.isSpec` delegates to several privates). Following callees *declared in
  * `rift.bridge`* reaches both, while never wandering into unrelated libraries.
  */
object FacadeInvocations:

  private val BridgePackage = "rift/bridge/"

  /** Depth is a backstop against a pathological call graph, not a tuning knob — the real bridge
    * chains are 2-3 hops (named method → lambda → private helper → facade).
    */
  private val MaxDepth = 6

  private val classCache = mutable.Map.empty[String, Option[ClassNode]]

  /** A method identified the way ASM sees it, so the traversal can dedupe overloads precisely. */
  private final case class MethodKey(owner: String, name: String, desc: String)

  private def readClass(internalName: String): Option[ClassNode] =
    classCache.getOrElseUpdate(
      internalName, {
        val resource = s"/$internalName.class"
        Option(getClass.getResourceAsStream(resource)).map { in =>
          try
            val node = new ClassNode()
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG)
            node
          finally in.close()
        }
      }
    )

  /** `io/github/achirdlabs/rift/InterceptOptions$Builder` → `InterceptOptions.Builder`, matching
    * `FacadeParitySpec.capabilityOwner`'s `Enclosing.Nested` rendering — the two must agree or the
    * three distinct nested `Builder` types collapse onto one key. The trailing `$` of a Scala
    * module class is dropped so bridge owners read normally in the diagnostic listing.
    */
  private def ownerKey(internalName: String): String =
    internalName.substring(internalName.lastIndexOf('/') + 1).stripSuffix("$").replace('$', '.')

  /** One erased parameter name, matching `FacadeParitySpec.paramTypeName` exactly: arrays unwrap to
    * `T[]`, everything else is `Class.getSimpleName`.
    *
    * Note the deliberate asymmetry with `ownerKey`: an *owner* renders nested as
    * `Enclosing.Nested`, but a *parameter* renders as the bare simple name, because that is what
    * `getSimpleName` gives and the enumerated keys are built from it — `types(EventType[])`, not
    * `types(EventStreamOptions.EventType[])`. Mirroring only one of the two silently fails every
    * row whose signature mentions a nested type.
    */
  private def paramName(t: Type): String =
    if t.getSort == Type.ARRAY then paramName(t.getElementType) + ("[]" * t.getDimensions)
    else
      val qualified = t.getClassName
      val afterPackage = qualified.substring(qualified.lastIndexOf('.') + 1)
      afterPackage.substring(afterPackage.lastIndexOf('$') + 1)

  private def paramNames(desc: String): String =
    Type.getArgumentTypes(desc).map(paramName).mkString(",")

  private def invocationKey(owner: String, name: String, desc: String): String =
    s"${ownerKey(owner)}#$name(${paramNames(desc)})"

  private def constantKey(owner: String, name: String): String = s"${ownerKey(owner)}#$name"

  private def isBridge(owner: String): Boolean = owner.startsWith(BridgePackage)

  /** Every facade capability key reachable from any overload of `className#methodName`.
    *
    * Seeded with *all* overloads of the name because `Wrapped.by` is deliberately name-only — check
    * (c)'s existing leniency about which overload a pointer means is preserved here rather than
    * tightened as a side effect.
    */
  def reachableCapabilities(className: String, methodName: String): Set[String] =
    val rootOwner = className.replace('.', '/')

    val found = mutable.Set.empty[String]
    val visited = mutable.Set.empty[MethodKey]
    val initialized = mutable.Set.empty[String]
    val queue = mutable.Queue.empty[(MethodKey, Int)]

    /** A bridge class's `<init>`/`<clinit>` count as part of every member it declares.
      *
      * `val riftJava = RiftVersion.get()` puts the facade call in the constructor and leaves the
      * accessor a bare field read; `private val it = underlying.iterator()` does the same for
      * `EventStreamConnector.poll`. Those pointers are honest — the member really is what surfaces
      * the capability — so a check that read only the named method's own body would reject three
      * truthful rows and teach the next author to work around it.
      *
      * The cost is that a row also sees whatever its class's construction calls. That is bounded to
      * one class's own initialization, which in this codebase is exactly the field setup belonging
      * to its members — not a general widening of the closure.
      */
    def enqueueInitializers(owner: String, depth: Int): Unit =
      if initialized.add(owner) then
        for
          node <- readClass(owner).toVector
          m <- node.methods.asScala
          if m.name == "<init>" || m.name == "<clinit>"
        do queue.enqueue(MethodKey(owner, m.name, m.desc) -> depth)

    readClass(rootOwner).foreach { node =>
      node.methods.asScala
        .filter(_.name == methodName)
        .foreach(m => queue.enqueue(MethodKey(rootOwner, m.name, m.desc) -> 0))
    }
    enqueueInitializers(rootOwner, 0)

    while queue.nonEmpty do
      val (key, depth) = queue.dequeue()
      if !visited.contains(key) && depth <= MaxDepth then
        visited += key
        if isBridge(key.owner) then enqueueInitializers(key.owner, depth)
        for
          node <- readClass(key.owner).toVector
          method <- node.methods.asScala
          if method.name == key.name && method.desc == key.desc
          insn <- instructionsOf(method)
        do
          insn match
            case m: MethodInsnNode =>
              found += invocationKey(m.owner, m.name, m.desc)
              if isBridge(m.owner) then
                queue.enqueue(MethodKey(m.owner, m.name, m.desc) -> (depth + 1))

            // The lambda/by-name case: the body lives in a synthetic method named by a bootstrap
            // handle, so the facade call is invisible without following it.
            case d: InvokeDynamicInsnNode =>
              d.bsmArgs.foreach {
                case h: Handle if isBridge(h.getOwner) =>
                  queue.enqueue(MethodKey(h.getOwner, h.getName, h.getDesc) -> (depth + 1))
                case _ => ()
              }

            // Enum constants are read, not called — `VerifyDetail#REQUESTS` and friends enumerate as
            // capabilities, so a GETSTATIC has to count as covering one.
            case f: FieldInsnNode if f.getOpcode == Opcodes.GETSTATIC =>
              found += constantKey(f.owner, f.name)

            case _ => ()

    found.toSet

  private def instructionsOf(m: MethodNode): Vector[AbstractInsnNode] =
    if m.instructions == null then Vector.empty else m.instructions.toArray.toVector
