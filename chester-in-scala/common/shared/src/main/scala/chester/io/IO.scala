package chester.io

import cats.Monad
import chester.repl.{ReadLineResult, TerminalInfo, TerminalInit}

import scala.util.Try

trait Runner[F[_]] extends Monad[F] {
  def spawn(x: => F[Unit]): Unit

  def doTry[T](IO: F[T]): F[Try[T]]
}

extension [F[_], A](m: F[A])(using runner: Runner[F]) {
  inline def flatMap[B](inline f: A => F[B]): F[B] = runner.flatMap(m)(f)
  inline def map[B](inline f: A => B): F[B] = runner.map(m)(f)
}

implicit inline def summonPathOpsFromIO[F[_]](using io: IO[F]): PathOps[io.Path] = io.pathOps

trait IO[F[_]] {
  type Path

  def pathOps: PathOps[Path]

  def println(x: String): F[Unit]

  def readString(path: Path): F[String]

  def writeString(path: Path, content: String, append: Boolean = false): F[Unit]

  def removeWhenExists(path: Path): F[Boolean]

  def getHomeDir: F[Path]

  def exists(path: Path): F[Boolean]

  def createDirRecursiveIfNotExists(path: Path): F[Unit]

  def downloadToFile(url: String, path: Path): F[Unit]

  def chmodExecutable(path: Path): F[Unit]

  def getAbsolutePath(path: Path): F[Path]
}

object Runner {
  inline def pure[F[_], A](inline x: A)(using inline runner: Runner[F]): F[A] = runner.pure(x)

  inline def doTry[F[_], T](inline IO: F[T])(using inline runner: Runner[F]): F[Try[T]] = runner.doTry(IO)

  inline def spawn[F[_]](inline x: => F[Unit])(using inline runner: Runner[F]): Unit = runner.spawn(x)
}

object IO {
  inline def println[F[_]](inline x: String)(using inline io: IO[F]): F[Unit] = io.println(x)
}

extension [F[_]](_io: IO.type)(using io: IO[F]) {
  inline def readString(inline path: io.Path): F[String] = io.readString(path)
  inline def writeString(inline path: io.Path, inline content: String, inline append: Boolean = false): F[Unit] = io.writeString(path, content, append)
  inline def removeWhenExists(inline path: io.Path): F[Boolean] = io.removeWhenExists(path)
  inline def getHomeDir: F[io.Path] = io.getHomeDir
  inline def exists(inline path: io.Path): F[Boolean] = io.exists(path)
  inline def createDirRecursiveIfNotExists(inline path: io.Path): F[Unit] = io.createDirRecursiveIfNotExists(path)
  inline def downloadToFile(inline url: String, inline path: io.Path): F[Unit] = io.downloadToFile(url, path)
  inline def chmodExecutable(inline path: io.Path): F[Unit] = io.chmodExecutable(path)
  inline def getAbsolutePath(inline path: io.Path): F[io.Path] = io.getAbsolutePath(path)
}

trait Terminal[F[_]] {
  def runTerminal[T](init: TerminalInit, block: InTerminal[F] ?=> F[T]): F[T]
}

object Terminal {
  inline def runTerminal[F[_], T](inline init: TerminalInit)(inline block: InTerminal[F] ?=> F[T])(using inline terminal: Terminal[F]): F[T] =
    terminal.runTerminal(init, block)
}


trait InTerminal[F[_]] {
  def writeln(line: fansi.Str): F[Unit]

  def readline(info: TerminalInfo): F[ReadLineResult]

  def getHistory: F[Seq[String]]
}

object InTerminal {
  inline def writeln[F[_]](inline line: fansi.Str)(using inline terminal: InTerminal[F]): F[Unit] = terminal.writeln(line)
  inline def readline[F[_]](inline info: TerminalInfo)(using inline terminal: InTerminal[F]): F[ReadLineResult] = terminal.readline(info)

  inline def getHistory[F[_]](using inline terminal: InTerminal[F]): F[Seq[String]] = terminal.getHistory
}