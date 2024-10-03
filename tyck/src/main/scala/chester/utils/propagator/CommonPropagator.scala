package chester.utils.propagator
import cats.implicits.*

trait CommonPropagator[Ck] extends ProvideCellId {

  case class Merge[T](a: CellId[T], b: CellId[T]) extends Propagator[Ck] {
    override val readingCells = Set(a, b)
    override val writingCells = Set(a, b)
    override val zonkingCells = Set(a, b)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      val aVal = state.read(a)
      val bVal = state.read(b)
      if (aVal.isDefined && bVal.isDefined) {
        if (aVal.get == bVal.get) return true
        throw new IllegalStateException("Merge propagator should not be used if the values are different")
        return true
      }
      if (aVal.isDefined) {
        state.fill(b, aVal.get)
        return true
      }
      if (bVal.isDefined) {
        state.fill(a, bVal.get)
        return true
      }
      false
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      val aVal = state.read(a)
      val bVal = state.read(b)
      if (aVal.isDefined && bVal.isDefined) {
        if (aVal.get == bVal.get) return ZonkResult.Done
        throw new IllegalStateException("Merge propagator should not be used if the values are different")
        return ZonkResult.Done
      }
      if (aVal.isDefined) {
        state.fill(b, aVal.get)
        return ZonkResult.Done
      }
      if (bVal.isDefined) {
        state.fill(a, bVal.get)
        return ZonkResult.Done
      }
      ZonkResult.NotYet
    }
  }

  case class FlatMaping[T, U](xs: Seq[CellId[T]], f: Seq[T] => U, result: CellId[U]) extends Propagator[Ck] {
    override val readingCells = xs.toSet
    override val writingCells = Set(result)
    override val zonkingCells = Set(result)

    override def run(using state: StateAbility[Ck], more: Ck): Boolean = {
      xs.traverse(state.read(_)).map(f) match {
        case Some(result) => {
          state.fill(this.result, result)
          true
        }
        case None => false
      }
    }

    override def naiveZonk(needed: Vector[CellId[?]])(using state: StateAbility[Ck], more: Ck): ZonkResult = {
      val needed = xs.filter(state.noValue(_))
      if (needed.nonEmpty) return ZonkResult.Require(needed)
      val done = run
      require(done)
      ZonkResult.Done
    }
  }

  def FlatMap[T, U](xs: Seq[CellId[T]])(f: Seq[T] => U)(using ck: Ck, state: StateAbility[Ck]): CellId[U] = {
    val cell = state.addCell(OnceCell[U]())
    state.addPropagator(FlatMaping(xs, f, cell))
    cell
  }

  def Map1[T, U](x: CellId[T])(f: T => U)(using ck: Ck, state: StateAbility[Ck]): CellId[U] = {
    val cell = state.addCell(OnceCell[U]())
    state.addPropagator(FlatMaping(Vector(x), (xs: Seq[T]) => f(xs.head), cell))
    cell
  }

  def Map2[A, B, C](x: CellId[A], y: CellId[B])(f: (A, B) => C)(using ck: Ck, state: StateAbility[Ck]): CellId[C] = {
    val cell = state.addCell(OnceCell[C]())
    state.addPropagator(FlatMaping(Vector[CellId[Any]](x.asInstanceOf[CellId[Any]], y.asInstanceOf[CellId[Any]]), (xs: Seq[Any]) => f(xs(0).asInstanceOf[A], xs(1).asInstanceOf[B]), cell))
    cell
  }

  def Traverse[A](x: Seq[CellId[A]])(using ck: Ck, state: StateAbility[Ck]): CellId[Seq[A]] = FlatMap(x)(identity)

}