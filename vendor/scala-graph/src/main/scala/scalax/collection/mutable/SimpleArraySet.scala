package scalax.collection
package mutable

import scala.collection.mutable.{ExtHashSet, GrowableBuilder}
import scala.collection.{IterableFactory, IterableFactoryDefaults, SortedSet, StrictOptimizedIterableOps}
import scala.util.Random

import scalax.collection.immutable.SortedArraySet

/** A basic [[ArraySet]] implementation suitable for efficient add operations.
  * Element removal could be optimized by another implementation.
  *
  * @param hints Optimization hints controlling the growth of the underlying
  *        [[scala.collection.mutable.ArrayBuffer!]].
  * @define OPT Optimized by use of unchecked insertions.
  * @author Peter Empen
  */
@SerialVersionUID(1L)
final class SimpleArraySet[A](override val hints: ArraySet.Hints)
    extends ArraySet[A]
    with StrictOptimizedIterableOps[A, SimpleArraySet, SimpleArraySet[A]]
    with IterableFactoryDefaults[A, SimpleArraySet]
    with Serializable {

  override def iterableFactory: IterableFactory[SimpleArraySet] = SimpleArraySet

  protected[collection] def newNonCheckingBuilder[B] = new SimpleArraySet.NonCheckingBuilder[A, B](this)
  override def clone: ArraySet[A]                    = (newNonCheckingBuilder ++= this).result()
  private var nextFree: Int                          = 0
  private var arr: Array[A]                          = _
  private var hashSet: ExtHashSet[A]                 = _

  private def initialize(): Unit = {
    val capacity = hints.nextCapacity(0)
    if (capacity == 0) hashSet = ExtHashSet.empty[A]
    else arr = new Array[AnyRef](capacity).asInstanceOf[Array[A]]
  }
  initialize()

  def capacity: Int                         = if (isHash) 0 else arr.length
  @inline private def isHash: Boolean       = arr eq null
  @inline def isArray: Boolean              = !isHash
  protected[collection] def array: Array[A] = arr
  protected[collection] def set: MSet[A]    = hashSet

  def addOne(elem: A) = { add(elem); this }

  def subtractOne(elem: A) = {
    if (isHash) hashSet -= elem
    else removeIndex(indexOf(elem))
    this
  }

  protected def removeIndex(i: Int): Unit =
    if (i != -1) {
      if (i + 1 < nextFree)
        java.lang.System.arraycopy(arr, i + 1, arr, i, nextFree - i - 1)
      nextFree -= 1
    }

  protected[collection] def +=!(elem: A): this.type = {
    if (isHash) hashSet add elem
    else {
      if (nextFree == capacity)
        if (resizedToHash) {
          add(elem); return this
        }
      arr(nextFree) = elem
      nextFree += 1
    }
    this
  }

  protected[collection] def map(xs: IterableOnce[A]): this.type = this

  override def iterator: Iterator[A] =
    if (isHash) hashSet.iterator
    else
      new scala.collection.AbstractIterator[A] {
        private[this] var i          = 0
        private[this] var prevElm: A = _
        def hasNext =
          i < nextFree
        def next() = {
          if (i >= nextFree)
            throw new NoSuchElementException
          prevElm = arr(i)
          i += 1
          prevElm
        }
      }

  override def foreach[U](f: (A) => U): Unit =
    if (isHash) hashSet foreach f
    else {
      var i = 0
      while (i < nextFree) { f(arr(i)); i += 1 }
    }

  protected def resizeArray(fromCapacity: Int, toCapacity: Int): Unit = {
    val newArr: Array[AnyRef] = new Array(toCapacity)
    java.lang.System.arraycopy(arr, 0, newArr, 0, math.min(fromCapacity, toCapacity))
    arr = newArr.asInstanceOf[Array[A]]
  }

  protected def setToArray(set: Iterable[A], size: Int): Unit = {
    arr = new Array[AnyRef](size).asInstanceOf[Array[A]]
    nextFree = 0
    set foreach { elem =>
      arr(nextFree) = elem
      nextFree += 1
    }
    hashSet = null
  }

  def compact(): Unit =
    if (isHash) {
      val _size = size
      if (_size < hints.hashTableThreshold)
        setToArray(hashSet, _size)
    } else if (
      hints.compactUpToUsed match {
        case perc if perc == 0   => false
        case perc if perc == 100 => nextFree < capacity
        case perc                => perc >= nextFree * 100 / capacity
      }
    )
      resizeArray(capacity, nextFree)

  protected def indexOf[B](elem: B, pred: (A, B) => Boolean): Int = {
    var i = 0
    while (i < nextFree)
      if (pred(arr(i), elem)) return i
      else i += 1
    -1
  }

  /* Optimized 'arr contains c'. */
  protected def indexOf(elem: A): Int = {
    var i = 0
    while (i < nextFree)
      if (arr(i) == elem) return i
      else i += 1
    -1
  }

  override def contains(elem: A): Boolean =
    if (isHash) hashSet contains elem
    else indexOf(elem) >= 0

  def find(elem: A): Option[A] =
    if (isHash) hashSet find (_ == elem)
    else {
      val i = indexOf(elem)
      if (i >= 0) Some(arr(i)) else None
    }

  override def add(elem: A): Boolean =
    if (isHash) hashSet add elem
    else {
      if (nextFree == capacity)
        if (resizedToHash)
          return add(elem)
      var i = 0
      while (i < nextFree)
        if (arr(i) == elem) return false
        else i += 1
      arr(nextFree) = elem
      nextFree += 1
      true
    }

  protected def resizedToHash: Boolean = {
    val newCapacity = hints.nextCapacity(capacity)
    if (newCapacity == 0) {
      hashSet = ExtHashSet.empty[A]
      hashSet sizeHint capacity
      hashSet ++= iterator
      arr = null
      true
    } else {
      resizeArray(capacity, newCapacity)
      false
    }
  }

  override def size = if (isHash) hashSet.size else nextFree

  protected[collection] def upsert(elem: A with AnyRef): Boolean =
    if (isHash) hashSet upsert elem
    else {
      val i        = indexOf(elem)
      val isUpdate = i >= 0
      if (isUpdate) arr(i) = elem
      else add(elem)
      !isUpdate
    }

  /** $OPT */
  override def filter(p: (A) => Boolean) =
    if (isHash) super.filter(p)
    else {
      val b = newNonCheckingBuilder[A]
      for (x <- this)
        if (p(x)) b += x
      b.result()
    }

  /** Faster mapping in case the caller ensures to insert no duplicates. */
  protected[collection] def mapUnchecked[B, That](f: A => B): SimpleArraySet[B] =
    if (isHash) super.map(f)
    else {
      val b = newNonCheckingBuilder[B]
      for (x <- this) b += f(x)
      b.result()
    }

  /** $OPT */
  override def partition(p: A => Boolean) =
    if (isHash) super.partition(p)
    else {
      val l, r = newNonCheckingBuilder[A]
      for (x <- this) (if (p(x)) l else r) += x
      (l.result(), r.result())
    }

  def sorted(implicit ord: Ordering[A]): SortedSet[A] =
    if (isHash)
      SortedSet.from(hashSet)
    else {
      val newArr: Array[AnyRef] = new Array(nextFree)
      java.lang.System.arraycopy(arr, 0, newArr, 0, nextFree)
      new SortedArraySet(newArr.asInstanceOf[Array[A]])
    }

  def findElem[B](other: B, correspond: (A, B) => Boolean): A =
    if (isHash) hashSet findElem (other, correspond)
    else {
      val idx = indexOf(other, (a: A, b: B) => a.hashCode == b.hashCode && correspond(a, b))
      (if (idx < 0) null else arr(idx)).asInstanceOf[A]
    }

  def draw(random: Random): A =
    if (isHash) hashSet draw random
    else arr(random.nextInt(size))

  override def clear(): Unit =
    for (elem <- this.toList)
      this -= elem
}

/** @define FROM The [[ArraySet]] instance an operation of which this builder is invoked on.
  */
object SimpleArraySet extends IterableFactory[SimpleArraySet] {

  /** Returns an empty set with default hints. */
  override def empty[A]: SimpleArraySet[A] = new SimpleArraySet[A](ArraySet.Hints())

  /** Returns an empty set with custom hints. */
  def emptyWithHints[A](implicit hints: ArraySet.Hints): SimpleArraySet[A] =
    new SimpleArraySet[A](hints)

  /** Returns an empty set with hints propagated from `arraySet`. */
  def emptyWithPropagatedHints[A, B](arraySet: ArraySet[A]): SimpleArraySet[B] =
    emptyWithHints(arraySet.hints.propagate(arraySet.size))

  /** Default `ArraySet` builder preventing duplicates. The hints passed are propagated
    * such that `initial size == from.size`.
    *
    * @param from $FROM
    */
  protected class CheckingBuilder[A](from: ArraySet[A])
      extends GrowableBuilder[A, SimpleArraySet[A]](emptyWithPropagatedHints(from))

  /** An `ArraySet` builder without duplicate checking.
    *
    * @param from $FROM
    */
  protected class NonCheckingBuilder[A, B](from: ArraySet[A])
      extends GrowableBuilder[B, SimpleArraySet[B]](emptyWithPropagatedHints[A, B](from)) {
    override def addOne(x: B): this.type = { elems +=! x; this }
  }

  override def from[A](source: IterableOnce[A]) = empty ++= source

  override def newBuilder[A]: GrowableBuilder[A, SimpleArraySet[A]] = new GrowableBuilder[A, SimpleArraySet[A]](empty)
}
