package chester.tyck

import cats.Monad
import chester.error.{TyckError, TyckWarning}
import cps.{CpsMonad, CpsMonadContext}

trait Trying[State, +Result] {
  def apply(state: State): Vector[TyckResult[State, Result]]
}

private def processSeq[State, Result](seq: Vector[TyckResult[State, Result]]): Vector[TyckResult[State, Result]] = {
  val filtered = seq.filter(_.errorsEmpty)
  if (filtered.isEmpty) {
    seq
  } else {
    filtered
  }
}

extension [State, Result](self: Trying[State, Result]) {
  inline def run(inline state: State): Vector[TyckResult[State, Result]] = {
    val seq = self.apply(state)
    processSeq(seq)
  }

  inline def map[U](inline f: Result => U): Trying[State, U] = { (state: State) =>
    self.run(state).map { x => x.copy(result = f(x.result)) }
  }

  inline def flatMap[U](inline f: Result => Trying[State, U]): Trying[State, U] = { (state0: State) =>
    self.run(state0).flatMap { x =>
      f(x.result).run(x.state).map { y =>
        TyckResult(warnings = x.warnings ++ y.warnings, errors = x.errors ++ y.errors, state = y.state, result = y.result)
      }
    }
  }

  inline def ||[U >: Result](inline other: Trying[State, U]): Trying[State, U] = { (state: State) =>
    val seq1 = self.run(state)
    val seq2 = other.run(state)
    seq1 ++ seq2
  }
}

object Trying {
  inline def state[State]: Trying[State, State] = { (state: State) =>
    Vector(TyckResult(state = state, result = state))
  }

  inline def state_=[State](inline state: State): Trying[State, Unit] = { _ =>
    Vector(TyckResult(state = state, result = ()))
  }

  inline def error[State](inline error: TyckError): Trying[State, Unit] = { (state: State) =>
    Vector(TyckResult(state = state, result = (), errors = Vector(error)))
  }
  inline def errors[State](inline errors: Vector[TyckError]): Trying[State, Unit] = { (state: State) =>
    Vector(TyckResult(state = state, result = (), errors = errors))
  }

  inline def warning[State](inline warning: TyckWarning): Trying[State, Unit] = { (state: State) =>
    Vector(TyckResult(state = state, result = (), warnings = Vector(warning)))
  }

  inline def pure[State, Value](inline value: Value): Trying[State, Value] = { (state: State) =>
    Vector(TyckResult(state = state, result = value))
  }
}

val cpsMonadTryingInstance: CpsMonadTrying[?] = new CpsMonadTrying
implicit inline def cpsMonadTrying[State]: CpsMonadTrying[State] = cpsMonadTryingInstance.asInstanceOf

final class CpsMonadTrying[State] extends CpsMonad[[X] =>> Trying[State, X]] with CpsMonadContext[[X] =>> Trying[State, X]] {
  override inline def pure[A](a: A): WF[A] = Trying.pure(a)

  override inline def map[A, B](fa: WF[A])(f: A => B): WF[B] = fa.map(f)

  override inline def flatMap[A, B](fa: WF[A])(f: A => WF[B]): WF[B] = fa.flatMap(f)

  override inline def monad: CpsMonad[WF] = this

  override type Context = this.type

  override inline def apply[T](op: Context => WF[T]): WF[T] = op(this)
}

implicit def monadTrying[State]: Monad[[X] =>> Trying[State, X]] = new MonadTrying[State]

final class MonadTrying[State] extends Monad[[X] =>> Trying[State, X]] {
  type WF[A] = Trying[State, A]

  override inline def pure[A](a: A): WF[A] = Trying.pure(a)

  override inline def map[A, B](fa: WF[A])(f: A => B): WF[B] = fa.map(f)

  override inline def flatMap[A, B](fa: WF[A])(f: A => WF[B]): WF[B] = fa.flatMap(f)

  override def tailRecM[A, B](a: A)(f: A => WF[Either[A, B]]): WF[B] = { (state: State) =>
    f(a).run(state).flatMap { (x: TyckResult[State, Either[A, B]]) =>
      x.result match {
        case Left(a) => tailRecM(a)(f).run(state)
        case Right(b) => Vector(x.copy(result = b))
      }
    }
  }
}
