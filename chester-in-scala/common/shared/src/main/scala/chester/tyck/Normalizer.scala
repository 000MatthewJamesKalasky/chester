package chester.tyck

import cats.data.State
import chester.syntax.core.*


case class Normalizer() {
  def apply(term: Term): State[TyckState, Term] = term match {

    case _ => State.pure(term)
  }
}
