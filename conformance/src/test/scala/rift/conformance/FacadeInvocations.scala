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

/** What facade members a bridge method actually *calls*, read from its bytecode (#130).
  *
  * `FacadeParitySpec`'s check (c) used to ask only whether a `Wrapped.by` pointer still named a
  * declared method. That catches a rename but not a lie: a plausible-but-wrong pointer passed
  * silently. It is not a hypothetical failure — seeding the allow-list for #98 produced eight rows
  * mislabelled `Excluded("… never sets it")` for setters the bridge demonstrably calls, and the
  * only reason they were caught was a hand-run `javap` sweep. Nothing kept them honest afterwards.
  *
  * So this computes, for a named bridge method, the set of *facade* members reachable from it, in
  * the same key format the gate enumerates (`Owner#method(ParamSimpleName,...)`, or
  * `Owner#CONSTANT` for an enum constant read). Check (c2) then asserts the key a row claims is in
  * that set.
  *
  * Two things it must get right, because both directions are dangerous — a false FAIL trains the
  * next author to relabel a truthful row `Excluded`, and a false PASS is the defect the gate exists
  * to catch:
  *
  *   - '''Only facade owners count.''' The bridge deliberately mirrors facade type and method names
  *     (`rift.bridge.RecordSpec`, `InterceptRuleBuilder`, `RiftEvent`, `RuleKind`, …), so a
  *     bridge-internal call renders byte-identically to a facade capability key. Recording those
  *     would let `Wrapped("RecordSpec#mode()", "rift.bridge.RecordSpec#toJava")` pass on a bridge
  *     self-call. Only `io.github.achirdlabs.rift` owners are recorded; bridge owners are followed
  *     but never counted.
  *   - '''Field initializers are attributed per field, not per class.''' `val riftJava =
  *     RiftVersion.get()` puts the facade call in `<clinit>` and leaves the accessor a bare field
  *     read, so the member is honestly the thing that surfaces the capability — but a Scala
  *     `object` initializes *every* `val` in one `<clinit>`, so pulling the whole initializer in
  *     would make all its members interchangeable (`RiftVersions#riftJava` and `#engine` would each
  *     reach both `RiftVersion#get()` and `#engineVersion()`). Instead, a method that reads field
  *     `f` pulls in exactly the initializer slice that assigns `f`.
  */
object FacadeInvocations:

  private val BridgePackage = "rift/bridge/"
  private val FacadePackage = "io/github/achirdlabs/rift/"

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

  private def isBridge(owner: String): Boolean = owner.startsWith(BridgePackage)
  private def isFacade(owner: String): Boolean = owner.startsWith(FacadePackage)

  /** `io/github/achirdlabs/rift/InterceptOptions$Builder` → `InterceptOptions.Builder`, matching
    * `FacadeParitySpec.capabilityOwner`'s `Enclosing.Nested` rendering — the two must agree or the
    * three distinct nested `Builder` types collapse onto one key.
    */
  private def ownerKey(internalName: String): String =
    internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.')

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

  private def instructionsOf(m: MethodNode): Vector[AbstractInsnNode] =
    if m.instructions == null then Vector.empty else m.instructions.toArray.toVector

  private def isFieldRead(f: FieldInsnNode): Boolean =
    f.getOpcode == Opcodes.GETFIELD || f.getOpcode == Opcodes.GETSTATIC

  private def isFieldWrite(f: FieldInsnNode): Boolean =
    f.getOpcode == Opcodes.PUTFIELD || f.getOpcode == Opcodes.PUTSTATIC

  /** The instructions of `owner`'s `<init>`/`<clinit>` that compute the value assigned to `field`.
    *
    * Field initialization in a constructor is straight-line: the value is computed and then stored,
    * so the slice for `f` runs from just after the previous store to one of `owner`'s own fields up
    * to the store of `f`. That is what keeps `RiftVersions#riftJava` from reaching
    * `RiftVersion#engineVersion()`, which shares its `<clinit>`.
    */
  private def initializerSlice(owner: String, field: String): Vector[AbstractInsnNode] =
    readClass(owner).toVector.flatMap { node =>
      node.methods.asScala.toVector
        .filter(m => m.name == "<init>" || m.name == "<clinit>")
        .flatMap { m =>
          val insns = instructionsOf(m)
          var start = 0
          var slice = Vector.empty[AbstractInsnNode]
          insns.zipWithIndex.foreach {
            case (f: FieldInsnNode, i) if isFieldWrite(f) && f.owner == owner =>
              if f.name == field then slice = slice ++ insns.slice(start, i)
              start = i + 1
            case _ => ()
          }
          slice
        }
    }

  /** Every facade capability key reachable from any overload of `className#methodName`.
    *
    * Seeded with *all* overloads of the name because `Wrapped.by` is deliberately name-only — check
    * (c)'s existing leniency about which overload a pointer means is preserved here rather than
    * tightened as a side effect.
    */
  def reachableCapabilities(className: String, methodName: String): Set[String] =
    val rootOwner = className.replace('.', '/')

    val found = mutable.Set.empty[String]
    val visitedMethods = mutable.Set.empty[MethodKey]
    val visitedFields = mutable.Set.empty[(String, String)]
    val queue = mutable.Queue.empty[(MethodKey, Int)]

    def record(owner: String, name: String, desc: String): Unit =
      if isFacade(owner) then found += invocationKey(owner, name, desc)

    def walk(owner: String, insns: Vector[AbstractInsnNode], depth: Int): Unit =
      insns.foreach {
        case m: MethodInsnNode =>
          record(m.owner, m.name, m.desc)
          if isBridge(m.owner) then queue.enqueue(MethodKey(m.owner, m.name, m.desc) -> (depth + 1))

        // The lambda/by-name case: the body lives in a synthetic method named by a bootstrap
        // handle, so the facade call is invisible without following it. A handle straight onto a
        // facade member (a method reference) is itself the call, and is recorded rather than
        // dropped — otherwise the row reads as "reaches nothing" and invites a false Excluded.
        case d: InvokeDynamicInsnNode =>
          d.bsmArgs.foreach {
            case h: Handle =>
              record(h.getOwner, h.getName, h.getDesc)
              if isBridge(h.getOwner) then
                queue.enqueue(MethodKey(h.getOwner, h.getName, h.getDesc) -> (depth + 1))
            case _ => ()
          }

        case f: FieldInsnNode =>
          // Enum constants are read, not called — `VerifyDetail#REQUESTS` and friends enumerate as
          // capabilities, so a facade GETSTATIC has to count as covering one.
          if f.getOpcode == Opcodes.GETSTATIC && isFacade(f.owner) then
            found += constantKey(f.owner, f.name)
          // A read of one of this class's own fields pulls in that field's initializer slice only.
          if isFieldRead(f) && f.owner == owner && visitedFields.add(owner -> f.name) then
            walk(owner, initializerSlice(owner, f.name), depth)

        case _ => ()
      }

    readClass(rootOwner).foreach { node =>
      node.methods.asScala
        .filter(_.name == methodName)
        .foreach(m => queue.enqueue(MethodKey(rootOwner, m.name, m.desc) -> 0))
    }

    while queue.nonEmpty do
      val (key, depth) = queue.dequeue()
      if !visitedMethods.contains(key) && depth <= MaxDepth then
        visitedMethods += key
        for
          node <- readClass(key.owner).toVector
          method <- node.methods.asScala
          if method.name == key.name && method.desc == key.desc
        do walk(key.owner, instructionsOf(method), depth)

    found.toSet
