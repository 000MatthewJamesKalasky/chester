package chester.resolve

import cats.data.*
import cats.implicits.*
import chester.error.*
import chester.syntax.{Const, ModuleRef}
import chester.syntax.concrete.*
import chester.tyck.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.*
import io.github.iltotore.iron.constraint.numeric.*
import io.github.iltotore.iron.upickle.given
import chester.utils.*

import scala.annotation.tailrec

case class DesugarInfo()

private object DesaltCaseClauseMatch {
  def unapply(x: Expr)(using reporter: Reporter[TyckProblem]): Option[DesaltCaseClause] = x match {
    case OpSeq(Vector(Identifier(Const.Case, _), pattern, Identifier(Const.Arrow2, _), returning), meta) =>
      Some(DesaltCaseClause(pattern, returning, meta))
    case OpSeq(Vector(Identifier(Const.Case, _), _*), _) =>
      val error = ExpectCase(x)
      reporter(error)
      None
    case _ => None
  }
}

private object MatchDeclarationTelescope {
  def unapply(x: Expr)(using reporter: Reporter[TyckProblem]): Option[DefTelescope] = x match {
    case id: Identifier =>
      // Single parameter without type
      Some(DefTelescope(Vector(Arg(name = id, meta = id.meta))))
    case OpSeq(terms, _) if terms.nonEmpty =>
      // Multiple parameters, possibly with types
      val argsResult = terms.grouped(3).map {
        case Vector(id: Identifier, Identifier(Const.`:`, _), ty) =>
          Some(Arg(name = id, ty = Some(ty), meta = id.meta))
        case Vector(id: Identifier) =>
          Some(Arg(name = id, meta = id.meta))
        case _ =>
          reporter(ExpectParameterList(x))
          None
      }.toVector

      if (argsResult.contains(None)) {
        None
      } else {
        Some(DefTelescope(argsResult.flatten))
      }
    case Tuple(terms, _) =>
      // Parameters enclosed in parentheses
      val argsResult = terms.map {
        case id: Identifier =>
          Some(Arg(name = id, meta = id.meta))
        case OpSeq(Vector(id: Identifier, Identifier(Const.`:`, _), ty), _) =>
          Some(Arg(name = id, ty = Some(ty), meta = id.meta))
        case _ =>
          reporter(ExpectParameterList(x))
          None
      }

      if (argsResult.contains(None)) {
        None
      } else {
        Some(DefTelescope(argsResult.flatten.toVector))
      }
    case _ =>
      reporter(ExpectParameterList(x))
      None
  }
}

def opSeq(xs: Seq[Expr])(using reporter: Reporter[TyckProblem]): Expr = SimpleDesalt.desugar(OpSeq(xs.toVector))

private object DesaltSimpleFunction {
  def predicate(x: Expr): Boolean = x match {
    case Identifier(Const.Arrow2, _) => true
    case _ => false
  }

  def unapply(x: Expr)(using reporter: Reporter[TyckProblem]): Option[Expr] = x match {
    case OpSeq(xs, meta) if xs.exists(predicate) =>
      val index = xs.indexWhere(predicate)
      assert(index >= 0)
      val before = xs.take(index)
      val after = xs.drop(index + 1)

      val paramsExpr = before match {
        case Vector(Tuple(terms, _)) => Vector(Tuple(terms))
        case _ => before
      }

      paramsExpr.traverse(MatchDeclarationTelescope.unapply) match {
        case Some(telescopes) =>
          val telescopeArgs = telescopes.flatMap(_.args)
          val body = opSeq(after)
          Some(FunctionExpr(
            telescope = Vector(DefTelescope(telescopeArgs)),
            body = body,
            meta = meta
          ))
        case None =>
          val error = ExpectLambda(x)
          reporter(error)
          Some(DesaltFailed(x, error, meta))
      }
    case _ => None
  }
}

private object ObjectDesalt {
  def desugarQualifiedName(qname: QualifiedName): Vector[String] = qname match {
    case Identifier(name, _) => Vector(name)
    case DotCall(expr: QualifiedName, field: Identifier, _, _) =>
      desugarQualifiedName(expr) :+ field.name
    case _ => throw new IllegalArgumentException("Invalid QualifiedName structure")
  }

  def insertNested(fields: Vector[(Vector[String], Expr)], base: ObjectExpr): ObjectExpr = {
    fields.foldLeft(base) {
      case (acc, (Vector(k), v)) =>
        acc.copy(clauses = acc.clauses :+ ObjectExprClause(Identifier(k), v))
      case (acc, (k +: ks, v)) =>
        val nestedObj = acc.clauses.collectFirst {
          case ObjectExprClause(id: Identifier, obj: ObjectExpr) if id.name == k => obj
        }.getOrElse(ObjectExpr(Vector.empty))
        val updatedNested = insertNested(Vector((ks, v)), nestedObj)
        val updatedClauses = acc.clauses.filterNot {
          case ObjectExprClause(id: Identifier, _) => id.name == k
          case _ => false
        } :+ ObjectExprClause(Identifier(k), updatedNested)
        acc.copy(clauses = updatedClauses)
      case (acc, _) => acc
    }
  }

  def desugarObjectExpr(expr: ObjectExpr): ObjectExpr = {
    val (desugaredFields, otherClauses) = expr.clauses.foldLeft((Vector.empty[(Vector[String], Expr)], Vector.empty[ObjectClause])) {
      case ((fields, others), ObjectExprClause(qname, value)) =>
        (fields :+ (desugarQualifiedName(qname), value), others)
      case ((fields, others), clause) =>
        (fields, others :+ clause)
    }

    val nestedObject = insertNested(desugaredFields, ObjectExpr(Vector.empty))

    val updatedClauses = (nestedObject.clauses ++ otherClauses).map {
      case ObjectExprClause(key: Identifier, value) =>
        ObjectExprClauseOnValue(SymbolLiteral(key.name, key.meta), value)
      case other: ObjectExprClauseOnValue => other
    }

    expr.copy(clauses = updatedClauses)
  }
}

case object PatternDesalt {
  def desugar(x: Expr)(using reporter: Reporter[TyckProblem]): Option[DesaltPattern] = x match {
    case id@Identifier(_, meta) => Some(PatternBind(id, meta))
    case _ => None // TODO: more
  }
}

case object StmtDesalt {
  def desugar(x: Expr)(using reporter: Reporter[TyckProblem]): Expr = x match {
    case StmtDesalt(x) => x
    case _ => x
  }

  def defined(xs: Vector[Expr])(using reporter: Reporter[TyckProblem]): Option[Defined] = {
    if (xs.isEmpty) None
    else if (xs.length == 1) xs.head match {
      // TODO: support multiple telescopes
      case FunctionCall(f: Identifier, MatchDeclarationTelescope(t), meta) => Some(DefinedFunction(f, Vector(t).assumeNonEmpty))
      case a => PatternDesalt.desugar(a).map(DefinedPattern)
    }
    else xs.head match {
      case identifier: Identifier =>
        xs.tail.traverse(MatchDeclarationTelescope.unapply).map { telescopes =>
          DefinedFunction(identifier, NonEmptyVector.fromVectorUnsafe(telescopes))
        }
      case _ => None
    }
  }

  def letdef(decorations: Vector[Expr], kw: Identifier, xs: Vector[Expr], cause: Expr)(using reporter: Reporter[TyckProblem]): Stmt = {
    val typeIdx = xs.indexWhere { case Identifier(Const.`:`, _) => true; case _ => false }
    val valueIdx = xs.indexWhere { case Identifier(Const.`=`, _) => true; case _ => false }
    val kind = kw.name match {
      case Const.Let => LetDefType.Let
      case Const.Def => LetDefType.Def
      case name => unreachable(s"Unknown keyword ${name}")
    }

    val (onExprs, typeExprs, valueExprs) = (typeIdx, valueIdx) match {
      case (-1, -1) => (xs, Vector.empty[Expr], Vector.empty[Expr])
      case (tIdx, -1) => (xs.take(tIdx), xs.drop(tIdx + 1), Vector.empty[Expr])
      case (-1, vIdx) => (xs.take(vIdx), Vector.empty[Expr], xs.drop(vIdx + 1))
      case (tIdx, vIdx) if tIdx < vIdx =>
        (xs.take(tIdx), xs.slice(tIdx + 1, vIdx), xs.drop(vIdx + 1))
      case _ =>
        val error = ExpectLetDef(cause)
        reporter(error)
        return DesaltFailed(cause, error, cause.meta)
    }

    val on = defined(onExprs).getOrElse {
      val error = ExpectLetDef(cause)
      reporter(error)
      return DesaltFailed(cause, error, cause.meta)
    }

    val ty = if (typeExprs.nonEmpty) Some(opSeq(typeExprs)) else None
    val body = if (valueExprs.nonEmpty) Some(opSeq(valueExprs)) else None
    unrollFunction(LetDefStmt(kind, on, ty=ty, body=body, decorations=decorations, meta=cause.meta))
  }
  
  def unrollFunction(stmt: LetDefStmt): LetDefStmt = {
    stmt.defined match {
      case DefinedFunction(id, telescopes) =>
        require(stmt.decorations.isEmpty, "not supported yet")
        require(stmt.body.nonEmpty, "not supported yet")
        val expr = FunctionExpr(telescope = telescopes.toVector, resultTy = stmt.ty, effect = stmt.effect,  body = stmt.body.get, meta = stmt.meta)
        stmt.copy(defined=DefinedPattern(PatternBind(id, id.meta)), body=Some(expr), ty = None, effect = None)
      case other => stmt
    }
  }

  def unapply(x: Expr)(using reporter: Reporter[TyckProblem]): Option[Stmt] = x match {
    case opseq @ OpSeq(seq, meta) =>
      seq.indexWhere {
        case Identifier(id, _) if Const.kw1.contains(id) => true
        case _ => false
      } match {
        case -1 => None
        case kwIdx =>
          val kwId = seq(kwIdx).asInstanceOf[Identifier]
          val beforeKw = seq.take(kwIdx)
          val afterKw = seq.drop(kwIdx + 1)
          if (!beforeKw.forall(_.isInstanceOf[Identifier])) None
          else kwId.name match {
            case Const.Let | Const.Def => Some(letdef(beforeKw, kwId, afterKw, opseq))
            case other => unreachable(s"Unknown keyword ${other}")
          }
      }
    case _ => None
  }
}

case object SimpleDesalt {
  def desugar(expr: Expr)(using reporter: Reporter[TyckProblem]): Expr = expr match {
    case OpSeq(xs, _) if xs.length == 1 => xs.head
    case clause @ DesaltCaseClauseMatch(x) => x
    case block @ Block(heads, tail, _) if heads.exists(_.isInstanceOf[DesaltCaseClause]) ||
      tail.exists(_.isInstanceOf[DesaltCaseClause]) =>
      val clauses = (heads ++ tail.toVector).collect { case clause: DesaltCaseClause => clause }
      if (clauses.length != heads.length + tail.size) {
        val error = ExpectFullCaseBlock(block)
        reporter(error)
        DesaltFailed(block, error, block.meta)
      } else {
        DesaltMatching(clauses, block.meta)
      }
    case block @ Block(heads, tail, meta) =>
      Block(heads.map(StmtDesalt.desugar), tail.map(StmtDesalt.desugar), meta)
    case DesaltSimpleFunction(func) => func
    case obj: ObjectExpr => ObjectDesalt.desugarObjectExpr(obj)
    case FunctionCall(function, telescopes, meta) =>
      val desugaredFunction = desugar(function)
      val desugaredTelescopes = telescopes match {
        case t: Tuple => Vector(DesaltCallingTelescope(t.terms.map(term => CallingArg(expr = desugar(term))), meta = t.meta))
        case other =>
          reporter(UnexpectedTelescope(other))
          Vector(DesaltCallingTelescope(Vector(CallingArg(expr = desugar(other))), meta = other.meta))
      }
      desugaredFunction match {
        case DesaltFunctionCall(f, t, m) => DesaltFunctionCall(f, t ++ desugaredTelescopes, m)
        case _ => DesaltFunctionCall(desugaredFunction, NonEmptyVector.fromVectorUnsafe(desugaredTelescopes), meta)
      }
    case OpSeq(Vector(lhs, Identifier(Const.`:`, _), rhs), meta) =>
      val desugaredLhs = desugar(lhs)
      val desugaredRhs = desugar(rhs)
      TypeAnotationNoEffects(desugaredLhs, desugaredRhs, meta)
    case opSeq @ OpSeq(Vector(Identifier(Const.Import, _), some), meta) =>
      Some(some) match {
        case Some(qualifiedName) =>
          val modulePath = ModuleRef(ObjectDesalt.desugarQualifiedName(qualifiedName.asInstanceOf[QualifiedName]))
          ImportStmt(modulePath, meta)
        case None =>
          val error = InvalidImportSyntax(opSeq)
          reporter(error)
          DesaltFailed(opSeq, error, meta)
      }
    case opseq @ OpSeq(Vector(Identifier(Const.Module, _), some), meta) =>
      Some(some) match {
        case Some(qualifiedName) =>
          val modulePath = ModuleRef(ObjectDesalt.desugarQualifiedName(qualifiedName.asInstanceOf[QualifiedName]))
          ModuleStmt(modulePath, meta)
        case None =>
          val error = InvalidModuleSyntax(opseq)
          reporter(error)
          DesaltFailed(opseq, error, meta)
      }
    case default => default
  }

  @tailrec
  private def unwrap(e: Expr)(using reporter: Reporter[TyckProblem]): Expr = e match {
    case Block(Vector(), Some(tail), _) => unwrap(desugar(tail))
    case Tuple(Vector(term), _)         => unwrap(desugar(term))
    case _                              => e
  }

  def desugarUnwrap(expr: Expr)(using reporter: Reporter[TyckProblem]): Expr = unwrap(desugar(expr))
}
case object OpSeqDesalt {
  def desugar(expr: Expr): Expr = ???
}
