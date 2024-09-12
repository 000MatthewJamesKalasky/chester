package chester.tyck

import chester.error.*
import chester.resolve.ExprResolver
import chester.syntax.*
import chester.syntax.concrete.*
import chester.syntax.core.*
import chester.utils.reuse
import spire.math.Trilean
import spire.math.Trilean.{True, Unknown}

import scala.annotation.tailrec
import scala.language.implicitConversions

sealed trait Constraint

object Constraint {
  case class Is(judge: Judge) extends Constraint

  case class TyRange(lower: Option[Judge], upper: Option[Judge]) extends Constraint {
    require(lower.isDefined || upper.isDefined)
  }
}

type Solutions = Map[VarId, Constraint]

object Solutions {
  val Empty: Solutions = Map.empty
}

extension (subst: Solutions) {
  @tailrec
  def walk(term: MetaTerm): Judge = subst.get(term.id) match {
    case Some(Constraint.Is(clause)) => clause.wellTyped match {
      case term: MetaTerm => subst.walk(term)
      case _ => Judge(clause.wellTyped, clause.ty, clause.effect)
    }
    case Some(Constraint.TyRange(lower, upper)) => Judge(term, term.ty, term.effect) // TODO
    case None => Judge(term, term.ty, term.effect)
  }
  def isDefined(term: MetaTerm): Boolean = subst.contains(term.id)
  def read(term: MetaTerm): Option[Constraint] = subst.get(term.id) match {
    case some@Some(Constraint.Is(Judge(meta2: MetaTerm, ty, effect))) => read(meta2).orElse(some)
    case Some(x) => Some(x)
    case None => None
  }
}

case class TyckState(subst: Solutions = Solutions.Empty)

case class LocalCtx(ctx: Context = Context.builtin) {
  def resolve(id: Id): Option[CtxItem] = ctx.get(id)

  def resolve(id: VarId): Option[CtxItem] = ctx.getByVarId(id)

  def extend(name: LocalVar): LocalCtx = copy(ctx = ctx.extend(name))
}

object LocalCtx {
  val Empty = LocalCtx()

  def fromParent(parent: LocalCtx): LocalCtx = parent
}

case class WithCtxEffect[T](ctx: LocalCtx, effect: Effects, value: T)

// https://www.alessandrolacava.com/blog/scala-self-recursive-types/
trait Tycker[Self <: Tycker[Self]] {
  def ev: this.type <:< Self

  protected def thisToSelfTypesafe(x: this.type): Self = ev.apply(x)

  implicit protected inline def thisToSelfImplementation(ignored: this.type): Self = this.asInstanceOf[Self]

  def copy(localCtx: LocalCtx = localCtx, tyck: Tyck = tyck): Self

  inline final def rec(localCtx: LocalCtx): Self = copy(localCtx = localCtx)

  final def tryOne[A](xs: Self => A): TyckResult[TyckState, A] = {
    Tyck.run(tyck => xs(copy(tyck = tyck)))(tyck.getState)
  }

  final def tryAll[A](xs: Seq[Self => A]): A = {
    ???
  }

  def localCtx: LocalCtx

  def tyck: Tyck
}

case class ExprTyckerInternal(localCtx: LocalCtx = LocalCtx.Empty, tyck: Tyck) extends TyckerBase[ExprTyckerInternal] with TelescopeTycker[ExprTyckerInternal] {
  override def ev: this.type <:< ExprTyckerInternal = implicitly[this.type <:< ExprTyckerInternal]
}

trait TyckerBase[Self <: TyckerBase[Self] & TelescopeTycker[Self]] extends Tycker[Self] {
  implicit val reporter1: Reporter[TyckProblem] = tyck.reporter

  def superTypes(ty: Term): Option[Vector[Term]] = {
    ty match {
      case Intersection(ts) => Some(ts)
      case _ => None
    }
  }

  def compareTy(rhs: Term, lhs: Term): Trilean = {
    val subType1 = whnfNoEffect(rhs)
    val superType1 = whnfNoEffect(lhs)
    if (subType1 == superType1) True
    else (subType1, superType1) match {
      case (subType, AnyType(level)) => True // TODO: level
      case _ => Unknown
    }
  }

  def unifyLevel(rhs: Term, lhs: Term): Term = {
    rhs // TODO
  }

  def isDefined(x: MetaTerm): Boolean = tyck.getState.subst.isDefined(x)

  def linkTy(from: MetaTerm, to: Term): Unit = link(from, synthesizeTyTerm(to).toJudge)

  def link(from: MetaTerm, to: Judge): Unit = ???

  /** assume a subtype relationship and get a subtype back */
  def unifyTy(lhs: Term, rhs: Term, failed: => Term = null): Term = {
    val rhs1 = whnfNoEffect(rhs)
    val lhs1 = whnfNoEffect(lhs)
    if (rhs1 == lhs1) rhs1
    else (lhs1, rhs1) match {
      case (lhs, rhs: MetaTerm) if !isDefined(rhs) => {
        linkTy(rhs, lhs)
        lhs
      }
      case (lhs: MetaTerm, rhs: MetaTerm) => {
        if (isDefined(rhs)) {
          if (!isDefined(lhs)) {
            linkTy(lhs, rhs)
            rhs
          } else {
            ???
          }
        } else {
          linkTy(rhs, lhs)
          lhs
        }
      }
      case (AnyType(level), subType) => subType // TODO: level
      case (Union(superTypes), subType) => Union.from(superTypes.map(x => unifyTyOrNothingType(rhs = subType, lhs = x)))
      case (superType, Union(subTypes)) => {
        val results = subTypes.map(rhs => unifyTy(rhs = rhs, lhs = superType))
        Union.from(results)
      }
      case (Intersection(superTypes), subType) => {
        val results = superTypes.map(x => unifyTy(rhs = subType, lhs = x))
        Intersection.from(results)
      }
      case (superTypes, Intersection(subTypes)) => {
        val results = subTypes.map(x => unifyTy(rhs = x, lhs = superTypes))
        Intersection.from(results)
      }
      case (IntegerType, IntType) => IntType
      case (superType, subType) =>
        if (failed != null) failed else {
          val err = UnifyFailedError(rhs = subType, lhs = superType)
          tyck.report(err)
          new ErrorTerm(err)
        }
    }
  }

  def unifyTyOrNothingType(lhs: Term, rhs: Term): Term = unifyTy(rhs = rhs, lhs = lhs, failed = NothingType)

  def unifyEff(lhs: Option[Effects], rhs: JudgeMaybeEffect): JudgeMaybeEffect = rhs // TODO

  def unifyEff(lhs: Option[Effects], rhs: Judge): Judge = unifyEff(lhs, rhs.toMaybe).get

  /** get the most sub common super type */
  def common(ty1: Term, ty2: Term): Term = {
    if (ty1 == ty2) ty1
    else (ty1, ty2) match {
      case (_, AnyType(level)) => AnyType0 // TODO: level
      case (AnyType(level), _) => AnyType0 // TODO: level
      case (NothingType, ty) => ty
      case (ty, NothingType) => ty
      case (ListType(ty1), ListType(ty2)) => ListType(common(ty1, ty2))
      case (Union(ts1), ty2) => Union(ts1.map(common(_, ty2)))
      case (ty1, Union(ts2)) => Union(ts2.map(common(ty1, _)))
      case (ty1, ty2) =>
        Union.from(Vector(ty1, ty2))
    }
  }

  def common(seq: Seq[Term]): Term = {
    seq.reduce(common)
  }

  def effectFold(es: Seq[Effects]): Effects = {
    Effects.merge(es)
  }

  def effectUnion(e1: Effects, e2: Effects): Effects = e1.merge(e2)

  def collectLevel(xs: Seq[Term]): Term = {
    Level0 // TODO
  }

  def tyFold(types: Vector[Term]): Term = {
    types.reduce((ty1, ty2) => common(ty1, ty2))
  }

  def synthesizeObjectExpr(x: ObjectExpr, effects: Option[Effects]): Judge = {
    val synthesizedClausesWithEffects: Vector[EffectWith[(Term, Term, Term)]] = x.clauses.map {
      case ObjectExprClauseOnValue(keyExpr, valueExpr) => {
        val Judge(wellTypedExpr, exprType, exprEffect) = synthesize(valueExpr, effects)
        val Judge(keyWellTyped, _, keyEffect) = synthesize(keyExpr, effects)
        val combinedEffect = effectUnion(exprEffect, keyEffect)
        EffectWith(combinedEffect, (keyWellTyped, wellTypedExpr, exprType))
      }
      case _ => throw new IllegalArgumentException("Unexpected clause type, maybe no desalted")
    }

    val combinedEffect = effectFold(synthesizedClausesWithEffects.map(_.effect))
    val objectClauses = synthesizedClausesWithEffects.map(_.value)

    val objectTerm = ObjectTerm(objectClauses.map { case (key, value, _) => ObjectClauseValueTerm(key, value) })
    val objectType = ObjectType(objectClauses.map { case (key, _, ty) => ObjectClauseValueTerm(key, ty) })

    Judge(objectTerm, objectType, combinedEffect)
  }

  def synthesizeBlock(block: Block, effects: Option[Effects]): Judge = {
    val heads: Vector[Stmt] = block.heads.map {
      case stmt: Stmt => stmt
      case expr => ExprStmt(expr, expr.meta)
    }
    val bindings = Bindings.reduce(heads.map(_.bindings))
    ???
  }

  @deprecated("not needed")
  def resolveId(id: Identifier, resolved: ResolvedLocalVar, expr: Expr): Expr = {
    def continue(expr: Expr): Expr = resolveId(id, resolved, expr)

    expr match {
      case id1: Identifier if id1.name == id.name => resolved
      case e: (OpSeq | ListExpr | Stmt) => e.descent(continue)
      case e: Literal => e
      case e: FunctionExpr => ???
      case e => ???
    }
  }

  def telescopePrecheck(telescopes: Vector[DefTelescope], cause: Expr): Unit = {
    var ids = telescopes.flatMap(_.args).map(_.getName)
    if (ids.distinct.length != ids.length) {
      tyck.report(DuplicateTelescopeArgError(cause))
    }
    var inits = telescopes.filter(_.args.nonEmpty).flatMap(_.args.init)
    if (inits.exists(_.vararg)) {
      tyck.report(UnsupportedVarargError(cause))
    }
    if (telescopes.exists(_.args.isEmpty)) {
      if (telescopes.length != 1) {
        tyck.report(UnsupportedEmptyTelescopeError(cause))
      }
    }
  }

  def synthesize(expr: Expr, effects: Option[Effects]): Judge = {
    resolve(expr) match {
      case IntegerLiteral(value, meta) =>
        Judge(AbstractIntTerm.from(value), IntegerType, NoEffect)
      case RationalLiteral(value, meta) =>
        Judge(RationalTerm(value), RationalType, NoEffect)
      case StringLiteral(value, meta) =>
        Judge(StringTerm(value), StringType, NoEffect)
      case SymbolLiteral(value, meta) =>
        Judge(SymbolTerm(value), SymbolType, NoEffect)
      case ListExpr(terms, meta) if terms.isEmpty =>
        Judge(ListTerm(Vector()), ListType(NothingType), NoEffect)
      case ListExpr(terms, meta) =>
        val judges: Vector[Judge] = terms.map { term =>
          synthesize(term, effects)
        }
        val ty = tyFold(judges.map(_.ty))
        val effect = effectFold(judges.map(_.effect))
        Judge(ListTerm(judges.map(_.wellTyped)), ListType(ty), effect)
      case objExpr: ObjectExpr =>
        synthesizeObjectExpr(objExpr, effects)
      case block: Block => (synthesizeBlock(block, effects))
      case expr: Stmt => {
        val err = UnexpectedStmt(expr)
        tyck.report(err)
        Judge(new ErrorTerm(err), UnitType, NoEffect)
      }
      case Identifier(id, meta) => {
        val resolved = localCtx.resolve(id)
        resolved match {
          case Some(CtxItem(name, JudgeNoEffect(wellTyped, ty))) =>
            Judge(name, ty, NoEffect)
          case None =>
            val err = IdentifierNotFoundError(expr)
            tyck.report(err)
            Judge(new ErrorTerm(err), new ErrorTerm(err), NoEffect)
        }
      }
      case f: FunctionExpr => {
        telescopePrecheck(f.telescope, f)
        val effects = f.effect.map(this.checkEffect)
        val WithCtxEffect(newCtx, defaultEff, args) = this.synthesizeTelescopes(f.telescope, effects, f)
        val checker = rec(newCtx)
        val resultTy = f.resultTy.map(checker.checkType)
        assert(resultTy.isEmpty || resultTy.get.effect == NoEffect)
        val body = checker.check(f.body, resultTy.map(_.wellTyped), effects)
        val finalEffects = effects.getOrElse(effectUnion(defaultEff, body.effect))
        val funcTy = FunctionType(telescope = args, resultTy = body.ty, finalEffects)
        Judge(Function(funcTy, body.wellTyped), funcTy, NoEffect)
      }

      case _ =>
        val err = UnsupportedExpressionError(expr)
        tyck.report(err)
        Judge(new ErrorTerm(err), new ErrorTerm(err), NoEffect)
    }
  }

  def checkEffect(effectExpr: Expr): Effects = NoEffect // TODO

  def checkType(expr: Expr): Judge = inherit(expr, Typeω)

  def synthesizeTyTerm(term: Term): JudgeNoEffect = {
    term match {
      case _ => ???
    }
  }

  case class EffectWith[T](effect: Effects, value: T)

  def inheritObjectFields(clauses: Vector[ObjectClause], fieldTypes: Vector[ObjectClauseValueTerm], effect: Option[Effects]): EffectWith[Vector[ObjectClauseValueTerm]] = {
    ??? // TODO
  }

  /** part of whnf */
  def normalizeNoEffect(term: Term): Term = {
    term match {
      case Union(xs) => {
        val xs1 = xs.map(x => whnfNoEffect(x))
        Union.from(xs1)
      }
      case wellTyped => wellTyped
    } match {
      case Union(xs) if xs.exists(_.isInstanceOf[AnyType]) =>
        val level = collectLevel(xs)
        AnyType(level)
      case wellTyped => reuse(term, wellTyped)
    }
  }

  def walk(term: MetaTerm): Judge = {
    val state = tyck.getState
    val result = state.subst.walk(term)
    result
  }

  def whnfNoEffect(term: Term): Term = {
    val result = normalizeNoEffect(term)
    result match
      case term: MetaTerm => {
        val walked = walk(term)
        require(walked.effect == NoEffect)
        whnfNoEffect(walked.wellTyped)
      }
      case _ => result
  }

  def genTypeVariable(name: Option[Id] = None, ty: Option[Term] = None, meta: OptionTermMeta = None): Term = {
    val id = name.getOrElse("t")
    val varid = VarId.generate
    MetaTerm(id, varid, ty.getOrElse(Typeω), meta = meta)
  }

  def resolve(expr: Expr): Expr = {
    ExprResolver.resolve(localCtx, expr)
  }

  /** possibly apply an implicit conversion */
  def inheritFallbackUnify(judge: Judge, ty: Term, effect: Option[Effects] = None): Judge = {
    val Judge(wellTypedExpr, exprType, exprEffect) = unifyEff(effect, judge)
    val ty1 = (unifyTy(ty, exprType))
    Judge(wellTypedExpr, ty1, exprEffect)
  }

  def inherit(expr: Expr, ty: Term, effect: Option[Effects] = None): Judge = {
    val expr1: Expr = (resolve(expr))
    val ty1: Term = whnfNoEffect(ty)
    (expr1, ty1) match {
      case (IntegerLiteral(value, meta), IntType) => {
        if (value.isValidInt)
          Judge(IntTerm(value.toInt), IntType, NoEffect)
        else {
          tyck.report(InvalidIntError(expr))
          Judge(IntegerTerm(value.toInt), IntType, NoEffect)
        }
      }
      case (IntegerLiteral(value, meta), NaturalType) => {
        if (value > 0)
          Judge(NaturalTerm(value), NaturalType, NoEffect)
        else {
          tyck.report(InvalidNaturalError(expr))
          Judge(IntegerTerm(value.toInt), NaturalType, NoEffect)
        }
      }
      case (expr, Union(xs)) => ??? // TODO
      case (objExpr: ObjectExpr, ObjectType(fieldTypes, _)) =>
        val EffectWith(inheritedEffect, inheritedFields) = (inheritObjectFields(clauses = objExpr.clauses, fieldTypes = fieldTypes, effect = effect))
        Judge(ObjectTerm(inheritedFields), ty, inheritedEffect)
      case (ListExpr(terms, meta), lstTy@ListType(ty)) =>
        val checkedTerms: Vector[EffectWith[Term]] = terms.map { term =>
          val Judge(wellTypedTerm, termType, termEffect) = (inherit(term, ty, effect))
          EffectWith(termEffect, wellTypedTerm)
        }
        val effect1 = effectFold(checkedTerms.map(_.effect))
        Judge(ListTerm(checkedTerms.map(_.value)), lstTy, effect1)
      case (expr, ty) =>
        inheritFallbackUnify(synthesize(expr, effect), ty, effect)
    }
  }

  def check(expr: Expr, ty: Option[Term] = None, effect: Option[Effects] = None): Judge = ty match {
    case Some(ty) => inherit(expr, ty, effect)
    case None => {
      val Judge(wellTypedExpr, exprType, exprEffect) = unifyEff(effect, synthesize(expr, effect))
      Judge(wellTypedExpr, exprType, exprEffect)
    }
  }

  /** do cleanup on Effects to only use one variable for an effect */
  def cleanup(judge: Judge): Judge = {
    val xs = judge.effect.xs
    val newXs = xs.map(named => named.copy(name = Vector(named.name.head)))
    val effects = Effects.unchecked(newXs)
    var wellTyped = judge.wellTyped
    var ty = judge.ty
    for (x <- xs) {
      for (name <- x.name) {
        wellTyped = wellTyped.substitute(name, x.name.head)
        ty = ty.substitute(name, x.name.head)
      }
    }
    Judge(wellTyped, ty, effects)
  }
}

object ExprTycker {
  private def convertToEither[T](result: TyckResult[TyckState, T]): Either[Vector[TyckError], T] = {
    if (!result.errorsEmpty) {
      Left(result.problems.filter(_.isInstanceOf[TyckError]).asInstanceOf[Vector[TyckError]])
    } else {
      Right(result.result)
    }
  }

  @deprecated("error information are lost")
  def unifyV0(subType: Term, superType: Term, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Term] = {
    val result = unifyTy(superType, subType, state, ctx)
    convertToEither(result)
  }

  @deprecated("error information are lost")
  def inheritV0(expr: Expr, ty: Term, effect: Option[Effects] = None, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Judge] = {
    val result = inherit(expr, ty, effect, state, ctx)
    convertToEither(result)
  }

  @deprecated("error information are lost")
  def synthesizeV0(expr: Expr, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): Either[Vector[TyckError], Judge] = {
    val result = synthesize(expr, state = state, ctx = ctx)
    convertToEither(result)
  }

  def unifyTy(lhs: Term, rhs: Term, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): TyckResult[TyckState, Term] = {
    Tyck.run(ExprTyckerInternal(ctx, _).unifyTy(lhs, rhs))(state)
  }

  def inherit(expr: Expr, ty: Term, effect: Option[Effects] = None, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): TyckResult[TyckState, Judge] = {
    Tyck.run(ExprTyckerInternal(ctx, _).inherit(expr, ty, effect))(state)
  }

  def synthesize(expr: Expr, effect: Option[Effects] = None, state: TyckState = TyckState(), ctx: LocalCtx = LocalCtx.Empty): TyckResult[TyckState, Judge] = {
    Tyck.run(ExprTyckerInternal(ctx, _).synthesize(expr, effect))(state)
  }
}