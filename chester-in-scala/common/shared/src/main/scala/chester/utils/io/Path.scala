package chester.utils.io

import effekt.*

import cats.free.*
import cats.free.Free.*
import cats._

import scala.language.implicitConversions

trait PathOps[T] {
  def of(path: String): T

  def join(p1: T, p2: T): T

  def asString(p: T): String
}

extension [T](p: T)(using ops: PathOps[T]) {
  def /(p2: T): T = ops.join(p, p2)
}

implicit def stringToPath[T](path: String)(using ops: PathOps[T]): T = ops.of(path)

implicit object PathOpsString extends PathOps[String] {
  def of(path: String): String = path

  def join(p1: String, p2: String): String = p1 + "/" + p2

  def asString(p: String): String = p
}

implicit def summonPathOps[F <: FileOps](using fileOps: F): PathOps[fileOps.P] = fileOps.pathOps
implicit def summonMonad[F <: FileOps](using fileOps: F): Monad[fileOps.M] = fileOps.m

object Path {
  def of[T](path: String)(using ops: PathOps[T]): T = ops.of(path)
}

trait FileOps {
  type P
  type M[_]

  def m: Monad[M]

  def catchErrors[A](eff: => M[A]): M[Either[Throwable, A]]

  def pathOps: PathOps[P]

  def read(path: P): M[String]

  def write(path: P, content: String): M[Unit]

  def append(path: P, content: String): M[Unit]

  def removeWhenExists(path: P): M[Boolean]

  def getHomeDir: M[P]

  def exists(path: P): M[Boolean]

  def createDirIfNotExists(path: P): M[Unit]
}

object Files

extension (_files: Files.type)(using fileOps: FileOps) {
  def read(path: fileOps.P): fileOps.M[String] = fileOps.read(path)
  def write(path: fileOps.P, content: String): fileOps.M[Unit] = fileOps.write(path, content)
  def append(path: fileOps.P, content: String): fileOps.M[Unit] = fileOps.append(path, content)
  def removeWhenExists(path: fileOps.P): fileOps.M[Boolean] = fileOps.removeWhenExists(path)
  def getHomeDir: fileOps.M[fileOps.P] = fileOps.getHomeDir
  def exists(path: fileOps.P): fileOps.M[Boolean] = fileOps.exists(path)
  def createDirIfNotExists(path: fileOps.P): fileOps.M[Unit] = fileOps.createDirIfNotExists(path)
}

implicit object MonadControl extends Monad[Control] {
  override def pure[A](a: A): Control[A] = effekt.pure(a)

  override def flatMap[A, B](fa: Control[A])(f: A => Control[B]): Control[B] = fa.flatMap(f)

  override def tailRecM[A, B](a: A)(f: A => Control[Either[A, B]]): Control[B] = f(a).flatMap {
    case Left(a) => tailRecM(a)(f)
    case Right(b) => pure(b)
  }
}

trait FileOpsEff extends FileOps {
  type M[x] = Control[x]

  def m = MonadControl

  def errorFilter(e: Throwable): Boolean
  def catchErrors[A](eff: => M[A]): M[Either[Throwable, A]] = eff map { a => Right(a) } _catch {
    case e if errorFilter(e) => effekt.pure(Left(e))
  }
}

extension [A, M[_]](x: M[A])(using fileOps: FileOps, ev: M[A] =:= fileOps.M[A]) {
  def catchErrors: fileOps.M[Either[Throwable, A]] = fileOps.catchErrors(x)
}

trait FileOpsFree extends FileOps {
  sealed trait Op[A]

  type M[x] = Free[Op, x]

  def m = implicitly

  case class Read(path: P) extends Op[String]

  case class Write(path: P, content: String) extends Op[Unit]

  case class Append(path: P, content: String) extends Op[Unit]

  case class RemoveWhenExists(path: P) extends Op[Boolean]

  case object GetHomeDir extends Op[P]

  case class Exists(path: P) extends Op[Boolean]

  case class CreateDirIfNotExists(path: P) extends Op[Unit]

  def read(path: P): M[String] = liftF(Read(path))

  def write(path: P, content: String): M[Unit] = liftF(Write(path, content))

  def append(path: P, content: String): M[Unit] = liftF(Append(path, content))

  def removeWhenExists(path: P): M[Boolean] = liftF(RemoveWhenExists(path))

  def getHomeDir: M[P] = liftF(GetHomeDir)

  def exists(path: P): M[Boolean] = liftF(Exists(path))

  def createDirIfNotExists(path: P): M[Unit] = liftF(CreateDirIfNotExists(path))
}