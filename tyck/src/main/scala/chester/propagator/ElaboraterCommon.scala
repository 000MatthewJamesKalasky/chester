package chester.propagator

import chester.error.{TyckProblem, TypeMismatch}
import chester.resolve.{SimpleDesalt, resolveOpSeq}
import chester.syntax.concrete.*
import chester.syntax.core.*
import chester.tyck.Reporter
import chester.utils.*
import chester.utils.propagator.CommonPropagator

trait ElaboraterCommon extends ProvideCtx {

  def resolve(expr: Expr, localCtx: LocalCtx)(using reporter: Reporter[TyckProblem]): Expr = {
    val result = SimpleDesalt.desugarUnwrap(expr) match {
      case opseq: OpSeq => {
        val result = resolveOpSeq(reporter, localCtx.operators, opseq)
        result
      }
      case default => default
    }
    reuse(expr, result)
  }


  type Literals = Expr & (IntegerLiteral | RationalLiteral | StringLiteral | SymbolLiteral)

  case class Unify(lhs: CellId[Term], rhs: CellId[Term], cause: Expr)(using localCtx: LocalCtx) extends Propagator[Ck] {
    override val readingCells = Set(lhs, rhs)
    override val writingCells = Set(lhs, rhs)
    override val zonkingCells = Set(lhs, rhs)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      val lhs = state.read(this.lhs)
      val rhs = state.read(this.rhs)
      if (lhs.isDefined && rhs.isDefined) {
        unify(lhs.get, rhs.get, cause)
        return true
      }
      return false
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      val lhs = state.read(this.lhs)
      val rhs = state.read(this.rhs)
      (lhs, rhs) match {
        case (Some(lhs), Some(rhs)) if lhs == rhs => return ZonkResult.Done
        case (Some(lhs), None) => {
          state.fill(this.rhs, lhs)
          return ZonkResult.Done
        }
        case (None, Some(rhs)) => {
          state.fill(this.lhs, rhs)
          return ZonkResult.Done
        }
        case _ => return ZonkResult.NotYet
      }
    }
  }

  case class UnionOf(
                      lhs: CellId[Term],
                      rhs: Vector[CellId[Term]],
                      cause: Expr,
                    )(using localCtx: LocalCtx) extends Propagator[Ck] {
    override val readingCells = Set(lhs) ++ rhs.toSet
    override val writingCells = Set(lhs)
    override val zonkingCells = Set(lhs) ++ rhs.toSet

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      val lhsValueOpt = state.read(lhs)
      val rhsValuesOpt = rhs.map(state.read)

      if (lhsValueOpt.isDefined && rhsValuesOpt.forall(_.isDefined)) {
        val lhsValue = lhsValueOpt.get
        val rhsValues = rhsValuesOpt.map(_.get)

        // Check that each rhsValue is assignable to lhsValue
        val assignable = rhsValues.forall { rhsValue =>
          unify(lhsValue, rhsValue, cause)
          true // Assuming unify reports errors internally
        }
        assignable
      } else {
        // Not all values are available yet
        false
      }
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      val lhsValueOpt = state.read(lhs)
      val rhsValuesOpt = rhs.map(state.read)

      val unknownRhs = rhs.zip(rhsValuesOpt).collect { case (id, None) => id }
      if (unknownRhs.nonEmpty) {
        // Wait for all rhs values to be known
        ZonkResult.Require(unknownRhs.toVector)
      } else {
        val rhsValues = rhsValuesOpt.map(_.get)

        lhsValueOpt match {
          case Some(lhsValue) =>
            // LHS is known, unify each RHS with LHS
            rhsValues.foreach { rhsValue =>
              unify(lhsValue, rhsValue, cause)
            }
            ZonkResult.Done
          case None =>
            // LHS is unknown, create UnionType from RHS values and set LHS
            val unionType = Union.from(rhsValues.assumeNonEmpty)
            state.fill(lhs, unionType)
            ZonkResult.Done
        }
      }
    }
  }

  def tryUnify(lhs: Term, rhs: Term)(using state: StateAbility[Ck], localCtx: LocalCtx): Boolean = {
    if (lhs == rhs) return true
    val lhsResolved = lhs match {
      case varCall: MaybeVarCall =>
        localCtx.getKnown(varCall) match {
          case Some(tyAndVal) =>
            state.read(tyAndVal.value).getOrElse(lhs)
          case None => lhs
        }
      case _ => lhs
    }
    val rhsResolved = rhs match {
      case varCall: MaybeVarCall =>
        localCtx.getKnown(varCall) match {
          case Some(tyAndVal) =>
            state.read(tyAndVal.value).getOrElse(rhs)
          case None => rhs
        }
      case _ => rhs
    }
    if (lhsResolved == rhsResolved) return true
    return false // TODO
  }


  case class LiteralType(x: Literals, tyLhs: CellId[Term])(using localCtx: LocalCtx) extends Propagator[Ck] {
    override val readingCells = Set(tyLhs)
    override val writingCells = Set(tyLhs)
    override val zonkingCells = Set(tyLhs)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      if (state.noValue(tyLhs)) return false
      val ty_ = state.read(this.tyLhs).get
      val t = x match {
        case IntegerLiteral(_, _) => IntegerType
        case RationalLiteral(_, _) => RationalType
        case StringLiteral(_, _) => StringType
        case SymbolLiteral(_, _) => SymbolType
      }
      x match {
        case IntegerLiteral(_, _) => {
          if (tryUnify(ty_, IntType)) return true
        }
        case _ => {

        }
      }
      if (ty_ == t) {
        return true
      } else {
        unify(ty_, t, x)
        return true
      }
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult =
      state.fill(tyLhs, x match {
        case IntegerLiteral(_, _) => IntegerType
        case RationalLiteral(_, _) => RationalType
        case StringLiteral(_, _) => StringType
        case SymbolLiteral(_, _) => SymbolType
      })
      ZonkResult.Done
  }

  case class IsEffects(effects: CellId[Effects]) extends Propagator[Ck] {
    override val zonkingCells = Set(effects)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = state.hasValue(effects)

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      state.fill(effects, Effects.Empty)
      ZonkResult.Done
    }
  }

  case class IsType(ty: CellId[Term]) extends Propagator[Ck] {
    override val readingCells = Set(ty)
    override val zonkingCells = Set(ty)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = state.hasValue(ty)

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      if (state.readingZonkings(Vector(ty)).exists { (x: Propagator[Ck]) => !x.isInstanceOf[IsType] }) return ZonkResult.NotYet
      state.fill(ty, AnyType0)
      ZonkResult.Done
    }
  }

  def newType(using ck: Ck, state: StateAbility[Ck]): CellId[Term] = {
    val cell = state.addCell(OnceCell[Term]())
    state.addPropagator(IsType(cell))
    cell
  }

  def unify(lhs: Term, rhs: Term, cause: Expr)(using localCtx: LocalCtx, ck: Ck, state: StateAbility[Ck]): Unit = {
    if (lhs == rhs) return
    val lhsResolved = lhs match {
      case varCall: MaybeVarCall =>
        localCtx.getKnown(varCall) match {
          case Some(tyAndVal) =>
            state.read(tyAndVal.value).getOrElse(lhs)
          case None => lhs
        }
      case _ => lhs
    }
    val rhsResolved = rhs match {
      case varCall: MaybeVarCall =>
        localCtx.getKnown(varCall) match {
          case Some(tyAndVal) =>
            state.read(tyAndVal.value).getOrElse(rhs)
          case None => rhs
        }
      case _ => rhs
    }
    if (lhsResolved == rhsResolved) return
    (lhsResolved, rhsResolved) match {

      // Structural unification for ListType
      case (ListType(elem1), ListType(elem2)) =>
        unify(elem1, elem2, cause)

      case (Type(Levelω), Type(LevelFinite(_))) => ()

      // THIS IS INCORRECT, TODO: FIX
      case (Union(types1), Union(types2)) =>
        val minLength = math.min(types1.length, types2.length)
        (types1.take(minLength), types2.take(minLength)).zipped.foreach { (ty1, ty2) =>
          unify(ty1, ty2, cause)
        }
        if (types1.length != types2.length) {
          ck.reporter.apply(TypeMismatch(lhs, rhs, cause))
        }

      // Base case: types do not match
      case _ =>
        ck.reporter.apply(TypeMismatch(lhs, rhs, cause))

    }
  }

  def unify(t1: Term, t2: CellId[Term], cause: Expr)(using localCtx: LocalCtx, ck: Ck, state: StateAbility[Ck]): Unit = {
    state.addPropagator(Unify(literal(t1), t2, cause))
  }

  def unify(t1: CellId[Term], t2: Term, cause: Expr)(using localCtx: LocalCtx, ck: Ck, state: StateAbility[Ck]): Unit = {
    state.addPropagator(Unify(t1, literal(t2), cause))
  }

  def unify(t1: CellId[Term], t2: CellId[Term], cause: Expr)(using localCtx: LocalCtx, ck: Ck, state: StateAbility[Ck]): Unit = {
    state.addPropagator(Unify(t1, t2, cause))
  }

  /** t is rhs, listT is lhs */
  case class ListOf(tRhs: CellId[Term], listTLhs: CellId[Term], cause: Expr)(using ck: Ck, localCtx: LocalCtx) extends Propagator[Ck] {
    override val readingCells = Set(tRhs, listTLhs)
    override val writingCells = Set(tRhs, listTLhs)
    override val zonkingCells = Set(listTLhs)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      val t1 = state.read(this.tRhs)
      val listT1 = state.read(this.listTLhs)
      (t1, listT1) match {
        case (_, Some(l)) if !l.isInstanceOf[ListType] => {
          ck.reporter.apply(TypeMismatch(l, ListType(t1.get), cause))
          true
        }
        case (Some(t1), Some(ListType(t2))) => {
          unify(t2, t1, cause)
          true
        }
        case (_, Some(ListType(t2))) => {
          unify(t2, tRhs, cause)
          true
        }
        case (Some(t1), None) => {
          unify(this.listTLhs, ListType(t1): Term, cause)
          true
        }
        case (_, _) => false
      }
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      val t1 = state.read(this.tRhs)
      val listT1 = state.read(this.listTLhs)
      if (!t1.isDefined) return ZonkResult.Require(Vector(this.tRhs))
      val ty = t1.get
      assert(listT1.isEmpty)
      state.fill(this.listTLhs, ListType(ty))
      ZonkResult.Done
    }
  }

}