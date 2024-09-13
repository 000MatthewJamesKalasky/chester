package chester.tyck

import chester.syntax.core.*

trait EffTycker[Self <: TyckerBase[Self] & TelescopeTycker[Self] & EffTycker[Self]] extends Tycker[Self] {

  def unifyEff(lhs: Option[Effects], rhs: JudgeMaybeEffect): JudgeMaybeEffect = rhs // TODO

  def unifyEff(lhs: Option[Effects], rhs: Judge): Judge = unifyEff(lhs, rhs.toMaybe).get

  def unifyEff(lhs: Effects, rhs: Judge): Judge = unifyEff(Some(lhs), rhs)

  def cleanupFunction(function: Function): Judge = {
    val oldEffects = function.ty.effects
    val body = cleanupEffects(Judge(function.body, function.ty.resultTy, function.ty.effects))
    val effects = body.effects
    val args = function.ty.telescope.map(telescope => telescope.copy(args = telescope.args.map(arg => arg.copy(default = arg.default.map(default => unifyEff(effects, default, arg.ty, oldEffects))))))
    val resultTy = unifyEff(effects, function.ty.resultTy, this.synthesizeTyTerm(function.ty.resultTy).ty, oldEffects)
    Judge(function.copy(ty = function.ty.copy(telescope = args, resultTy = resultTy), body = body.wellTyped), resultTy)
  }

  def unifyEff(effects: Effects, term: Term, ty: Term, oldEffects: Effects): Term = {
    unifyEff(effects, Judge(term, ty, oldEffects)).wellTyped
  }

  /** do cleanup on Effects to only use one variable for an effect */
  def cleanupEffects(judge: Judge): Judge = {
    val newEffects = Effects.unchecked(judge.effects.effects.map { case (effect, names) =>
      effect -> Vector(names.head)
    })
    var wellTyped = judge.wellTyped
    var ty = judge.ty
    for ((effect, names) <- judge.effects.effects; name <- names.tail) {
      wellTyped = wellTyped.substitute(name, names.head)
      ty = ty.substitute(name, names.head)
    }
    Judge(wellTyped, ty, newEffects)
  }
}