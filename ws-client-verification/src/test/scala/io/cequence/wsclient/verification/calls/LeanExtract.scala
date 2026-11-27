package io.cequence.wsclient.verification.calls

import scala.meta._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.sys.process._
import scala.util.Random

/**
 * Extracts the Lean model of `AsyncCall` plans FROM the Scala source the tests run
 * ([[CallModel]]): the Scala file is parsed, every construct is translated one to one, and
 * anything outside the subset (see `CallModel`) fails the extraction. Nothing is
 * transliterated by hand: the Lean definitions `Calls/Proofs.lean` reasons about are the Scala
 * definitions `CallTreeSpec` compares the real interpreters with.
 *
 * Modes (the sbt command `leanExtract`):
 *   - no argument: rewrites `formal/lean/WsClientProofs/Calls/Model.lean`
 *   - `--check`: fails when that file is not what the extraction produces now (CI)
 *   - `--cases N`: TRANSLATION VALIDATION - N plans (the small scope and random deep ones)
 *     with the outcome the Scala model gives each, as Lean terms, are checked by the generated
 *     Lean (`denote` and the machine) in the Lean interpreter. A mistake of the extractor
 *     itself would show here (an `Int` operation, a pattern order, ...).
 *
 * The translation is syntax-directed and small: types (`Int`, `Boolean`, `List`, `Option`, the
 * model's own), `sealed trait` + case classes -> `inductive`, a lone case class ->
 * `structure`, `def` -> `def`, `match`, `val`, `if`, `+` `-` `==` `&&` `||` `::` `++`, calls,
 * constructors. `Int` becomes Lean's `Int`.
 */
object LeanExtract {

  private val ModelFile =
    "ws-client-verification/src/test/scala/io/cequence/wsclient/verification/calls/CallModel.scala"

  private val OutputFile = "formal/lean/WsClientProofs/Calls/Model.lean"

  // The production code extracted directly: the Lean files the proofs of the pure pieces
  // (query encoding, multipart parameters, SSE frames) are about
  private val Targets: List[ProductionExtract.Target] = List(
    ProductionExtract.Target(
      name = "wire",
      source =
        "ws-client-core/src/main/scala/io/cequence/wsclient/service/ws/WireFormat.scala",
      obj = "WireFormat",
      out = "formal/lean/WsClientProofs/WireFormat/Extracted.lean",
      namespace = "WsClientProofs.WireFormat.Extracted",
      imports = List("WsClientProofs.Scala", "WsClientProofs.WireFormat.JdkUrlEncoder"),
      take = List("encodeQueryComponent", "url"),
      about =
        "`WireFormat.encodeQueryComponent` and `WireFormat.url`. `URLEncoder.encode` is the JDK's\n" +
          "(`WireFormat.urlEncoderEncode`, modelled from its javadoc); a value of type `Any` is its\n" +
          "`toString` text, the only thing `url` does with it."
    ),
    ProductionExtract.Target(
      name = "multipart",
      source =
        "ws-client-core/src/main/scala/io/cequence/wsclient/service/ws/MultipartFormData.scala",
      obj = "MultipartParams",
      out = "formal/lean/WsClientProofs/Multipart/Extracted.lean",
      namespace = "WsClientProofs.Multipart.Extracted",
      imports = List("WsClientProofs.Scala", "WsClientProofs.JavaString"),
      take = List("escape", "quoted", "dataPartName", "isTokenChar"),
      about =
        "`MultipartParams`: the encoding of caller-supplied `Content-Disposition` values."
    ),
    ProductionExtract.Target(
      name = "frame",
      source =
        "ws-client-core/src/main/scala/io/cequence/wsclient/service/JsonStreamFrames.scala",
      obj = "JsonStreamFrames",
      out = "formal/lean/WsClientProofs/SseFrames/Extracted.lean",
      namespace = "WsClientProofs.SseFrames.Extracted",
      imports = List("WsClientProofs.Scala"),
      take = List("FrameResult", "EndOfStreamToken", "isComment", "LineBreak", "parseFrame"),
      about = "`JsonStreamFrames.parseFrame`. `Json.parse` is the free constructor\n" +
        "`Scala.JsValue.parsed` (the parse itself is not modelled: the frame result is the string\n" +
        "handed to it), and a `try` whose handlers all rethrow is its body (the exception paths -\n" +
        "a non-JSON payload - are outside the model)."
    )
  )

  // The imperative framers, extracted by `ClassExtract`: their loops over arrays become
  // fuel-bounded recursive defs, their fields a state
  private val ClassTargets: List[ClassExtract.Target] = List(
    ClassExtract.Target(
      name = "byteFraming",
      source = "ws-client-core/src/main/scala/io/cequence/wsclient/stream/ByteFraming.scala",
      cls = "DelimiterFramer",
      out = "formal/lean/WsClientProofs/Framing/ByteFramingExtracted.lean",
      namespace = "WsClientProofs.Framing.ByteFramingExtracted",
      imports = List("WsClientProofs.Scala", "WsClientProofs.Java"),
      methods = List("push", "finish", "append", "indexOfDelimiter"),
      about = "`ByteFraming.DelimiterFramer`: the per-run state of `ByteFraming.delimiter`."
    ),
    ClassExtract.Target(
      name = "engineFramer",
      source =
        "ws-client-core/src/main/scala/io/cequence/wsclient/stream/StreamTransformers.scala",
      cls = "DelimiterFramer",
      out = "formal/lean/WsClientProofs/Framing/EngineFramerExtracted.lean",
      namespace = "WsClientProofs.Framing.EngineFramerExtracted",
      imports = List("WsClientProofs.Scala", "WsClientProofs.Java"),
      methods = List("onInput", "onUpstreamComplete", "indexOfDelimiter"),
      about =
        "`StreamTransformers.DelimiterFramer`: the SSE framer of the jdk and zio-http engines."
    )
  )

  /**
   * The hand-written Lean DRIVERS of the extracted framers (`Framing/ExtractedDrivers.lean`:
   * what runs them - the `stateful` stage of `ByteFraming.delimiter`, the `TransformPublisher`
   * of the jdk / zio-http engines - is not extractable): each is PINNED to the Scala it models
   * \- the hash of the member's tokens, white space and comments left out, is recorded in
   * `formal/lean/pins.txt`. A change to the member fails `leanExtract --check` until the
   * driver is re-read and the pin is renewed on purpose (`leanExtract --pin`) - no silent
   * drift between code and model.
   */
  private final case class Pin(
    id: String,
    source: String,
    member: String,
    model: String
  )

  private val PinFile = "formal/lean/pins.txt"

  private val Pins: List[Pin] = {
    val core = "ws-client-core/src/main/scala/io/cequence/wsclient/stream/"
    val drivers = "formal/lean/WsClientProofs/Framing/ExtractedDrivers.lean"
    List(
      Pin("ByteFraming.delimiter", core + "ByteFraming.scala", "def delimiter", drivers),
      Pin(
        "TransformPublisher",
        core + "TransformPublisher.scala",
        "class TransformPublisher",
        drivers
      )
    )
  }

  // the tokens of a member without white space and comments, hashed
  private def pinHash(
    source: String,
    member: String
  ): String = {
    implicit val dialect: Dialect = dialects.Scala213
    val tree = source.parse[Source].get
    val found: List[Tree] = member.split(" ", 2) match {
      case Array("class", name) =>
        tree.collect { case c: Defn.Class if c.name.value == name => c: Tree }
      case Array("def", name) =>
        tree.collect { case d: Defn.Def if d.name.value == name => d: Tree }
      case _ => sys.error(s"unknown pin member: $member")
    }
    require(found.size == 1, s"pin member '$member' matches ${found.size} definitions")
    val tokens = found.head.tokens.filterNot {
      case _: Token.Space | _: Token.Tab | _: Token.CR | _: Token.LF | _: Token.LFLF |
          _: Token.FF | _: Token.Comment | _: Token.BOF | _: Token.EOF =>
        true
      case _ => false
    }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    tokens.foreach(t => digest.update((t.syntax + "\u0000").getBytes(StandardCharsets.UTF_8)))
    digest.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  private def pinLines(root: Path): List[String] =
    Pins.map { p =>
      s"${p.id}  ${pinHash(read(root.resolve(p.source)), p.member)}  ${p.source}  ${p.model}"
    }

  private val PinHeader =
    """# The Scala members whose hand-written Lean drivers (what runs the extracted framers) the proofs are about.
      |# Each line: member, SHA-256 of its tokens (white space and comments left out), source, model.
      |# `sbt "leanExtract --check"` fails when a member changed: re-read the model and its proofs,
      |# change them if the behavior changed, then renew with `sbt "leanExtract --pin"`.
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    val root = locateRoot()
    val extraction = Extraction(read(root.resolve(ModelFile)))
    // every generated file: path -> text
    val generated: List[(String, String)] =
      (OutputFile -> extraction.lean) :: Targets.map(t =>
        t.out -> ProductionExtract.lean(t, read(root.resolve(t.source)))
      ) ::: ClassTargets.map(t => t.out -> ClassExtract.lean(t, read(root.resolve(t.source))))

    args.toList match {
      case Nil =>
        generated.foreach { case (path, text) =>
          val file = root.resolve(path)
          Files.createDirectories(file.getParent)
          Files.write(file, text.getBytes(StandardCharsets.UTF_8))
          println(s"wrote $path (${text.linesIterator.size} lines)")
        }

      case "--pin" :: Nil =>
        val text = PinHeader + pinLines(root).mkString("\n") + "\n"
        Files.write(root.resolve(PinFile), text.getBytes(StandardCharsets.UTF_8))
        println(s"wrote $PinFile (${Pins.size} pins)")

      case "--check" :: Nil =>
        val stale = generated.filter { case (path, text) =>
          val file = root.resolve(path)
          val current = if (Files.exists(file)) read(file) else ""
          current != text
        }
        if (stale.nonEmpty) {
          stale.foreach { case (path, _) =>
            println(s"FAIL  $path is not what the extraction of its Scala source produces")
          }
          println("      run `sbt leanExtract` and commit the result")
          sys.exit(1)
        }
        generated.foreach { case (path, _) =>
          println(s"PASS  $path is the extraction of its source")
        }
        checkPins(root)

      case "--cases" :: count :: Nil =>
        val ok = validate(root, extraction, count.toInt)
        if (!ok) sys.exit(1)

      case other =>
        System.err.println(s"unknown arguments: ${other.mkString(" ")}")
        sys.exit(2)
    }
  }

  private def checkPins(root: Path): Unit = {
    val file = root.resolve(PinFile)
    val recorded: Map[String, String] =
      if (!Files.exists(file)) Map.empty
      else
        read(file).linesIterator
          .filterNot(l => l.startsWith("#") || l.trim.isEmpty)
          .map(_.split("  ").toList)
          .collect { case id :: hash :: _ => id -> hash }
          .toMap
    val changed = Pins.filter { p =>
      recorded.get(p.id) != Some(pinHash(read(root.resolve(p.source)), p.member))
    }
    if (changed.nonEmpty) {
      changed.foreach { p =>
        println(s"FAIL  ${p.id} (${p.source}) changed since its Lean model was last checked")
        println(s"      re-read ${p.model} and its proofs; then `sbt \"leanExtract --pin\"`")
      }
      sys.exit(1)
    }
    Pins.foreach(p => println(s"PASS  ${p.id} is as its Lean model (${p.model}) was checked"))
  }

  // --------------------------------------------------------------------- translation check

  private def validate(
    root: Path,
    extraction: Extraction,
    count: Int
  ): Boolean = {
    import CallModel._

    val lean = formalDir(root)
    val random = new Random(1)
    val small = CallTreeSpec.scope(2).toVector
    val deep =
      CallTreeSpec.randomPlans(random.nextLong(), math.max(0, count - small.size)).toVector
    val plans = (small ++ deep).take(count)

    def term(value: Any): String = extraction.literal(value)

    val chunks = plans
      .grouped(100)
      .zipWithIndex
      .map { case (group, i) =>
        val cases =
          group.map(p => s"(${term(p)}, ${term(denote(p))})").mkString("  [", ",\n   ", "]")
        s"def cases$i : List (Plan × Obs) :=\n$cases"
      }
      .toVector

    val script =
      s"""import WsClientProofs.Calls.Model
         |open WsClientProofs.Calls
         |
         |def run (c : Config) : Nat → Config
         |  | 0 => c
         |  | n + 1 => match step c with
         |    | none => c
         |    | some c' => run c' n
         |
         |${chunks.mkString("\n\n")}
         |
         |def cases : List (Plan × Obs) :=
         |  ${chunks.indices.map(i => s"cases$i").mkString(" ++ ")}
         |
         |def main : IO UInt32 := do
         |  let mut denoteBad := 0
         |  let mut machineBad := 0
         |  for (p, expected) in cases do
         |    if denote p != expected then denoteBad := denoteBad + 1
         |    if result (run (init p) 1000000) != expected then machineBad := machineBad + 1
         |  IO.println s!"{cases.length} plans: denote differs on {denoteBad}, machine on {machineBad}"
         |  return (if denoteBad == 0 && machineBad == 0 then 0 else 1)
         |""".stripMargin

    val dir = Files.createTempDirectory("lean-extract-cases")
    val file = dir.resolve("Cases.lean")
    Files.write(file, script.getBytes(StandardCharsets.UTF_8))

    val log = new StringBuilder
    val exit = Process(
      Seq("lake", "env", "lean", "--run", file.toString),
      lean.toFile
    ).!(
      ProcessLogger(
        line => log.append(line).append('\n'),
        line => log.append(line).append('\n')
      )
    )
    val text = log.toString.trim
    if (exit == 0) println(s"PASS  translation: $text")
    else println(s"FAIL  translation (exit $exit): ${text.take(4000)}")
    exit == 0
  }

  // -------------------------------------------------------------------------------- paths

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def formalDir(root: Path): Path = root.resolve("formal/lean")

  // the repository root: the directory (from the working directory up) that holds `formal/lean`
  private def locateRoot(): Path = {
    var dir = Paths.get("").toAbsolutePath
    while (dir != null && !Files.isDirectory(dir.resolve("formal/lean"))) dir = dir.getParent
    if (dir == null) sys.error("formal/lean not found above the working directory")
    dir
  }

  // ------------------------------------------------------------------------- the extraction

  /** What the model's source declares, and its Lean text. */
  final class Extraction private (
    types: Vector[Extraction.TypeDecl],
    defs: Vector[Extraction.DefDecl]
  ) {
    import Extraction._

    private val inductives = types.collect { case i: Inductive => i }
    private val structures = types.collect { case s: Structure => s }

    // constructor name -> the inductive it belongs to; case classes without a parent
    private val ctorOwner: Map[String, String] =
      inductives.flatMap(i => i.ctors.map(_.name -> i.name)).toMap
    private val ctorFields: Map[String, Vector[Field]] =
      inductives.flatMap(i => i.ctors.map(c => c.name -> c.fields)).toMap
    private val structureNames: Set[String] = structures.map(_.name).toSet
    private val structureFields: Map[String, Vector[Field]] =
      structures.map(s => s.name -> s.fields).toMap
    private val defNames: Set[String] = defs.map(_.name).toSet

    require(
      ctorOwner.size == inductives.map(_.ctors.size).sum,
      "two constructors share a name"
    )

    // ------------------------------------------------------------------------------ Lean

    def lean: String = {
      val sb = new StringBuilder
      sb.append(
        """/-!
          |GENERATED by `sbt leanExtract` from
          |ws-client-verification/src/test/scala/io/cequence/wsclient/verification/calls/CallModel.scala
          |- do not edit; change the Scala model and extract again. `sbt "leanExtract --check"`
          |fails when this file is out of date.
          |-/
          |
          |namespace WsClientProofs.Calls
          |""".stripMargin
      )
      types.foreach {
        case i: Inductive =>
          sb.append(s"\ninductive ${i.name} where\n")
          i.ctors.foreach { c =>
            val fields = c.fields.map(f => s" (${esc(f.name)} : ${f.tpe})").mkString
            sb.append(s"  | ${c.name}$fields\n")
          }
          sb.append("  deriving DecidableEq, Repr\n")
        case s: Structure =>
          sb.append(s"\nstructure ${s.name} where\n")
          s.fields.foreach(f => sb.append(s"  ${esc(f.name)} : ${f.tpe}\n"))
          sb.append("  deriving DecidableEq, Repr\n")
      }
      defs.foreach { d =>
        val params = d.params.map(p => s" (${esc(p.name)} : ${p.tpe})").mkString
        sb.append(s"\ndef ${d.name}$params : ${d.result} :=\n")
        sb.append(body(d.body, 2, top = true)).append("\n")
      }
      sb.append("\nend WsClientProofs.Calls\n")
      sb.result()
    }

    // ----------------------------------------------------------------------- expressions

    private def pad(n: Int): String = " " * n

    private def fail(
      what: String,
      tree: Tree
    ): Nothing =
      sys.error(s"outside the extractable subset: $what: ${tree.syntax.take(200)}")

    private def isAtom(t: Term): Boolean = t match {
      case _: Term.Name | _: Lit                            => true
      case s: Term.Select if s.qual.isInstanceOf[Term.Name] => true
      case a: Term.Apply if isListLiteral(a)                => true
      case _                                                => false
    }

    private def isListLiteral(a: Term.Apply): Boolean = a.fun match {
      case Term.Name("List") => true
      case _                 => false
    }

    private def atom(
      t: Term,
      ind: Int
    ): String =
      if (isAtom(t)) expr(t, ind) else s"(${expr(t, ind)})"

    /** A def body or an arm body: a match / let / if stands as it is. */
    private def body(
      t: Term,
      ind: Int,
      top: Boolean
    ): String =
      pad(ind) + expr(t, ind, top)

    private def expr(
      t: Term,
      ind: Int,
      top: Boolean = false
    ): String = t match {
      case Lit.Int(n)     => if (n < 0) s"($n : Int)" else n.toString
      case Lit.Boolean(b) => b.toString
      case Term.Name(n)   => name(n)
      case s: Term.Select =>
        s.qual match {
          case Term.Name(q) => s"${name(q)}.${esc(s.name.value)}"
          case _            => fail("field access on a non-variable", t)
        }

      case a: Term.Apply => apply(a, ind)
      case Term.ApplyUnary(op, arg) =>
        op.value match {
          case "!"   => s"!${atom(arg, ind)}"
          case "-"   => s"-${atom(arg, ind)}"
          case other => fail(s"unary $other", t)
        }
      case i: Term.ApplyInfix =>
        val rhs = i.argClause.values match {
          case List(single) => single
          case _            => fail("an infix operator without one argument", t)
        }
        val op = i.op.value match {
          case "+" | "-" | "*" | "==" | "&&" | "||" | "::" | "++" => i.op.value
          case "!="                                               => "!="
          case other => fail(s"operator $other", t)
        }
        s"${atom(i.lhs, ind)} $op ${atom(rhs, ind)}"

      case i: Term.If =>
        val inner = ind + 2
        val text =
          s"if ${expr(i.cond, ind)} then\n${pad(inner)}${expr(i.thenp, inner)}\n${pad(
              ind
            )}else\n${pad(inner)}${expr(i.elsep, inner)}"
        text

      case m: Term.Match =>
        val scrutinee = expr(m.expr, ind)
        val arms = m.cases.map { c =>
          if (c.cond.nonEmpty) fail("a guard", c)
          val inner = ind + 4
          val rhs = armBody(c.body, inner)
          s"${pad(ind)}| ${pattern(c.pat, top = true)} =>\n${pad(inner)}$rhs"
        }
        s"match $scrutinee with\n${arms.mkString("\n")}"

      case b: Term.Block =>
        b.stats match {
          case stats if stats.nonEmpty && stats.init.forall(_.isInstanceOf[Defn.Val]) =>
            val lets = stats.init.map { case v: Defn.Val =>
              v.pats match {
                case List(Pat.Var(n)) => s"let ${esc(n.value)} := ${expr(v.rhs, ind + 2)}"
                case _                => fail("a val pattern", v)
              }
            }
            val last = stats.last match {
              case term: Term => expr(term, ind)
              case other      => fail("a block that does not end in an expression", other)
            }
            (lets :+ last).mkString(s"\n${pad(ind)}")
          case _ => fail("a block", t)
        }

      case other => fail("expression", other)
    }

    // an arm's body: a nested match / let / if must not swallow the next arm
    private def armBody(
      t: Term,
      ind: Int
    ): String = t match {
      case _: Term.Match | _: Term.Block | _: Term.If => s"(${expr(t, ind)})"
      case _                                          => expr(t, ind)
    }

    private def apply(
      a: Term.Apply,
      ind: Int
    ): String = {
      val args = a.argClause.values
      a.fun match {
        case Term.Name("Some") => s"some ${args.map(atom(_, ind)).mkString(" ")}"
        case Term.Name("List") => s"[${args.map(expr(_, ind)).mkString(", ")}]"
        case Term.Name(f) if ctorOwner.contains(f) =>
          require(args.size == ctorFields(f).size, s"constructor $f: ${args.size} arguments")
          s"${ctorOwner(f)}.$f${args.map(x => " " + atom(x, ind)).mkString}"
        case Term.Name(f) if structureNames.contains(f) =>
          require(
            args.size == structureFields(f).size,
            s"constructor $f: ${args.size} arguments"
          )
          s"$f.mk${args.map(x => " " + atom(x, ind)).mkString}"
        case Term.Name(f) if defNames.contains(f) =>
          s"$f${args.map(x => " " + atom(x, ind)).mkString}"
        case other => fail("a call", other)
      }
    }

    // a bare name: a variable, or a constructor without arguments
    private def name(n: String): String =
      if (n == "Nil") "[]"
      else if (n == "None") "none"
      else if (ctorOwner.contains(n)) {
        require(ctorFields(n).isEmpty, s"$n needs arguments")
        s"${ctorOwner(n)}.$n"
      } else esc(n)

    // ------------------------------------------------------------------------- patterns

    private def pattern(
      p: Pat,
      top: Boolean
    ): String = {
      def nested(q: Pat): String = {
        val s = pattern(q, top = false)
        if (s.contains(' ') && !s.startsWith("(") && !s.startsWith("[")) s"($s)" else s
      }
      p match {
        case Pat.Wildcard()    => "_"
        case Pat.Var(n)        => esc(n.value)
        case Lit.Int(n)        => if (n < 0) s"($n : Int)" else n.toString
        case Lit.Boolean(b)    => b.toString
        case Term.Name("Nil")  => "[]"
        case Term.Name("None") => "none"
        case Term.Name(n) if ctorOwner.contains(n) && ctorFields(n).isEmpty =>
          s"${ctorOwner(n)}.$n"
        case e: Pat.Extract =>
          val args = e.argClause.values
          e.fun match {
            case Term.Name("Some") => s"some ${args.map(nested).mkString(" ")}"
            case Term.Name(f) if ctorOwner.contains(f) =>
              require(args.size == ctorFields(f).size, s"pattern $f: ${args.size} arguments")
              s"${ctorOwner(f)}.$f${args.map(x => " " + nested(x)).mkString}"
            case Term.Name(f) if structureNames.contains(f) =>
              require(
                args.size == structureFields(f).size,
                s"pattern $f: ${args.size} arguments"
              )
              s"$f.mk${args.map(x => " " + nested(x)).mkString}"
            case other => fail("a pattern", other)
          }
        case i: Pat.ExtractInfix if i.op.value == "::" =>
          i.rhs.values match {
            case List(tail) => s"${nested(i.lhs)} :: ${nested(tail)}"
            case _          => fail("a cons pattern", p)
          }
        case other => fail("a pattern", other)
      }
    }

    // --------------------------------------------------------------------- runtime values

    /** A value of the model, as a Lean term (case classes are Products). */
    def literal(value: Any): String = value match {
      case n: Int        => if (n < 0) s"($n : Int)" else n.toString
      case b: Boolean    => b.toString
      case Nil           => "[]"
      case list: List[_] => list.map(literal).mkString("[", ", ", "]")
      case None          => "none"
      case Some(x)       => s"(some ${literal(x)})"
      case p: Product =>
        val n = p.productPrefix
        val args = p.productIterator.map(x => " " + literalAtom(x)).mkString
        if (ctorOwner.contains(n)) s"(${ctorOwner(n)}.$n$args)"
        else if (structureNames.contains(n)) s"($n.mk$args)"
        else sys.error(s"not a model type: $n")
      case other => sys.error(s"not a model value: $other")
    }

    private def literalAtom(value: Any): String = literal(value)
  }

  object Extraction {

    final case class Field(
      name: String,
      tpe: String
    )
    final case class Ctor(
      name: String,
      fields: Vector[Field]
    )

    sealed trait TypeDecl { def name: String }
    final case class Inductive(
      name: String,
      ctors: Vector[Ctor]
    ) extends TypeDecl
    final case class Structure(
      name: String,
      fields: Vector[Field]
    ) extends TypeDecl

    final case class DefDecl(
      name: String,
      params: Vector[Field],
      result: String,
      body: Term
    )

    // Lean words that cannot be a name as they are
    private val keywords: Set[String] = Set(
      "at",
      "by",
      "do",
      "else",
      "end",
      "fun",
      "have",
      "if",
      "in",
      "let",
      "match",
      "open",
      "show",
      "then",
      "with",
      "from",
      "calc",
      "where",
      "deriving",
      "instance",
      "local",
      "macro",
      "rec",
      "theorem",
      "variable",
      "universe",
      "import",
      "namespace",
      "section",
      "structure",
      "class",
      "mutual",
      "partial",
      "private",
      "protected",
      "unsafe",
      "inductive",
      "def",
      "abbrev",
      "example",
      "axiom",
      "syntax",
      "elab",
      "notation",
      "infix",
      "prefix",
      "postfix",
      "attribute",
      "export",
      "return",
      "try",
      "catch",
      "finally",
      "for",
      "unless",
      "mut",
      "suffices",
      "using",
      "this",
      "Type",
      "Sort",
      "Prop",
      "forall",
      "exists",
      "fun",
      "λ"
    )

    private[calls] def esc(n: String): String =
      if (keywords.contains(n)) s"«$n»" else n

    def apply(source: String): Extraction = {
      implicit val dialect: Dialect = dialects.Scala213
      val tree = source.parse[Source].get
      val model = tree.collect {
        case o: Defn.Object if o.name.value == "CallModel" => o
      }.headOption.getOrElse(sys.error("object CallModel not found"))
      val stats = model.templ.stats

      val userTypes: Set[String] = stats.collect {
        case t: Defn.Trait                          => t.name.value
        case c: Defn.Class if c.templ.inits.isEmpty => c.name.value
      }.toSet

      def leanType(t: Type): String = t match {
        case Type.Name("Int")                      => "Int"
        case Type.Name("Boolean")                  => "Bool"
        case Type.Name(n) if userTypes.contains(n) => n
        case a: Type.Apply =>
          (a.tpe, a.argClause.values) match {
            case (Type.Name("List"), List(x))   => s"List ${atomType(x)}"
            case (Type.Name("Option"), List(x)) => s"Option ${atomType(x)}"
            case _ => sys.error(s"outside the extractable subset: type ${t.syntax}")
          }
        case _ => sys.error(s"outside the extractable subset: type ${t.syntax}")
      }
      def atomType(t: Type): String = {
        val s = leanType(t)
        if (s.contains(' ')) s"($s)" else s
      }

      def fieldsOf(c: Defn.Class): Vector[Field] =
        c.ctor.paramClauses.flatMap(_.values).toVector.map { p =>
          Field(
            p.name.value,
            leanType(p.decltpe.getOrElse(sys.error(s"untyped field ${p.name.value}")))
          )
        }

      def parentOf(inits: List[Init]): Option[String] = inits.headOption.map(_.tpe match {
        case Type.Name(n) => n
        case other => sys.error(s"outside the extractable subset: parent ${other.syntax}")
      })

      def hasMod[T](
        mods: List[Mod]
      )(
        implicit tag: scala.reflect.ClassTag[T]
      ): Boolean =
        mods.exists(tag.runtimeClass.isInstance)

      // a type is declared where its trait / lone class stands; its children come from
      // wherever they are in the object
      val children: Map[String, Vector[Ctor]] = {
        val classes = stats.collect {
          case c: Defn.Class if hasMod[Mod.Case](c.mods) && c.templ.inits.nonEmpty =>
            parentOf(c.templ.inits).get -> Ctor(c.name.value, fieldsOf(c))
        }
        val objects = stats.collect {
          case o: Defn.Object if hasMod[Mod.Case](o.mods) && o.templ.inits.nonEmpty =>
            parentOf(o.templ.inits).get -> Ctor(o.name.value, Vector.empty)
        }
        // keep the source order: classes and objects interleave in the file
        val ordered = stats.flatMap {
          case c: Defn.Class if hasMod[Mod.Case](c.mods) && c.templ.inits.nonEmpty =>
            Some(parentOf(c.templ.inits).get -> Ctor(c.name.value, fieldsOf(c)))
          case o: Defn.Object if hasMod[Mod.Case](o.mods) && o.templ.inits.nonEmpty =>
            Some(parentOf(o.templ.inits).get -> Ctor(o.name.value, Vector.empty))
          case _ => None
        }
        require(ordered.size == classes.size + objects.size)
        ordered.groupBy(_._1).map { case (k, v) => k -> v.map(_._2).toVector }
      }

      val types: Vector[TypeDecl] = stats.toVector.flatMap {
        case t: Defn.Trait if hasMod[Mod.Sealed](t.mods) =>
          Some(Inductive(t.name.value, children.getOrElse(t.name.value, Vector.empty)))
        case c: Defn.Class if hasMod[Mod.Case](c.mods) && c.templ.inits.isEmpty =>
          Some(Structure(c.name.value, fieldsOf(c)))
        case _ => None
      }

      val defs: Vector[DefDecl] = stats.toVector.collect { case d: Defn.Def =>
        DefDecl(
          d.name.value,
          d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toVector.map { p =>
            Field(
              p.name.value,
              leanType(p.decltpe.getOrElse(sys.error(s"untyped parameter ${p.name.value}")))
            )
          },
          leanType(d.decltpe.getOrElse(sys.error(s"def ${d.name.value} has no result type"))),
          d.body
        )
      }

      // everything in the object is either translated or rejected: nothing is skipped silently
      stats.foreach {
        case _: Defn.Trait | _: Defn.Class | _: Defn.Object | _: Defn.Def => ()
        case other => sys.error(s"outside the extractable subset: ${other.syntax.take(120)}")
      }

      new Extraction(types, dependencyOrder(defs))
    }

    // Lean wants a def before its uses: the defs in the order of their calls (source order
    // otherwise); a def that calls itself is recursion, two that call each other are rejected
    private def dependencyOrder(defs: Vector[DefDecl]): Vector[DefDecl] = {
      val names = defs.map(_.name).toSet
      val calls: Map[String, Set[String]] = defs.map { d =>
        d.name -> d.body.collect {
          case Term.Name(n) if names.contains(n) && n != d.name => n
        }.toSet
      }.toMap
      val byName = defs.map(d => d.name -> d).toMap
      val done = scala.collection.mutable.LinkedHashSet.empty[String]
      def visit(
        name: String,
        path: List[String]
      ): Unit =
        if (!done.contains(name)) {
          if (path.contains(name))
            sys.error(
              s"mutual recursion is outside the extractable subset: ${(name :: path).reverse.mkString(" -> ")}"
            )
          // the callees in source order
          defs.map(_.name).filter(calls(name).contains).foreach(visit(_, name :: path))
          done += name
        }
      defs.foreach(d => visit(d.name, Nil))
      done.toVector.map(byName)
    }
  }
}
