package io.cequence.wsclient.verification.calls

import scala.meta._

import scala.collection.mutable

/**
 * Extracts Lean definitions FROM PRODUCTION Scala source: the methods of a shipped object are
 * parsed and translated construct by construct - no model file in between, nothing
 * transliterated by hand. The Lean the proofs reason about is the translation of the code the
 * engines run.
 *
 * What the translation trusts, and nothing else:
 *   - the Lean prelude of the Java / Scala library calls
 *     (`formal/lean/WsClientProofs/Scala.lean`, `Java.lean`, `JavaString.lean`, the JDK URL
 *     encoder), each written from the library's documentation and checked against the JVM by
 *     the `lib` target of `LeanConformance`;
 *   - this translator, checked end to end by the conformance targets of the extracted code
 *     (production Scala against the generated Lean on random inputs) and by the planted-bug
 *     runs recorded in `formal/lean/README.md`.
 *
 * The subset is small and a construct outside it fails the extraction (nothing is skipped
 * silently): `Int` / `Boolean` / `Char` / `String` (a `List Char`) / `Seq` / `List` / `Option`
 * / tuples / the object's own sealed types; `def`, `val`, `if`, `match`, blocks of `val`s,
 * lambdas, `+` `-` `*` comparisons `==` `!=` `&&` `||` `::` `++`, string interpolation, and
 * the library calls of [[Translator.method]] / [[Translator.static]]. An exception path is
 * outside the model: a `try` whose every handler rethrows is its body.
 *
 * `Int` is Lean's unbounded `Int` (the extracted code is checked on inputs far below 2^31), a
 * `String` is a text of Unicode scalar values (a Java string with an unpaired surrogate is
 * outside the model), and a value of type `Any` is its `toString` text (the only thing the
 * extracted code does with it).
 */
object ProductionExtract {

  /**
   * One object of production code to extract: the members named in `take` (a sealed trait
   * takes its case classes and objects along), in dependency order.
   */
  final case class Target(
    name: String,
    source: String,
    obj: String,
    out: String,
    namespace: String,
    imports: List[String],
    take: List[String],
    about: String
  )

  // ------------------------------------------------------------------------------- types

  sealed trait Ty {
    def lean: String
    def atom: String = if (lean.contains(' ')) s"($lean)" else lean
  }
  case object TInt extends Ty { val lean = "Int" }
  case object TBool extends Ty { val lean = "Bool" }
  case object TChar extends Ty { val lean = "Char" }
  case object TText extends Ty { val lean = "List Char" }
  final case class TList(elem: Ty) extends Ty { def lean = s"List ${elem.atom}" }
  final case class TOpt(elem: Ty) extends Ty { def lean = s"Option ${elem.atom}" }
  final case class TTuple(elems: List[Ty]) extends Ty {
    def lean = elems.map(_.atom).mkString(" × ")
  }
  final case class TUser(lean: String) extends Ty
  case object TUnknown extends Ty { val lean = "_" }

  private val PatternType = TUser("Scala.Pattern")
  private val JsValueType = TUser("Scala.JsValue")

  // a type known on one side only is the other's
  private def join(
    a: Ty,
    b: Ty
  ): Ty = (a, b) match {
    case (TUnknown, x)            => x
    case (x, TUnknown)            => x
    case (TList(x), TList(y))     => TList(join(x, y))
    case (TOpt(x), TOpt(y))       => TOpt(join(x, y))
    case (TTuple(xs), TTuple(ys)) => TTuple(xs.zip(ys).map { case (x, y) => join(x, y) })
    case (x, _)                   => x
  }

  // ------------------------------------------------------------------------------ Lean text

  /** A text literal as a list of characters (ASCII only: nothing else occurs in the code). */
  private[calls] def charList(s: String): String =
    if (s.isEmpty) "[]"
    else {
      require(s.forall(_ < '\u0080'), s"a non-ASCII text literal is outside the subset: $s")
      s.map(leanChar).mkString("[", ", ", "]")
    }

  private[calls] def leanChar(c: Char): String = c match {
    case '\\'                          => "'\\\\'"
    case '\''                          => "'\\''"
    case '\n'                          => "'\\n'"
    case '\r'                          => "'\\r'"
    case '\t'                          => "'\\t'"
    case x if x < ' ' || x == '\u007f' => f"'\\x${x.toInt}%02x'"
    case x                             => s"'$x'"
  }

  // an operand: a name, a literal, a bracketed expression stands as it is
  private def atom(s: String): String = {
    val simple = s.matches("[A-Za-z0-9_.«»']+")
    if (simple || wrapped(s)) s else s"($s)"
  }

  // the whole text is one parenthesized / bracketed group
  private def wrapped(s: String): Boolean =
    s.nonEmpty && (s.head == '(' || s.head == '[') && {
      var depth = 0
      var closedAt = -1
      var inString = false
      var i = 0
      while (i < s.length && closedAt < 0) {
        val c = s.charAt(i)
        if (inString) {
          if (c == '\\') i += 1
          else if (c == '"') inString = false
        } else if (c == '"') inString = true
        else if (
          c == '\'' && i + 2 < s.length && s.charAt(i + 1) != '\\' && s.charAt(i + 2) == '\''
        )
          i += 2
        else if (c == '\'' && i + 3 < s.length && s.charAt(i + 1) == '\\') {
          // an escaped character literal: skip to its closing quote
          i = s.indexOf('\'', i + 2)
        } else if (c == '(' || c == '[') depth += 1
        else if (c == ')' || c == ']') {
          depth -= 1
          if (depth == 0) closedAt = i
        }
        i += 1
      }
      closedAt == s.length - 1
    }

  // ------------------------------------------------------------------------------- context

  /** What the target's members declare, for the translator to resolve names against. */
  private final class Ctx(
    val funs: Map[String, (List[Ty], Ty)],
    val consts: Map[String, Ty],
    val ctorOwner: Map[String, String],
    val ctorFields: Map[String, List[Ty]],
    val userTypes: Set[String]
  )

  private final case class Decl(
    name: String,
    params: List[(String, Ty)],
    result: Ty,
    body: Term,
    source: String
  )

  private final case class Inductive(
    name: String,
    ctors: List[(String, List[(String, Ty)])]
  )

  // ----------------------------------------------------------------------------- the target

  /** The Lean module text of one target. */
  def lean(
    target: Target,
    source: String
  ): String = {
    implicit val dialect: Dialect = dialects.Scala213
    val tree = source.parse[Source].get
    val obj = tree.collect {
      case o: Defn.Object if o.name.value == target.obj => o
    }.headOption.getOrElse(sys.error(s"object ${target.obj} not found in ${target.source}"))
    val stats = obj.templ.stats

    // every name to take must exist
    val declared: Set[String] = stats.flatMap {
      case d: Defn.Def    => List(d.name.value)
      case t: Defn.Trait  => List(t.name.value)
      case c: Defn.Class  => List(c.name.value)
      case o: Defn.Object => List(o.name.value)
      case v: Defn.Val    => v.pats.collect { case Pat.Var(n) => n.value }
      case _              => Nil
    }.toSet
    target.take.foreach(n =>
      require(declared.contains(n), s"${target.obj}.$n not found in ${target.source}")
    )

    def hasMod[T](
      mods: List[Mod]
    )(
      implicit tag: scala.reflect.ClassTag[T]
    ): Boolean =
      mods.exists(tag.runtimeClass.isInstance)

    // the sealed traits taken, with their case classes and objects (anywhere in the object)
    val sealedTraits = stats.collect {
      case t: Defn.Trait if hasMod[Mod.Sealed](t.mods) && target.take.contains(t.name.value) =>
        t.name.value
    }.toSet
    val userTypes = sealedTraits

    val foreign: Map[String, Ty] = Map("JsValue" -> JsValueType)

    def scalaType(t: Type): Ty = t match {
      case Type.Name("Int")     => TInt
      case Type.Name("Boolean") => TBool
      case Type.Name("Char")    => TChar
      case Type.Name("String")  => TText
      // a value of type Any is used only through toString: its text
      case Type.Name("Any")                    => TText
      case Type.Name(n) if foreign.contains(n) => foreign(n)
      case Type.Name(n) if userTypes(n)        => TUser(n)
      case a: Type.Apply
          if a.tpe.isInstanceOf[Type.Name] &&
            Set("Seq", "List", "Array", "Vector")(a.tpe.asInstanceOf[Type.Name].value) &&
            a.argClause.values.size == 1 =>
        TList(scalaType(a.argClause.values.head))
      case a: Type.Apply
          if a.tpe.isInstanceOf[Type.Name] &&
            a.tpe.asInstanceOf[Type.Name].value == "Option" && a.argClause.values.size == 1 =>
        TOpt(scalaType(a.argClause.values.head))
      case Type.Tuple(ts) => TTuple(ts.map(scalaType))
      case other          => sys.error(s"outside the extractable subset: type ${other.syntax}")
    }

    def fieldsOf(c: Defn.Class): List[(String, Ty)] =
      c.ctor.paramClauses.flatMap(_.values).toList.map { p =>
        p.name.value -> scalaType(
          p.decltpe.getOrElse(sys.error(s"untyped field ${p.name.value}"))
        )
      }

    def parentOf(inits: List[Init]): Option[String] = inits.headOption.map(_.tpe match {
      case Type.Name(n) => n
      case other        => sys.error(s"outside the extractable subset: parent ${other.syntax}")
    })

    // the children of a taken sealed trait, in source order
    val children: List[(String, (String, List[(String, Ty)]))] = stats.flatMap {
      case c: Defn.Class if hasMod[Mod.Case](c.mods) && c.templ.inits.nonEmpty =>
        parentOf(c.templ.inits)
          .filter(sealedTraits)
          .map(p => p -> (c.name.value -> fieldsOf(c)))
      case o: Defn.Object if hasMod[Mod.Case](o.mods) && o.templ.inits.nonEmpty =>
        parentOf(o.templ.inits).filter(sealedTraits).map(p => p -> (o.name.value -> Nil))
      case _ => None
    }

    val inductives: List[Inductive] = stats.collect {
      case t: Defn.Trait if sealedTraits(t.name.value) =>
        Inductive(t.name.value, children.filter(_._1 == t.name.value).map(_._2))
    }

    val ctorOwner: Map[String, String] =
      inductives.flatMap(i => i.ctors.map(_._1 -> i.name)).toMap
    val ctorFields: Map[String, List[Ty]] =
      inductives.flatMap(i => i.ctors.map { case (n, fs) => n -> fs.map(_._2) }).toMap

    // defs and vals, in source order
    val decls: List[Decl] = stats.flatMap {
      case d: Defn.Def if target.take.contains(d.name.value) =>
        val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map { p =>
          p.name.value -> scalaType(
            p.decltpe.getOrElse(sys.error(s"untyped parameter ${p.name.value}"))
          )
        }
        val result = scalaType(
          d.decltpe.getOrElse(sys.error(s"${target.obj}.${d.name.value} has no result type"))
        )
        List(Decl(d.name.value, params, result, d.body, d.syntax))
      case v: Defn.Val
          if v.pats.collect { case Pat.Var(n) => n.value }.exists(target.take.contains) =>
        val name = v.pats match {
          case List(Pat.Var(n)) => n.value
          case _ => sys.error(s"outside the extractable subset: a val pattern: ${v.syntax}")
        }
        // a constant: the type of its right-hand side
        val ty = v.decltpe.map(scalaType)
        List(Decl(name, Nil, ty.getOrElse(TUnknown), v.rhs, v.syntax))
      case _ => Nil
    }

    // constants are typed by translating them (against the constants before them)
    val ordered = dependencyOrder(decls)
    val consts = mutable.LinkedHashMap.empty[String, Ty]
    val funs = mutable.LinkedHashMap.empty[String, (List[Ty], Ty)]
    val typed = ordered.map { d =>
      val ctx = new Ctx(funs.toMap, consts.toMap, ctorOwner, ctorFields, userTypes)
      val translator = new Translator(ctx)
      val env = d.params.toMap
      val result =
        if (d.params.isEmpty && d.result == TUnknown) translator.term(d.body, env, 2)._2
        else d.result
      if (d.params.isEmpty) consts(d.name) = result
      else funs(d.name) = (d.params.map(_._2), result)
      // a recursive def sees itself
      val ctx2 = new Ctx(funs.toMap, consts.toMap, ctorOwner, ctorFields, userTypes)
      new Translator(ctx2).decl(d.copy(result = result))
    }

    val sb = new StringBuilder
    target.imports.foreach(i => sb.append(s"import $i\n"))
    sb.append("\n/-!\n")
    sb.append(s"GENERATED by `sbt leanExtract` from\n${target.source}\n")
    sb.append(
      s"(object ${target.obj}: ${target.take.mkString(", ")}) - do not edit; change the\n"
    )
    sb.append(
      "Scala code and extract again. `sbt \"leanExtract --check\"` fails when this file is\n"
    )
    sb.append("out of date.\n\n")
    sb.append(target.about).append("\n-/\n")
    sb.append(s"\nnamespace ${target.namespace}\n")
    inductives.foreach { i =>
      sb.append(s"\ninductive ${i.name} where\n")
      i.ctors.foreach { case (n, fs) =>
        val fields = fs.map { case (f, t) => s" (${esc(f)} : ${t.lean})" }.mkString
        sb.append(s"  | $n$fields\n")
      }
      sb.append("  deriving DecidableEq, Repr\n")
    }
    typed.foreach(sb.append)
    sb.append(s"\nend ${target.namespace}\n")
    sb.result()
  }

  // Lean wants a def before its uses: the callees first, in source order
  private def dependencyOrder(decls: List[Decl]): List[Decl] = {
    val names = decls.map(_.name).toSet
    val calls: Map[String, Set[String]] = decls.map { d =>
      d.name -> d.body.collect {
        case Term.Name(n) if names.contains(n) && n != d.name => n
      }.toSet
    }.toMap
    val byName = decls.map(d => d.name -> d).toMap
    val done = mutable.LinkedHashSet.empty[String]
    def visit(
      name: String,
      path: List[String]
    ): Unit =
      if (!done.contains(name)) {
        if (path.contains(name))
          sys.error(
            s"mutual recursion is outside the extractable subset: ${(name :: path).reverse.mkString(" -> ")}"
          )
        decls.map(_.name).filter(calls(name).contains).foreach(visit(_, name :: path))
        done += name
      }
    decls.foreach(d => visit(d.name, Nil))
    done.toList.map(byName)
  }

  private def esc(n: String): String = LeanExtract.Extraction.esc(n)

  // -------------------------------------------------------------------------- the translator

  private type Env = Map[String, Ty]

  private final class Translator(ctx: Ctx) {

    private def fail(
      what: String,
      tree: Tree
    ): Nothing =
      sys.error(s"outside the extractable subset: $what: ${tree.syntax.take(200)}")

    private def pad(n: Int): String = " " * n

    /** A def or a constant, with the Scala it comes from quoted above it. */
    def decl(d: Decl): String = {
      require(
        !d.source.contains("-/") && !d.source.contains("/-"),
        s"${d.name}: the source holds a Lean comment delimiter"
      )
      val quote = d.source.linesIterator.map(l => s"  $l").mkString("\n")
      val params = d.params.map { case (n, t) => s" (${esc(n)} : ${t.lean})" }.mkString
      val (text, ty) = term(d.body, d.params.toMap, 2, d.result)
      require(
        join(ty, d.result) == join(d.result, ty) && sameShape(ty, d.result),
        s"${d.name}: the body is ${ty.lean}, the result type ${d.result.lean}"
      )
      s"\n/- Scala:\n$quote\n-/\ndef ${esc(d.name)}$params : ${d.result.lean} :=\n${pad(2)}$text\n"
    }

    private def sameShape(
      a: Ty,
      b: Ty
    ): Boolean = (a, b) match {
      case (TUnknown, _) | (_, TUnknown) => true
      case (TList(x), TList(y))          => sameShape(x, y)
      case (TOpt(x), TOpt(y))            => sameShape(x, y)
      case (TTuple(xs), TTuple(ys)) =>
        xs.size == ys.size && xs.zip(ys).forall((sameShape _).tupled)
      case _ => a == b
    }

    // ---------------------------------------------------------------------- expressions

    def term(
      t: Term,
      env: Env,
      ind: Int,
      expected: Ty = TUnknown
    ): (String, Ty) = t match {
      case Lit.Int(n)     => (s"($n : Int)", TInt)
      case Lit.Boolean(b) => (b.toString, TBool)
      case Lit.String(s)  => (charList(s), TText)
      case Lit.Char(c)    => (leanChar(c), TChar)

      case Term.Name("None") =>
        ("none", expected match { case o: TOpt => o; case _ => TOpt(TUnknown) })
      case Term.Name("Nil") =>
        ("[]", expected match { case l: TList => l; case _ => TList(TUnknown) })
      case Term.Name(n) =>
        env
          .get(n)
          .map(ty => (esc(n), ty))
          .orElse(ctx.consts.get(n).map(ty => (esc(n), ty)))
          .orElse {
            ctx.ctorOwner.get(n).map { owner =>
              require(ctx.ctorFields(n).isEmpty, s"$n needs arguments")
              (s"$owner.$n", TUser(owner))
            }
          }
          .getOrElse(fail("a name", t))
      case Term.Placeholder() =>
        env.get("_").map(ty => ("x_", ty)).getOrElse(fail("a placeholder", t))

      case Term.Select(qual, Term.Name(m)) => method(qual, m, Nil, env, ind, t)

      case a: Term.Apply => apply(a, env, ind, expected)

      case Term.ApplyUnary(op, arg) if op.value == "!" =>
        (s"!${atom(term(arg, env, ind)._1)}", TBool)

      case i: Term.ApplyInfix => infix(i, env, ind)

      case Term.Interpolate(Term.Name("s"), parts, args) =>
        require(parts.size == args.size + 1, "an interpolation shape")
        val pieces = parts.indices.flatMap { i =>
          val lit = parts(i) match {
            case Lit.String(s) => if (s.isEmpty) Nil else List(charList(s))
            case other         => fail("an interpolation part", other)
          }
          val arg =
            if (i < args.size) {
              val (text, ty) = term(args(i), env, ind)
              if (ty != TText) fail("a non-text value in an interpolation", args(i))
              List(atom(text))
            } else Nil
          lit ++ arg
        }
        (if (pieces.isEmpty) "[]" else pieces.mkString(" ++ "), TText)

      case Term.Tuple(args) =>
        val translated = args.map(term(_, env, ind))
        (translated.map(_._1).mkString("(", ", ", ")"), TTuple(translated.map(_._2)))

      case i: Term.If =>
        val (cond, _) = term(i.cond, env, ind)
        val inner = ind + 2
        val (thenp, tt) = term(i.thenp, env, inner, expected)
        val (elsep, et) = term(i.elsep, env, inner, expected)
        (
          s"if $cond then\n${pad(inner)}$thenp\n${pad(ind)}else\n${pad(inner)}$elsep",
          join(tt, et)
        )

      case m: Term.Match =>
        val (scrutinee, st) = term(m.expr, env, ind)
        val arms = m.cases.map { c =>
          if (c.cond.nonEmpty) fail("a guard", c)
          val (pat, bound) = pattern(c.pat, st)
          val inner = ind + 4
          val (rhs, ty) = term(c.body, env ++ bound, inner, expected)
          (s"${pad(ind)}| $pat =>\n${pad(inner)}${paren(rhs)}", ty)
        }
        (
          s"match $scrutinee with\n${arms.map(_._1).mkString("\n")}",
          arms.map(_._2).foldLeft[Ty](TUnknown)(join)
        )

      case b: Term.Block =>
        require(b.stats.nonEmpty, "an empty block")
        var scope = env
        val lets = b.stats.init.map {
          case v: Defn.Val =>
            val name = v.pats match {
              case List(Pat.Var(n)) => n.value
              case _                => fail("a val pattern", v)
            }
            val declared = v.decltpe.map(scalaTypeOf)
            val (rhs, ty0) = term(v.rhs, scope, ind + 2, declared.getOrElse(TUnknown))
            val ty = declared.getOrElse(ty0)
            scope = scope + (name -> ty)
            s"let ${esc(name)} : ${ty.lean} :=${rhsAfterAssign(rhs, ind)}"
          case other => fail("a block statement", other)
        }
        val (last, lt) = b.stats.last match {
          case term0: Term => term(term0, scope, ind, expected)
          case other       => fail("a block that does not end in an expression", other)
        }
        ((lets :+ last).mkString(s"\n${pad(ind)}"), lt)

      case tr: Term.Try =>
        require(tr.finallyp.isEmpty, "a finally")
        tr.catchp.foreach { c =>
          val rethrows = c.body match {
            case _: Term.Throw => true
            case Term.Block(stats) if stats.lastOption.exists(_.isInstanceOf[Term.Throw]) =>
              true
            case _ => false
          }
          if (!rethrows) fail("a catch case that does not rethrow", c)
        }
        // the exception paths are outside the model
        term(tr.expr, env, ind, expected)

      case other => fail("expression", other)
    }

    private def scalaTypeOf(t: Type): Ty = t match {
      case Type.Name("Int")     => TInt
      case Type.Name("Boolean") => TBool
      case Type.Name("Char")    => TChar
      case Type.Name("String")  => TText
      case other                => fail("a val type", other)
    }

    // a multi-line right-hand side goes on its own lines, bracketed
    private def rhsAfterAssign(
      rhs: String,
      ind: Int
    ): String =
      if (rhs.contains('\n')) s"\n${pad(ind + 2)}($rhs)"
      else s" $rhs"

    private def paren(s: String): String = if (s.contains('\n')) s"($s)" else s

    private def operand(
      t: Term,
      env: Env,
      ind: Int,
      expected: Ty = TUnknown
    ): (String, Ty) = {
      val (s, ty) = term(t, env, ind, expected)
      (atom(s), ty)
    }

    // ------------------------------------------------------------------------- operators

    private def infix(
      i: Term.ApplyInfix,
      env: Env,
      ind: Int
    ): (String, Ty) = {
      val rhs = i.argClause.values match {
        case List(single) => single
        case _            => fail("an infix operator without one argument", i)
      }
      val (l, lt) = operand(i.lhs, env, ind)
      val (r, rt) = operand(rhs, env, ind, lt)
      val op = i.op.value
      (op, lt) match {
        case ("+", TText) =>
          require(rt == TText, s"text + ${rt.lean}")
          (s"$l ++ $r", TText)
        case ("+", TInt) | ("-", TInt) | ("*", TInt) =>
          require(rt == TInt, s"Int $op ${rt.lean}")
          (s"$l $op $r", TInt)
        case ("<" | "<=" | ">" | ">=", TInt | TChar) =>
          require(sameShape(lt, rt), s"$op on ${lt.lean} and ${rt.lean}")
          (s"$l $op $r", TBool)
        case ("==", _) =>
          require(sameShape(lt, rt), s"== on ${lt.lean} and ${rt.lean}")
          (s"$l == $r", TBool)
        case ("!=", _) =>
          require(sameShape(lt, rt), s"!= on ${lt.lean} and ${rt.lean}")
          (s"$l != $r", TBool)
        case ("&&" | "||", TBool) =>
          require(rt == TBool, s"$op on ${rt.lean}")
          (s"$l $op $r", TBool)
        case ("::", _) =>
          (s"$l :: $r", TList(join(lt, rt match { case TList(e) => e; case _ => TUnknown })))
        case ("++", TList(_)) => (s"$l ++ $r", join(lt, rt))
        case _                => fail(s"operator $op on ${lt.lean}", i)
      }
    }

    // ----------------------------------------------------------------------------- calls

    private def apply(
      a: Term.Apply,
      env: Env,
      ind: Int,
      expected: Ty
    ): (String, Ty) = {
      val args = a.argClause.values
      a.fun match {
        case Term.Select(Term.Name(q), Term.Name(m)) if isStatic(q) =>
          static(q, m, args, env, ind, a)
        case Term.Select(qual, Term.Name(m)) => method(qual, m, args, env, ind, a)
        case Term.Name("Some") =>
          require(args.size == 1)
          val inner = expected match { case TOpt(e) => e; case _ => TUnknown }
          val (x, t) = operand(args.head, env, ind, inner)
          (s"some $x", TOpt(t))
        case Term.Name("List" | "Seq" | "Array") =>
          val inner = expected match { case TList(e) => e; case _ => TUnknown }
          val translated = args.map(operand(_, env, ind, inner))
          val elem = translated.map(_._2).foldLeft[Ty](inner)(join)
          (translated.map(_._1).mkString("[", ", ", "]"), TList(elem))
        case Term.Name(f) if ctx.ctorOwner.contains(f) =>
          val fields = ctx.ctorFields(f)
          require(args.size == fields.size, s"constructor $f: ${args.size} arguments")
          val translated = args.zip(fields).map { case (x, ft) => operand(x, env, ind, ft)._1 }
          (
            s"${ctx.ctorOwner(f)}.$f${translated.map(" " + _).mkString}",
            TUser(ctx.ctorOwner(f))
          )
        case Term.Name(f) if ctx.funs.contains(f) =>
          val (ps, result) = ctx.funs(f)
          require(args.size == ps.size, s"call of $f: ${args.size} arguments")
          val translated = args.zip(ps).map { case (x, pt) => operand(x, env, ind, pt)._1 }
          (s"${esc(f)}${translated.map(" " + _).mkString}", result)
        case other => fail("a call", other)
      }
    }

    private def isStatic(q: String): Boolean = Set("URLEncoder", "Pattern", "Json")(q)

    // library functions called on an object
    private def static(
      q: String,
      m: String,
      args: List[Term],
      env: Env,
      ind: Int,
      whole: Term
    ): (String, Ty) = (q, m, args) match {
      case ("URLEncoder", "encode", List(value, Lit.String("UTF-8"))) =>
        val (v, vt) = operand(value, env, ind)
        require(vt == TText, "URLEncoder.encode of a non-text")
        (s"WireFormat.urlEncoderEncode $v", TText)
      case ("Pattern", "compile", List(Lit.String("\r\n|\r|\n"))) =>
        ("Scala.Pattern.lineBreak", PatternType)
      case ("Json", "parse", List(text)) =>
        val (v, vt) = operand(text, env, ind)
        require(vt == TText, "Json.parse of a non-text")
        (s"Scala.JsValue.parsed $v", JsValueType)
      case _ => fail("a library call", whole)
    }

    // a function argument: a lambda, a placeholder lambda or the name of a def
    private def function(
      t: Term,
      params: List[Ty],
      env: Env,
      ind: Int
    ): (String, Ty) = t match {
      case Term.Name(f) if ctx.funs.contains(f) =>
        val (ps, result) = ctx.funs(f)
        require(
          ps == params || ps.zip(params).forall((sameShape _).tupled),
          s"$f used with other parameters"
        )
        (esc(f), result)
      case Term.AnonymousFunction(body) =>
        require(params.size == 1, "a placeholder function of several parameters")
        val (b, ty) = term(body, env + ("_" -> params.head), ind)
        (s"(fun x_ => $b)", ty)
      case Term.Function(clause, body) =>
        val names = clause.values.map(_.name.value)
        require(names.size == params.size, "a lambda of another arity")
        val (b, ty) = term(body, env ++ names.zip(params), ind)
        (s"(fun ${names.map(esc).mkString(" ")} => $b)", ty)
      case Term.PartialFunction(cases) =>
        require(params.size == 1, "a partial function of several parameters")
        val arms = cases.map { c =>
          if (c.cond.nonEmpty) fail("a guard", c)
          val (pat, bound) = pattern(c.pat, params.head)
          val (rhs, ty) = term(c.body, env ++ bound, ind + 4)
          (s"| $pat => some ${atom(rhs)}", ty)
        }
        val ty = arms.map(_._2).foldLeft[Ty](TUnknown)(join)
        (
          s"(fun x_ => match x_ with ${arms.map(_._1).mkString(" ")} | _ => none)",
          TOpt(ty)
        )
      case other => fail("a function argument", other)
    }

    private def method(
      recv: Term,
      m: String,
      args: List[Term],
      env: Env,
      ind: Int,
      whole: Term
    ): (String, Ty) = {
      val (r, rt) = operand(recv, env, ind)
      def one(expected: Ty = TUnknown): (String, Ty) = {
        require(args.size == 1, s"$m: ${args.size} arguments")
        operand(args.head, env, ind, expected)
      }
      def predicate(elem: Ty): String = {
        require(args.size == 1, s"$m: ${args.size} arguments")
        val (f, ty) = function(args.head, List(elem), env, ind)
        require(ty == TBool, s"$m: a predicate returning ${ty.lean}")
        f
      }
      (rt, m) match {
        // ---- text (java.lang.String, Scala StringOps)
        case (TText, "isEmpty")  => (s"$r.isEmpty", TBool)
        case (TText, "nonEmpty") => (s"!$r.isEmpty", TBool)
        case (TText, "length")   => (s"($r.length : Int)", TInt)
        case (TText, "trim")     => (s"Scala.trim $r", TText)
        case (TText, "toString") => (r, TText)
        case (TText, "startsWith") =>
          (s"Scala.startsWith $r ${one(TText)._1}", TBool)
        case (TText, "contains") =>
          val (a, at) = one()
          require(at == TText, "String.contains of a non-text")
          (s"Scala.containsText $r $a", TBool)
        case (TText, "indexOf") =>
          val (a, at) = one()
          at match {
            case TChar => (s"Scala.indexOfChar $r $a", TInt)
            case TText => (s"Scala.indexOfText $r $a", TInt)
            case _     => fail("indexOf", whole)
          }
        case (TText, "replace") =>
          args match {
            case List(Lit.String(target), Lit.String(replacement)) =>
              // Java inserts the replacement between every character for an empty target
              require(target.nonEmpty, "String.replace of an empty target")
              (
                s"Java.replace ${charList(target)} ${charList(replacement)} $r",
                TText
              )
            case _ => fail("String.replace with other than literals", whole)
          }
        case (TText, "stripPrefix") => (s"Scala.stripPrefix $r ${one(TText)._1}", TText)
        case (TText, "stripSuffix") => (s"Scala.stripSuffix $r ${one(TText)._1}", TText)
        case (TText, "substring") =>
          val (a, at) = one(TInt)
          require(at == TInt, "substring of a non-Int")
          (s"Scala.substring $r $a", TText)
        case (TText, "take") =>
          val (a, _) = one(TInt)
          (s"($r.take ($a).toNat)", TText)
        case (TText, "equals") => (s"($r == ${one(TText)._1})", TBool)
        case (TText, "forall") => (s"$r.all ${predicate(TChar)}", TBool)
        case (TText, "exists") => (s"$r.any ${predicate(TChar)}", TBool)

        // ---- patterns
        case (PatternType, "split") =>
          args match {
            case List(text, Lit.Int(-1)) =>
              val (a, at) = operand(text, env, ind)
              require(at == TText, "Pattern.split of a non-text")
              (s"Scala.Pattern.split $r $a", TList(TText))
            case _ => fail("Pattern.split other than (text, -1)", whole)
          }

        // ---- lists
        case (TList(_), "isEmpty")  => (s"$r.isEmpty", TBool)
        case (TList(_), "nonEmpty") => (s"!$r.isEmpty", TBool)
        case (TList(e), "forall")   => (s"$r.all ${predicate(e)}", TBool)
        case (TList(e), "exists")   => (s"$r.any ${predicate(e)}", TBool)
        case (TList(e), "filter")   => (s"$r.filter ${predicate(e)}", rt)
        case (TList(e), "filterNot") =>
          (s"$r.filter (fun y_ => !${atom(predicate(e))} y_)", rt)
        case (TList(e), "dropWhile") => (s"$r.dropWhile ${predicate(e)}", rt)
        case (TList(TText), "mkString") =>
          val (a, at) = one(TText)
          require(at == TText, "mkString of a non-text separator")
          (s"Scala.mkString $a $r", TText)
        case (TList(e), "collect") =>
          require(args.size == 1, "collect: one argument")
          val (f, ty) = function(args.head, List(e), env, ind)
          ty match {
            case TOpt(result) => (s"$r.filterMap $f", TList(result))
            case _            => fail("collect", whole)
          }

        // ---- options
        case (TOpt(e), "getOrElse") =>
          val (a, _) = one(e)
          (s"$r.getD $a", e)

        case _ => fail(s"a method $m on ${rt.lean}", whole)
      }
    }

    // -------------------------------------------------------------------------- patterns

    private def pattern(
      p: Pat,
      ty: Ty
    ): (String, Env) = p match {
      case Pat.Wildcard()    => ("_", Map.empty)
      case Pat.Var(n)        => (esc(n.value), Map(n.value -> ty))
      case Lit.Int(n)        => (s"($n : Int)", Map.empty)
      case Lit.Boolean(b)    => (b.toString, Map.empty)
      case Term.Name("None") => ("none", Map.empty)
      case Term.Name("Nil")  => ("[]", Map.empty)
      case Term.Name(n) if ctx.ctorOwner.get(n).exists(_ => ctx.ctorFields(n).isEmpty) =>
        (s"${ctx.ctorOwner(n)}.$n", Map.empty)
      case Pat.Tuple(args) =>
        val elems = ty match {
          case TTuple(es) if es.size == args.size => es
          case _                                  => fail("a tuple pattern on another type", p)
        }
        val parts = args.zip(elems).map { case (a, t) => pattern(a, t) }
        (parts.map(_._1).mkString("(", ", ", ")"), parts.flatMap(_._2).toMap)
      case e: Pat.Extract =>
        val args = e.argClause.values
        e.fun match {
          case Term.Name("Some") =>
            val inner = ty match {
              case TOpt(x) => x; case _ => fail("Some on a non-option", p)
            }
            val parts = args.map(pattern(_, inner))
            (s"some ${parts.map(x => atomPat(x._1)).mkString(" ")}", parts.flatMap(_._2).toMap)
          case Term.Name(f) if ctx.ctorOwner.contains(f) =>
            val fields = ctx.ctorFields(f)
            require(args.size == fields.size, s"pattern $f: ${args.size} arguments")
            val parts = args.zip(fields).map { case (a, t) => pattern(a, t) }
            (
              s"${ctx.ctorOwner(f)}.$f${parts.map(x => " " + atomPat(x._1)).mkString}",
              parts.flatMap(_._2).toMap
            )
          case other => fail("a pattern", other)
        }
      case i: Pat.ExtractInfix if i.op.value == "::" =>
        val elem = ty match {
          case TList(x) => x; case _ => fail("a cons pattern on a non-list", p)
        }
        i.rhs.values match {
          case List(tail) =>
            val (h, hb) = pattern(i.lhs, elem)
            val (t, tb) = pattern(tail, ty)
            (s"${atomPat(h)} :: ${atomPat(t)}", hb ++ tb)
          case _ => fail("a cons pattern", p)
        }
      case other => fail("a pattern", other)
    }

    private def atomPat(s: String): String =
      if (s.contains(' ') && !s.startsWith("(") && !s.startsWith("[")) s"($s)" else s
  }
}
