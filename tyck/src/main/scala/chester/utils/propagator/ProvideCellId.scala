package chester.utils.propagator

// TODO: maybe distinguish between read and fill to have more sound Scala types and functions. One is +T and one is -T
trait ProvideCellId {
  type CIdOf[+T <:Cell[?]]
  type PIdOf[+T<:Propagator[?]]
  type CellId[T] = CIdOf[Cell[T]]
  type SeqId[T] = CIdOf[SeqCell[T]]
  type CellIdOr[T] = CellId[T] | T
  def isCId(x: Any): Boolean
  def assumeCId(x: Any): CIdOf[Cell[?]] = x.asInstanceOf[CIdOf[Cell[?]]]

  sealed trait Cell[T] {
    def read: Option[T]

    def hasValue: Boolean = read.isDefined
    def noValue: Boolean = !hasValue

    /** fill an unstable cell */
    def fill(newValue: T): Cell[T]
  }

  sealed trait SeqCell[T] extends Cell[Seq[T]] {
    def add(newValue: T): SeqCell[T]

    override def fill(newValue: Seq[T]): SeqCell[T] = throw new UnsupportedOperationException("SeqCell cannot be filled")
  }

  sealed trait MapCell[A, B] extends Cell[Map[A, B]] {
    def add(key: A, value: B): MapCell[A, B]

    override def fill(newValue: Map[A, B]): MapCell[A, B] = throw new UnsupportedOperationException("MapCell cannot be filled")
  }

  case class OnceCell[T](value: Option[T] = None) extends Cell[T] {
    override def read: Option[T] = value

    override def fill(newValue: T): OnceCell[T] = {
      require(value.isEmpty)
      copy(value = Some(newValue))
    }
  }

  case class MutableCell[T](value: Option[T]) extends Cell[T] {
    override def read: Option[T] = value

    override def fill(newValue: T): MutableCell[T] = {
      copy(value = Some(newValue))
    }
  }

  case class CollectionCell[T](value: Vector[T] = Vector.empty) extends SeqCell[T] {
    override def read: Option[Vector[T]] = Some(value)

    override def add(newValue: T): CollectionCell[T] = copy(value = value :+ newValue)
  }

  case class MappingCell[A, B](value: Map[A, B] = Map.empty[A, B]) extends MapCell[A, B] {
    override def read: Option[Map[A, B]] = Some(value)

    override def add(key: A, newValue: B): MappingCell[A, B] = copy(value = value + (key -> newValue))
  }

  case class LiteralCell[T](value: T) extends Cell[T] {
    override def read: Option[T] = Some(value)

    override def hasValue: Boolean = true

    override def fill(newValue: T): LiteralCell[T] = throw new UnsupportedOperationException("LiteralCell cannot be filled")
  }

  def literal[T](t: T)(using state: StateAbility[?]): CellId[T] = {
    val cell = state.addCell(LiteralCell[T](t))
    cell
  }

  trait Propagator[Ability] {

    def readingCells: Set[CIdOf[Cell[?]]] = Set.empty

    def writingCells: Set[CIdOf[Cell[?]]] = Set.empty

    def zonkingCells: Set[CIdOf[Cell[?]]] = Set.empty

    /**
     * @return true if the propagator finished its work
     */
    def run(using state: StateAbility[Ability], more: Ability): Boolean

    /** make a best guess for zonkingCells */
    def naiveZonk(needed: Vector[CIdOf[Cell[?]]])(using state: StateAbility[Ability], more: Ability): ZonkResult
  }

  trait CellsStateAbility {
    def readCell[T <: Cell[?]](id: CIdOf[T]): Option[T]

    def read[U](id: CellId[U]): Option[U] = readCell[Cell[U]](id).get.read

    protected def update[T <: Cell[?]](id: CIdOf[T], f: T => T): Unit

    def fill[T <: Cell[U], U](id: CIdOf[T], f: U): Unit = {
      update[T](id, _.fill(f).asInstanceOf[T])
    }

    def add[T <: SeqCell[U], U](id: CIdOf[T], f: U): Unit = {
      update[T](id, _.add(f).asInstanceOf[T])
    }

    def add[T <: MapCell[A, B], A, B](id: CIdOf[T], key: A, value: B): Unit = {
      update[T](id, _.add(key, value).asInstanceOf[T])
    }

    def addCell[T <: Cell[?]](cell: T): CIdOf[T]

    def hasValue[T <: Cell[?]](id: CIdOf[T]): Boolean = readCell(id).exists((x: T) => x.hasValue)

    def noValue[T <: Cell[?]](id: CIdOf[T]): Boolean = !hasValue(id)
  }

  trait StateAbility[Ability] extends CellsStateAbility {
    def addPropagator[T<:Propagator[Ability]](propagator: T)(using more: Ability): PIdOf[T]

    def tick(using more: Ability): Unit

    def stable: Boolean

    def tickAll(using more: Ability): Unit = {
      while (!stable) {
        tick(using more)
      }
    }

    def readingZonkings(cells: Vector[CIdOf[Cell[?]]]): Vector[Propagator[Ability]]

    /** make a best guess for those cells */
    def naiveZonk(cells: Vector[CIdOf[Cell[?]]])(using more: Ability): Unit

    def toId[T](x: CellIdOr[T]): CIdOf[Cell[T]] = x match {
      case x if isCId(x) => x.asInstanceOf[CIdOf[Cell[T]]]
      case x: T => {
        val cell = addCell(LiteralCell[T](x))
        cell.asInstanceOf[CIdOf[Cell[T]]]
      }
    }
  }

  enum ZonkResult {
    case Done extends ZonkResult
    case Require(needed: Seq[CIdOf[Cell[?]]]) extends ZonkResult
    case NotYet extends ZonkResult
  }

}
