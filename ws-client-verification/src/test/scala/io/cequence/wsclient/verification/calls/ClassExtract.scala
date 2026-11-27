package io.cequence.wsclient.verification.calls

import scala.meta._

import scala.collection.mutable

/**
 * Extracts a CLASS of imperative production code - private `var` fields, `val` / `var` locals,
 * `while` loops (with an early `return`), `if` statements, `throw`, arrays - into Lean, the
 * way [[ProductionExtract]] extracts pure objects. The two delimiter framers are such classes.
 *
 * The translation, all of it mechanical:
 *   - the constructor parameters are the first parameters of every method, the `var` fields
 *     the structure `State`; a method that (transitively) assigns a field takes the state and
 *     returns `Except Failure (State x result)`, a method that does not returns `Except
 *     Failure result`; a `throw new E(..)` is `throw Failure.E` (the message is not modelled,
 *     the class is);
 *   - a method body is a Lean `do` block: locals are `let mut`, an assignment to a field is `s
 *     := { s with f := e }`, a call of a stateful method rebinds `s`;
 *   - a `while` loop is a top-level recursive def over a step counter: `fuel` is a parameter
 *     of every method, a loop starts with `steps := fuel` and fails with `Failure.outOfFuel`
 *     when it runs out - a theorem about the code says how much fuel is enough (the extraction
 *     decides nothing about termination); callees are given the method's own `fuel`. A loop
 *     returns the state and the locals it assigns; an early `return e` leaves it as `Sum.inl`;
 *   - an array is a `List UInt8`, a `Bytes` / `ByteBuffer` its content, a `Seq.newBuilder` the
 *     list built so far (`+=` appends), `System.arraycopy` / `Arrays.copyOf*` the functions of
 *     `Java.lean`, `Int` / `Long` Lean's `Int` (no overflow: the proofs bound the sizes). An
 *     array read out of bounds is `none` (Java throws; the proofs show the reads are in
 *     bounds).
 *
 * Anything else fails the extraction.
 */
object ClassExtract {

  import ProductionExtract._

  // `f(args)`
  private object Call {
    def unapply(t: Tree): Option[(Term, List[Term])] = t match {
      case a: Term.Apply => Some((a.fun, a.argClause.values))
      case _             => None
    }
  }

  // `Seq.newBuilder[T]`: the element type
  private def builderElement(t: Term): Option[Type] = t match {
    case a: Term.ApplyType =>
      a.fun match {
        case Term.Select(Term.Name("Seq"), Term.Name("newBuilder")) =>
          a.targClause.values.headOption
        case _ => None
      }
    case _ => None
  }

  final case class Target(
    name: String,
    source: String,
    cls: String,
    out: String,
    namespace: String,
    imports: List[String],
    methods: List[String],
    about: String,
    // CHECKED: every Int / Long operation and array operation that can throw or wrap in Java is
    // a step of `Except` (see ProductionExtract.Translator): the file proves nothing by itself,
    // the refinement says none of them fails
    checked: Boolean = false
  )

  def lean(
    target: Target,
    source: String
  ): String = {
    implicit val dialect: Dialect = dialects.Scala213
    val tree = source.parse[Source].get
    val found = tree.collect { case c: Defn.Class if c.name.value == target.cls => c }
    require(
      found.size == 1,
      s"class ${target.cls}: ${found.size} definitions in ${target.source}"
    )
    new Extraction(target, found.head).render()
  }

  private final class Extraction(
    target: Target,
    cls: Defn.Class
  ) {

    private def fail(
      what: String,
      tree: Tree
    ): Nothing = sys.error(s"outside the extractable subset: $what: ${tree.syntax.take(200)}")

    // ------------------------------------------------------------------------ declarations

    private val ctorParams: List[(String, Ty)] =
      cls.ctor.paramClauses.flatMap(_.values).toList.map { p =>
        p.name.value -> scalaTypeOf(
          p.decltpe.getOrElse(sys.error(s"untyped parameter ${p.name.value}")),
          Set.empty
        )
      }
    private val ctorEnv: Map[String, Ty] = ctorParams.toMap

    private val stats = cls.templ.stats

    private val baseCtx = new Ctx(Map.empty, Map.empty, Map.empty, Map.empty, Set.empty)

    // the fields: `private var name = init`, typed by their initializer
    private val fieldDecls: List[(String, Term, Ty)] = stats.collect { case v: Defn.Var =>
      val name = v.pats match {
        case List(Pat.Var(n)) => n.value
        case _                => fail("a var pattern", v)
      }
      val init = v.rhs.getOrElse(fail("a field without an initializer", v))
      val (_, ty) = new Translator(baseCtx).term(init, ctorEnv, 2)
      (name, init, ty)
    }
    private val fields: Map[String, Ty] = fieldDecls.map(f => f._1 -> f._3).toMap
    private val fieldNames: Set[String] = fields.keySet

    stats.foreach {
      case _: Defn.Var | _: Defn.Def => ()
      // the constructor's preconditions are the hypotheses of the theorems
      case Call(Term.Name("require"), _) => ()
      case other                         => fail("a class member", other)
    }

    private final case class Method(
      name: String,
      params: List[(String, Ty)],
      result: Ty,
      body: Term,
      source: String
    )

    private val methods: List[Method] = stats.collect {
      case d: Defn.Def if target.methods.contains(d.name.value) =>
        Method(
          d.name.value,
          d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList.map { p =>
            p.name.value -> scalaTypeOf(
              p.decltpe.getOrElse(sys.error(s"untyped parameter ${p.name.value}")),
              Set.empty
            )
          },
          scalaTypeOf(
            d.decltpe.getOrElse(sys.error(s"${d.name.value} has no result type")),
            Set.empty
          ),
          d.body,
          d.syntax
        )
    }
    target.methods.foreach(n => require(methods.exists(_.name == n), s"method $n not found"))
    private val methodNames: Set[String] = methods.map(_.name).toSet

    // a method is stateful when it assigns a field or calls a stateful method
    private def assignsField(t: Tree): Boolean = t.collect {
      case Term.Assign(Term.Name(n), _) if fieldNames(n) => true
      case i: Term.ApplyInfix
          if i.lhs.isInstanceOf[Term.Name] && isOpAssign(i.op.value) &&
            fieldNames(i.lhs.asInstanceOf[Term.Name].value) =>
        true
      case a: Term.Apply if isArrayCopy(a) && fieldNames(arrayCopyTarget(a)) => true
    }.nonEmpty

    private def isOpAssign(op: String): Boolean = Set("+=", "-=")(op)

    private def isArrayCopy(a: Term.Apply): Boolean = a.fun match {
      case Term.Select(Term.Name("System"), Term.Name("arraycopy")) =>
        a.argClause.values.size == 5
      case _ => false
    }

    private def arrayCopyTarget(a: Term.Apply): String = a.argClause.values(2) match {
      case Term.Name(n) => n
      case other        => fail("an arraycopy into something that is not a variable", other)
    }

    private val stateful: Set[String] = {
      var set = methods.filter(m => assignsField(m.body)).map(_.name).toSet
      var changed = true
      while (changed) {
        val next = methods.filter { m =>
          set(m.name) || m.body.collect {
            case Call(Term.Name(c), _) if set(c) => c
          }.nonEmpty
        }.map(_.name).toSet
        changed = next != set
        set = next
      }
      set
    }

    // the exceptions thrown: `throw new E(...)`
    private val failures: List[String] = methods
      .flatMap(_.body.collect { case Term.Throw(Term.New(init)) =>
        init.tpe match {
          case Type.Name(n) => n
          case other        => fail("a thrown type", other)
        }
      })
      .distinct

    // the Lean type of what a method returns (inside Except Failure)
    private def returns(m: Method): String =
      (stateful(m.name), m.result) match {
        case (true, TUnit)  => "State"
        case (true, r)      => s"State × ${r.atom}"
        case (false, TUnit) => "Unit"
        case (false, r)     => r.lean
      }

    // ------------------------------------------------------------------------------ render

    private val CheckedFailures =
      List(
        "intOverflow",
        "longOverflow",
        "indexOutOfBounds",
        "illegalArgument",
        "negativeSize"
      )

    // the Java semantics of the operations the checked extraction makes steps of `Except`: the
    // pure functions are `Java.lean`'s / `Scala.lean`'s (written from the javadoc, run against the
    // JVM by the `lib` conformance target)
    private val CheckedHelpers: String =
      """
        |/-- A Java operation that throws, as a failure of this code. -/
        |def thrown {α : Type} : Except Java.Throw α → Except Failure α
        |  | .ok a => .ok a
        |  | .error .indexOutOfBounds => .error .indexOutOfBounds
        |  | .error .illegalArgument => .error .illegalArgument
        |  | .error .negativeSize => .error .negativeSize
        |
        |/-- A Java `int` result: the value, or an overflow (Java wraps around). -/
        |def i32 (x : Int) : Except Failure Int :=
        |  match Scala.int32? x with
        |  | some v => .ok v
        |  | none => .error .intOverflow
        |
        |/-- A Java `long` result. -/
        |def i64 (x : Int) : Except Failure Int :=
        |  match Scala.int64? x with
        |  | some v => .ok v
        |  | none => .error .longOverflow
        |
        |def readArr (a : List UInt8) (i : Int) : Except Failure UInt8 := thrown (Java.readChecked a i)
        |
        |def copyOfRangeC (a : List UInt8) (f t : Int) : Except Failure (List UInt8) :=
        |  thrown (Java.copyOfRangeChecked a f t)
        |
        |def copyOfC (a : List UInt8) (n : Int) : Except Failure (List UInt8) :=
        |  thrown (Java.copyOfChecked a n)
        |
        |def newArrayC (n : Int) : Except Failure (List UInt8) := thrown (Java.newArrayChecked n)
        |
        |def concatC (a b : List UInt8) : Except Failure (List UInt8) :=
        |  thrown (Java.concatChecked a b)
        |
        |def arraycopyC (src : List UInt8) (sp : Int) (dst : List UInt8) (dp len : Int) :
        |    Except Failure (List UInt8) :=
        |  thrown (Java.arraycopyChecked src sp dst dp len)
        |""".stripMargin

    private val ctorText: String =
      ctorParams.map { case (n, t) => s" (${esc(n)} : ${t.lean})" }.mkString
    private val ctorArgs: String = ctorParams.map { case (n, _) => " " + esc(n) }.mkString

    private val loopDefs = mutable.ListBuffer.empty[String]

    def render(): String = {
      val ordered = order(methods)
      val defs = ordered.map(method)
      val sb = new StringBuilder
      target.imports.foreach(i => sb.append(s"import $i\n"))
      sb.append("\n/-!\n")
      sb.append(s"GENERATED by `sbt leanExtract` from\n${target.source}\n")
      sb.append(
        s"(class ${target.cls}: ${target.methods.mkString(", ")}) - do not edit; change the\n"
      )
      sb.append(
        "Scala code and extract again. `sbt \"leanExtract --check\"` fails when this file is\n"
      )
      sb.append("out of date.\n\n")
      sb.append(target.about).append("\n-/\n")
      sb.append(
        s"\nnamespace ${target.namespace}\n\nset_option linter.unusedVariables false\n"
      )
      sb.append(
        "\n/-- The exceptions the code throws (their messages are not modelled), and running\nout of the fuel a loop is given. -/\n"
      )
      sb.append("inductive Failure where\n")
      (failures ++ (if (target.checked) CheckedFailures else Nil) :+ "outOfFuel").foreach(f =>
        sb.append(s"  | $f\n")
      )
      sb.append("  deriving DecidableEq, Repr\n")
      if (target.checked) sb.append(CheckedHelpers)
      sb.append("\n/-- The mutable fields. -/\nstructure State where\n")
      fieldDecls.foreach { case (n, _, t) => sb.append(s"  ${esc(n)} : ${t.lean}\n") }
      sb.append("  deriving DecidableEq, Repr\n")
      val initFields = fieldDecls.map { case (n, init, _) =>
        val (e, _) = new Translator(baseCtx).term(init, ctorEnv, 2)
        s"${esc(n)} := $e"
      }
      sb.append(
        s"\n/-- The fields as the constructor leaves them. -/\ndef init$ctorText : State :=\n  { ${initFields
            .mkString(", ")} }\n"
      )
      defs.foreach(sb.append)
      sb.append(s"\nend ${target.namespace}\n")
      sb.result()
    }

    // callees first
    private def order(ms: List[Method]): List[Method] = {
      val done = mutable.LinkedHashSet.empty[String]
      def visit(
        m: Method,
        path: List[String]
      ): Unit =
        if (!done(m.name)) {
          require(
            !path.contains(m.name),
            s"recursion between methods: ${(m.name :: path).reverse}"
          )
          m.body.collect { case Call(Term.Name(c), _) if methodNames(c) => c }.distinct
            .foreach(c => visit(ms.find(_.name == c).get, m.name :: path))
          done += m.name
        }
      ms.foreach(visit(_, Nil))
      done.toList.map(n => ms.find(_.name == n).get)
    }

    private def method(m: Method): String = {
      loopDefs.clear()
      val tr = new MethodTranslation(m)
      val body = tr.body()
      val quote = m.source.linesIterator.map(l => s"  $l").mkString("\n")
      require(
        !quote.contains("-/") && !quote.contains("/-"),
        s"${m.name}: a comment delimiter"
      )
      val params = m.params.map { case (n, t) => s" (${esc(n)} : ${t.lean})" }.mkString
      val loops = loopDefs.toList.map(_ + "\n").mkString
      s"\n$loops/- Scala:\n$quote\n-/\ndef ${esc(m.name)}$ctorText (fuel : Nat) (s : State)$params :\n    Except Failure (${returns(
          m
        )}) := do\n${if (stateful(m.name)) "  let mut s := s\n" else ""}$body\n"
    }

    // ------------------------------------------------------------------------- statements

    private final case class Local(
      name: String,
      ty: Ty,
      mutable: Boolean
    )

    private sealed trait Tail
    // the method's value: the last statement
    private case object ValueTail extends Tail
    // a Unit method: the state at the end
    private case object UnitTail extends Tail
    // a loop body: the recursive call
    private final case class LoopTail(call: String) extends Tail
    // an `if` branch inside a loop or a method: nothing follows in this block
    private case object NoTail extends Tail

    private final class MethodTranslation(m: Method) {

      private val translator = new Translator(
        new Ctx(Map.empty, Map.empty, Map.empty, Map.empty, Set.empty, fields),
        target.checked
      )
      private val builders = mutable.Set.empty[String]
      private var loops = 0
      // a `return` inside a loop
      private var inLoop = false

      private def ret(value: Option[String]): String =
        (stateful(m.name), m.result) match {
          case (true, TUnit)  => "s"
          case (true, _)      => s"(s, ${value.get})"
          case (false, TUnit) => "()"
          case (false, _)     => value.get
        }

      def body(): String = {
        val locals = m.params.map { case (n, t) => Local(n, t, mutable = false) }.toVector
        val statements = m.body match {
          case Term.Block(ss) => ss
          case single         => List(single)
        }
        val tail = if (m.result == TUnit) UnitTail else ValueTail
        lines(statements, locals, 1, tail).mkString("\n")
      }

      private def envOf(locals: Vector[Local]): Map[String, Ty] =
        ctorEnv ++ locals.map(l => l.name -> l.ty)

      private def expr(
        t: Term,
        locals: Vector[Local],
        ind: Int,
        expected: Ty = TUnknown
      ): (String, Ty) = translator.term(t, envOf(locals), 2 * ind + 2, expected)

      private def isStatefulCall(t: Term): Option[(String, List[Term])] = t match {
        case a: Term.Apply =>
          a.fun match {
            case Term.Name(c) if stateful(c) => Some((c, a.argClause.values))
            case _                           => None
          }
        case _ => None
      }

      private def callText(
        c: String,
        args: List[Term],
        locals: Vector[Local],
        ind: Int
      ): String = {
        val callee = methods.find(_.name == c).get
        require(args.size == callee.params.size, s"call of $c: ${args.size} arguments")
        val translated = args.zip(callee.params).map { case (a, (_, pt)) =>
          atom(expr(a, locals, ind, pt)._1)
        }
        s"${esc(c)}$ctorArgs fuel s${translated.map(" " + _).mkString}"
      }

      private var fresh = 0
      private def tmp(base: String): String = { fresh += 1; s"${base}_$fresh" }

      // the statements of a block as Lean do-lines
      private def lines(
        ss: List[Stat],
        locals0: Vector[Local],
        ind: Int,
        tail: Tail
      ): List[String] = {
        val out = mutable.ListBuffer.empty[String]
        var locals = locals0
        val pad = "  " * ind
        def add(l: String): Unit = {
          translator
            .takePre()
            .foreach(entry => entry.linesIterator.foreach(x => out += pad + x))
          out += pad + l
        }
        // the statements; the value of a ValueTail method is the last one
        val (init, last) = tail match {
          case ValueTail => (ss.init, Some(ss.last))
          case _         => (ss, None)
        }
        var skip = false
        init.zipWithIndex.foreach { case (st, idx) =>
          if (skip) skip = false
          else
            st match {
              // val builder = Seq.newBuilder[T]
              case v: Defn.Val if v.pats.size == 1 && builderElement(v.rhs).isDefined =>
                val n = v.pats.head.asInstanceOf[Pat.Var].name.value
                val ty = TList(scalaTypeOf(builderElement(v.rhs).get, Set.empty))
                add(s"let mut ${esc(n)} : ${ty.lean} := []")
                builders += n
                locals = locals :+ Local(n, ty, mutable = true)

              // val incoming = new Array[Byte](input.remaining()); input.get(incoming)
              case v: Defn.Val
                  if v.rhs.isInstanceOf[Term.New] && v.pats.size == 1 && idx + 1 < init.size &&
                    isGetInto(init(idx + 1), v.pats.head.asInstanceOf[Pat.Var].name.value) =>
                val n = v.pats.head.asInstanceOf[Pat.Var].name.value
                val source = getSource(init(idx + 1))
                val (src, sty) = expr(source, locals, ind)
                require(sty == TList(TByte), "ByteBuffer.get from a non-buffer")
                requireAllocationOf(v.rhs.asInstanceOf[Term.New], source)
                add(s"let ${esc(n)} : List UInt8 := $src")
                locals = locals :+ Local(n, TList(TByte), mutable = false)
                skip = true

              case v: Defn.Val =>
                locals = declare(v.pats, v.decltpe, v.rhs, mutable = false, locals, ind, add)
              case v: Defn.Var =>
                locals = declare(
                  v.pats,
                  v.decltpe,
                  v.rhs.getOrElse(fail("a var without a value", v)),
                  mutable = true,
                  locals,
                  ind,
                  add
                )

              case Term.Assign(Term.Name(n), rhs) => assign(n, rhs, locals, ind, add)

              case i: Term.ApplyInfix
                  if i.lhs.isInstanceOf[Term.Name] && isOpAssign(i.op.value) =>
                opAssign(i, locals, ind, add)

              case i: Term.If =>
                val (cond, _) = expr(i.cond, locals, ind)
                add(s"if $cond then")
                out ++= branch(i.thenp, locals, ind + 1)
                i.elsep match {
                  case Lit.Unit() => ()
                  case e =>
                    add("else")
                    out ++= branch(e, locals, ind + 1)
                }

              case w: Term.While =>
                locals = loop(w, locals, ind, add)

              case Term.Return(value) =>
                require(inLoop, "a return outside a loop")
                val (v, _) = expr(value, locals, ind, m.result)
                add(s"return Sum.inl ${atom(ret(Some(atom(v))))}")

              case Term.Throw(Term.New(init)) => add(s"throw Failure.${thrown(init)}")

              case a: Term.Apply if isStatefulCall(a).isDefined =>
                val (c, args) = isStatefulCall(a).get
                val callee = methods.find(_.name == c).get
                require(callee.result == TUnit, s"the value of $c is dropped")
                add(s"s ← ${callText(c, args, locals, ind)}")

              case a: Term.Apply if isArrayCopy(a) => arrayCopy(a, locals, ind, add)

              case Term.Block(inner) =>
                out ++= lines(inner, locals, ind, NoTail)

              case other => fail("a statement", other)
            }
        }
        tail match {
          case ValueTail      => out ++= valueTail(last.get, locals, ind)
          case UnitTail       => add(s"return ${ret(None)}")
          case LoopTail(call) => add(call)
          case NoTail         => ()
        }
        out.toList
      }

      // the bindings hoisted while translating an expression, as lines at `pad`
      private def preLines(pad: String): List[String] =
        translator.takePre().flatMap(_.linesIterator).map(pad + _)

      private def thrown(init: Init): String = init.tpe match {
        case Type.Name(n) => n
        case other        => fail("a thrown type", other)
      }

      private def branch(
        t: Term,
        locals: Vector[Local],
        ind: Int
      ): List[String] = t match {
        case Term.Block(ss) => lines(ss, locals, ind, NoTail)
        case single         => lines(List(single), locals, ind, NoTail)
      }

      // the value of a method: an if / else chain, a throw, or an expression
      private def valueTail(
        t: Stat,
        locals: Vector[Local],
        ind: Int
      ): List[String] = {
        val pad = "  " * ind
        t match {
          case i: Term.If =>
            val (cond, _) = expr(i.cond, locals, ind)
            preLines(pad) ++ List(s"${pad}if $cond then") ++
              valueBranch(i.thenp, locals, ind + 1) ++
              List(s"${pad}else") ++ valueBranch(i.elsep, locals, ind + 1)
          case Term.Throw(Term.New(init)) => List(s"${pad}throw Failure.${thrown(init)}")
          case Term.Block(ss)             => lines(ss, locals, ind, ValueTail)
          case term: Term =>
            val (v, _) = expr(term, locals, ind, m.result)
            preLines(pad) ++ List(s"${pad}return ${atom(ret(Some(atom(v))))}")
          case other => fail("a method value", other)
        }
      }

      private def valueBranch(
        t: Term,
        locals: Vector[Local],
        ind: Int
      ): List[String] = t match {
        case Term.Block(ss) => lines(ss, locals, ind, ValueTail)
        case single         => valueTail(single, locals, ind)
      }

      // ---- declarations and assignments

      private def declare(
        pats: List[Pat],
        decl: Option[Type],
        rhs: Term,
        mutable: Boolean,
        locals: Vector[Local],
        ind: Int,
        add: String => Unit
      ): Vector[Local] = {
        val name = pats match {
          case List(Pat.Var(n)) => n.value
          case _                => fail("a pattern", pats.head)
        }
        val mut = if (mutable) "mut " else ""
        isStatefulCall(rhs) match {
          case Some((c, args)) =>
            val callee = methods.find(_.name == c).get
            val s1 = tmp("s")
            val r1 = tmp("r")
            add(s"let ($s1, $r1) ← ${callText(c, args, locals, ind)}")
            add(s"s := $s1")
            add(s"let $mut${esc(name)} : ${callee.result.lean} := $r1")
            locals :+ Local(name, callee.result, mutable)
          case None =>
            val declared = decl.map(scalaTypeOf(_, Set.empty))
            val (e, ty0) = expr(rhs, locals, ind, declared.getOrElse(TUnknown))
            val ty = declared.getOrElse(ty0)
            add(s"let $mut${esc(name)} : ${ty.lean} :=${assignRhs(e, ind)}")
            locals :+ Local(name, ty, mutable)
        }
      }

      private def assignRhs(
        e: String,
        ind: Int
      ): String =
        if (e.contains('\n')) s"\n${"  " * (ind + 1)}($e)" else s" $e"

      private def assign(
        n: String,
        rhs: Term,
        locals: Vector[Local],
        ind: Int,
        add: String => Unit
      ): Unit =
        locals.find(_.name == n) match {
          case Some(l) =>
            require(l.mutable, s"assignment to the val $n")
            isStatefulCall(rhs) match {
              case Some((c, args)) =>
                val s1 = tmp("s")
                val r1 = tmp("r")
                add(s"let ($s1, $r1) ← ${callText(c, args, locals, ind)}")
                add(s"s := $s1")
                add(s"${esc(n)} := $r1")
              case None =>
                val (e, _) = expr(rhs, locals, ind, l.ty)
                add(s"${esc(n)} :=${assignRhs(e, ind)}")
            }
          case None if fieldNames(n) =>
            val (e, _) = expr(rhs, locals, ind, fields(n))
            add(s"s := { s with ${esc(n)} :=${assignRhs(e, ind)} }")
          case None => sys.error(s"assignment to the unknown $n")
        }

      private def opAssign(
        i: Term.ApplyInfix,
        locals: Vector[Local],
        ind: Int,
        add: String => Unit
      ): Unit = {
        val n = i.lhs.asInstanceOf[Term.Name].value
        val rhs = i.argClause.values.head
        val op = i.op.value.dropRight(1)
        if (builders(n)) {
          require(op == "+", s"$op= on a builder")
          val (e, _) = expr(rhs, locals, ind)
          add(s"$n := $n ++ [${e}]")
        } else if (target.checked) {
          // the operation as an expression: its overflow check is hoisted in front of it
          val plain = Term.ApplyInfix(
            i.lhs,
            Term.Name(op),
            Type.ArgClause(Nil),
            Term.ArgClause(List(rhs))
          )
          val (e, _) = expr(plain, locals, ind)
          locals.find(_.name == n) match {
            case Some(l) =>
              require(l.mutable, s"$n $op= on a val")
              add(s"${esc(n)} := $e")
            case None if fieldNames(n) => add(s"s := { s with ${esc(n)} := $e }")
            case None                  => sys.error(s"assignment to the unknown $n")
          }
        } else {
          val (e, t) = expr(rhs, locals, ind, TInt)
          require(isNum(t), s"$n $op= a non-Int")
          locals.find(_.name == n) match {
            case Some(l) =>
              require(l.mutable, s"$n $op= on a val")
              add(s"${esc(n)} := ${esc(n)} $op ${atom(e)}")
            case None if fieldNames(n) =>
              add(s"s := { s with ${esc(n)} := s.${esc(n)} $op ${atom(e)} }")
            case None => sys.error(s"assignment to the unknown $n")
          }
        }
      }

      // System.arraycopy(src, srcPos, dest, destPos, length): the new contents of dest
      private def arrayCopy(
        a: Term.Apply,
        locals: Vector[Local],
        ind: Int,
        add: String => Unit
      ): Unit = {
        val args = a.argClause.values
        val translated = args.map(x => atom(expr(x, locals, ind, TInt)._1))
        val Seq(src, sp, dest, dp, len) = translated
        val dst = arrayCopyTarget(a)
        val copy =
          if (target.checked) {
            val t = tmp("copied")
            add(s"let $t ← arraycopyC $src $sp $dest $dp $len")
            t
          } else s"Java.arraycopy $src ($sp).toNat $dest ($dp).toNat ($len).toNat"
        if (locals.exists(_.name == dst)) add(s"${esc(dst)} := $copy")
        else add(s"s := { s with ${esc(dst)} := $copy }")
      }

      // ---- the get-into idiom for a ByteBuffer

      private def isGetInto(
        st: Stat,
        array: String
      ): Boolean = st match {
        case Call(Term.Select(_, Term.Name("get")), List(Term.Name(a))) => a == array
        case _                                                          => false
      }

      private def getSource(st: Stat): Term = st match {
        case Call(Term.Select(src, Term.Name("get")), _) => src
        case other                                       => fail("a get", other)
      }

      // the array is exactly as long as what is left in the buffer, so `get` copies all of it
      private def requireAllocationOf(
        allocation: Term.New,
        source: Term
      ): Unit = allocation.init.argClauses.flatMap(_.values) match {
        case List(Call(Term.Select(src, Term.Name("remaining")), Nil))
            if src.structure == source.structure =>
          ()
        case _ =>
          fail("an array that is not allocated to the buffer's remaining()", allocation)
      }

      // ------------------------------------------------------------------------- loops

      private def loop(
        w: Term.While,
        locals: Vector[Local],
        ind: Int,
        add: String => Unit
      ): Vector[Local] = {
        loops += 1
        val name = s"${m.name}_loop$loops"
        val bodyStats = w.body match {
          case Term.Block(ss) => ss
          case single         => List(single)
        }
        val whole: List[Tree] = List(w.expr, w.body)
        val names = whole.flatMap(_.collect { case Term.Name(n) => n }).toSet
        val mutableLocals = locals.filter(_.mutable).map(_.name).toSet
        val assigned: Set[String] = whole
          .flatMap(_.collect {
            case Term.Assign(Term.Name(n), _) if mutableLocals(n) => n
            case i: Term.ApplyInfix
                if i.lhs.isInstanceOf[Term.Name] && isOpAssign(i.op.value) &&
                  mutableLocals(i.lhs.asInstanceOf[Term.Name].value) =>
              i.lhs.asInstanceOf[Term.Name].value
          })
          .toSet
        val usesState = whole.exists(assignsField) || whole.exists(_.collect {
          case Call(Term.Name(c), _) if stateful(c) => c
        }.nonEmpty)
        val hasReturn = whole.exists(_.collect { case r: Term.Return => r }.nonEmpty)
        val used = locals.filter(l => names(l.name) || assigned(l.name))
        val mutated = used.filter(l => assigned(l.name))
        val components: List[(String, Ty, Boolean)] =
          (if (usesState) List(("s", TUnknown, true)) else Nil) ++
            mutated.map(l => (l.name, l.ty, false))
        require(components.nonEmpty, "a loop that assigns nothing")

        def tupleTy: String = components.map {
          case ("s", _, true) => "State"
          case (_, t, _)      => t.atom
        }.mkString(" × ")
        val resultTy = if (hasReturn) s"Sum (${returns(m)}) ($tupleTy)" else tupleTy

        def tupleExpr(names: List[String]): String =
          if (names.size == 1) names.head else names.mkString("(", ", ", ")")

        val localParams = used.map(l => s" (${esc(l.name)} : ${l.ty.lean})").mkString
        val recursive =
          s"$name$ctorArgs fuel steps s${used.map(l => " " + esc(l.name)).mkString}"
        val loopLocals = used.map(l => l.copy(mutable = assigned(l.name)))

        val wasInLoop = inLoop
        inLoop = true
        val (cond, _) = expr(w.expr, loopLocals, 2)
        // what the condition hoists (a checked read, ...) runs before it, in the def
        val condPre = preLines("    ")
        val bodyLines = lines(bodyStats, loopLocals, 3, LoopTail(recursive))
        inLoop = wasInLoop

        val exit = {
          val t = tupleExpr(components.map { case (n, _, _) => esc(n) })
          if (hasReturn) s"return Sum.inr $t" else s"return $t"
        }
        val mutLines =
          (if (usesState) List("    let mut s := s") else Nil) ++
            mutated.map(l => s"    let mut ${esc(l.name)} := ${esc(l.name)}")
        val text =
          s"""/-- The loop `while (${w.expr.syntax
              .replace("\n", " ")})` of `${m.name}`: `steps` is what is
             |left of the fuel; the result is ${components.map(_._1).mkString(", ")}${if (
              hasReturn
            ) " (or the value of a `return`)"
            else ""}. -/
             |def $name$ctorText (fuel : Nat) (steps : Nat) (s : State)$localParams :
             |    Except Failure ($resultTy) :=
             |  match steps with
             |  | 0 => throw Failure.outOfFuel
             |  | steps + 1 => do
             |${mutLines.mkString("\n")}${if (mutLines.nonEmpty) "\n" else ""}${condPre
              .map(_ + "\n")
              .mkString}    if $cond then
             |${bodyLines.mkString("\n")}
             |    else
             |      $exit
             |""".stripMargin
        loopDefs += text

        // the call: rebinding what the loop assigns
        val call =
          s"$name$ctorArgs fuel fuel s${used.map(l => " " + esc(l.name)).mkString}"
        val comps = components.map(_._1)
        def rebind(
          vars: List[String],
          pad: String
        ): Unit =
          comps.zip(vars).foreach { case (c, v) => add(s"$pad${esc(c)} := $v") }
        val fresh = comps.map(c => tmp(if (c == "s") "s" else c))
        if (hasReturn) {
          val r = tmp("r")
          add(s"let $r ← $call")
          add(s"match $r with")
          add("| .inl v => return v")
          add(s"| .inr ${tupleExpr(fresh)} =>")
          rebind(fresh, "    ")
        } else if (comps.size == 1) {
          add(s"${esc(comps.head)} ← $call")
        } else {
          add(s"let ${tupleExpr(fresh)} ← $call")
          rebind(fresh, "")
        }
        locals
      }
    }
  }
}
