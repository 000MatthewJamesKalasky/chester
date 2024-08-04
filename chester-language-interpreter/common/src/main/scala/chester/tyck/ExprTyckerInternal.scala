package chester.tyck

import chester.error.SourcePos
import chester.syntax.concrete._
import chester.syntax.core._

case class TyckState()

object BuiltinCtx {
  val builtinSyntax = Vector("data", "module")
}

case class LocalCtx()

object LocalCtx {
  val Empty = LocalCtx()
}

case class Judge(wellTyped: Term, ty: Term, effect: EffectTerm)

sealed trait TyckError {
  def message: String
  def cause: Option[Term | Expr]
  def location: Option[SourcePos] = cause match {
    case Some(term: Term) => term.sourcePos
    case Some(expr: Expr) => expr.sourcePos
    case _ => None
  }
}

case class EmptyResultsError() extends TyckError {
  def message: String = "Empty Results"
  def cause: Option[Term | Expr] = None
}

case class UnifyFailedError(subType: Term, superType: Term) extends TyckError {
  def message: String = s"Unification failed: $subType is not a subtype of $superType"
  def cause: Option[Term | Expr] = Some(subType)
}

case class UnsupportedExpressionError(expr: Expr) extends TyckError {
  def message: String = s"Unsupported expression type: $expr"
  def cause: Option[Term | Expr] = Some(expr)
}

case class FieldTypeNotFoundError(qualifiedName: QualifiedName) extends TyckError {
  def message: String = s"Field type not found for $qualifiedName"
  def cause: Option[Term | Expr] = Some(qualifiedName)
}

case class ExpectedObjectTypeError() extends TyckError {
  def message: String = "Expected an ObjectType for inheritance"
  def cause: Option[Term | Expr] = None
}

case class Getting[T](xs: TyckState => LazyList[Either[Vector[TyckError], (TyckState, T)]]) {

  private def nonEmptyErrors(errors: Vector[TyckError]): Vector[TyckError] = {
    require(errors.nonEmpty, "Errors vector cannot be empty")
    errors
  }

  def map[U](f: T => U): Getting[U] = Getting { state =>
    xs(state).map {
      case Left(err) => Left(nonEmptyErrors(err))
      case Right((nextState, value)) => Right((nextState, f(value)))
    }
  }

  def flatMap[U](f: T => Getting[U]): Getting[U] = Getting { state =>
    xs(state).flatMap {
      case Left(err) => LazyList(Left(nonEmptyErrors(err)))
      case Right((nextState, value)) => f(value).xs(nextState)
    }
  }

  def getOne(state: TyckState): Either[Vector[TyckError], (TyckState, T)] = {
    xs(state).collectFirst {
      case right@Right(_) => right
    }.getOrElse(xs(state).headOption.getOrElse(Left(nonEmptyErrors(Vector(EmptyResultsError())))))
  }

  def explainError(explain: TyckError => TyckError): Getting[T] = Getting { state =>
    xs(state).map {
      case Left(err) => Left(nonEmptyErrors(err.map(explain)))
      case right => right
    }
  }

  def ||(other: => Getting[T]): Getting[T] = Getting { state =>
    xs(state) #::: other.xs(state)
  }
}

object Getting {
  def pure[T](x: T): Getting[T] = Getting(state => LazyList(Right((state, x))))

  def error[T](err: TyckError): Getting[T] = Getting(_ => LazyList(Left(Vector(err))))

  def errors[T](errs: Vector[TyckError]): Getting[T] = Getting(_ => LazyList(Left(errs)))

  def read: Getting[TyckState] = Getting(state => LazyList(Right((state, state))))

  def write(newState: TyckState): Getting[Unit] = Getting(_ => LazyList(Right((newState, ()))))
}

case class ExprTyckerInternal(localCtx: LocalCtx = LocalCtx.Empty) {
  def unify(subType: Term, superType: Term): Getting[Term] = {
    if (subType == superType) return Getting.pure(subType)
    (subType, superType) match {
      case (_, AnyTerm(_)) => Getting.pure(subType) // AnyTerm matches any subtype
      case _ => Getting.error(UnifyFailedError(subType, superType))
    }
  }

  def unifyEffect(subEffect: EffectTerm, superEffect: EffectTerm): Getting[EffectTerm] = {
    (subEffect, superEffect) match {
      case (_, NoEffect(_)) => Getting.pure(subEffect)
      case (NoEffect(_), _) => Getting.pure(superEffect)
      case _ if subEffect == superEffect => Getting.pure(subEffect)
      case _ => Getting.error(UnifyFailedError(subEffect, superEffect))
    }
  }

  def desugarQualifiedName(qname: QualifiedName): Vector[String] = qname match {
    case Identifier(name, _) => Vector(name)
    case DotCall(expr, field: Identifier, _, _) => desugarQualifiedName(expr.asInstanceOf[QualifiedName]) :+ field.name
    case _ => throw new IllegalArgumentException("Invalid QualifiedName structure")
  }

  def desugarObjectExpr(expr: ObjectExpr): ObjectExpr = {
    def insertNested(fields: Vector[(Vector[String], Expr)], base: ObjectExpr): ObjectExpr = {
      fields.foldLeft(base) {
        case (acc, (Vector(k), v)) =>
          val updatedClauses = acc.clauses :+ (Identifier(k) -> v)
          acc.copy(clauses = updatedClauses)
        case (acc, (Vector(k, ks*), v)) =>
          val nestedExpr = acc.clauses.find(_._1 == Identifier(k)) match {
            case Some((_, nestedObj: ObjectExpr)) =>
              insertNested(Vector((ks.toVector, v)), nestedObj)
            case _ =>
              insertNested(Vector((ks.toVector, v)), ObjectExpr(Vector.empty))
          }
          val updatedClauses = acc.clauses.filterNot(_._1 == Identifier(k)) :+ (Identifier(k) -> nestedExpr)
          acc.copy(clauses = updatedClauses)
        case (acc, (Vector(), _)) => acc
      }
    }

    val desugaredClauses = expr.clauses.map {
      case (qname, expr) => (desugarQualifiedName(qname), expr)
    }
    insertNested(desugaredClauses, ObjectExpr(Vector.empty))
  }

  def synthesizeObjectExpr(clauses: Vector[(QualifiedName, Expr)]): Getting[ObjectTerm] = {
    for {
      typedClauses <- clauses.foldLeft(Getting.pure(Vector.empty[(String, Term)])) { (acc, clause) =>
        for {
          typedClauses <- acc
          (qualifiedName, expr) = clause
          Judge(wellTypedExpr, _, _) <- synthesize(expr)
        } yield typedClauses :+ (desugarQualifiedName(qualifiedName).mkString("."), wellTypedExpr)
      }
    } yield ObjectTerm(typedClauses)
  }

  def synthesizeObjectType(clauses: Vector[(QualifiedName, Expr)]): Getting[ObjectType] = {
    for {
      typedClauses <- clauses.foldLeft(Getting.pure(Vector.empty[(String, Term)])) { (acc, clause) =>
        for {
          typedClauses <- acc
          (qualifiedName, expr) = clause
          Judge(_, exprType, _) <- synthesize(expr)
        } yield typedClauses :+ (desugarQualifiedName(qualifiedName).mkString("."), exprType)
      }
    } yield ObjectType(typedClauses)
  }

  def synthesize(expr: Expr): Getting[Judge] = expr match {
    case IntegerLiteral(value, meta) =>
      val termMeta = convertMeta(meta)
      Getting.pure(Judge(IntegerTerm(value, termMeta), IntegerType(termMeta), NoEffect(termMeta)))
    case DoubleLiteral(value, meta) =>
      val termMeta = convertMeta(meta)
      Getting.pure(Judge(DoubleTerm(value, termMeta), DoubleType(termMeta), NoEffect(termMeta)))
    case StringLiteral(value, meta) =>
      val termMeta = convertMeta(meta)
      Getting.pure(Judge(StringTerm(value, termMeta), StringType(termMeta), NoEffect(termMeta)))
    case objExpr: ObjectExpr =>
      val desugaredExpr = desugarObjectExpr(objExpr)
      for {
        objTerm <- synthesizeObjectExpr(desugaredExpr.clauses)
        objType <- synthesizeObjectType(desugaredExpr.clauses)
      } yield Judge(objTerm, objType, NoEffect(convertMeta(objExpr.meta)))
    case _ => Getting.error(UnsupportedExpressionError(expr))
  }

  def inheritObjectFields(clauses: Vector[(QualifiedName, Expr)], fieldTypes: Vector[(String, Term)], effect: Option[EffectTerm]): Getting[Vector[(String, Term)]] = {
    clauses.foldLeft(Getting.pure(Vector.empty[(String, Term)])) { (acc, clause) =>
      for {
        typedClauses <- acc
        (qualifiedName, expr) = clause
        fieldType <- fieldTypes.find(_._1 == desugarQualifiedName(qualifiedName).head).map(_._2) match {
          case Some(ft) => inherit(expr, ft, effect).map(_.wellTyped)
          case None => Getting.error[Term](FieldTypeNotFoundError(qualifiedName))
        }
      } yield typedClauses :+ (desugarQualifiedName(qualifiedName).head, fieldType)
    }
  }

  def inherit0(expr: Expr, ty: Term, effect: Option[EffectTerm]): Getting[Judge] = expr match {
    case ObjectExpr(clauses, meta) =>
      ty match {
        case ObjectType(fieldTypes, _) =>
          for {
            inheritedFields <- inheritObjectFields(clauses, fieldTypes, effect)
            effect <- effect match {
              case Some(eff) => Getting.pure(eff)
              case None => Getting.pure(NoEffect(convertMeta(meta)))
            }
          } yield Judge(ObjectTerm(inheritedFields, convertMeta(meta)), ty, effect)
        case _ => Getting.error(ExpectedObjectTypeError())
      }
    case default => Getting.error(UnsupportedExpressionError(default))
  }

  def inherit(expr: Expr, ty: Term, effect: Option[EffectTerm] = None): Getting[Judge] = effect match {
    case Some(eff) => inherit0(expr, ty, Some(eff)) || (for {
      Judge(wellTyped, judgeTy, judgeEffect) <- synthesize(expr)
      ty1 <- unify(judgeTy, ty)
      effect1 <- unifyEffect(judgeEffect, eff)
    } yield Judge(wellTyped, ty1, effect1))
    case None => inherit0(expr, ty, None) || (for {
      Judge(wellTyped, judgeTy, judgeEffect) <- synthesize(expr)
      ty1 <- unify(judgeTy, ty)
    } yield Judge(wellTyped, ty1, judgeEffect))
  }
}

object ExprTycker {
  def unify(subType: Term, superType: Term, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Term] = {
    ExprTyckerInternal(ctx).unify(subType, superType).getOne(state).map(_._2)
  }

  def unifyEffect(subEffect: EffectTerm, superEffect: EffectTerm, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], EffectTerm] = {
    ExprTyckerInternal(ctx).unifyEffect(subEffect, superEffect).getOne(state).map(_._2)
  }

  def inherit(expr: Expr, ty: Term, effect: Option[EffectTerm] = None, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Judge] = {
    ExprTyckerInternal(ctx).inherit(expr, ty, effect).getOne(state).map(_._2)
  }

  def synthesize(expr: Expr, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Judge] = {
    ExprTyckerInternal(ctx).synthesize(expr).getOne(state).map(_._2)
  }
}