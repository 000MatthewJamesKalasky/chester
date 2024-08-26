package chester.io

import chester.parser.InputStatus.{Complete, Error, Incomplete}
import chester.parser.ParserEngine
import chester.repl.*

abstract class AbstractInTerminal[F[_]](using runner: Runner[F]) extends InTerminal[F] {
  private var history: Vector[String] = Vector()
  private var currentInputs: String = ""
  
  private var inited: Boolean = false

  private def checkInit: F[Unit] = {
    if (!inited) {
      inited = true
      initHistory.map { h =>
        history = h.toVector
      }
    } else {
      Runner.pure(())
    }
  }

  def initHistory: F[Seq[String]]

  /** String could be null means EOF */
  def readALine(prompt: fansi.Str): F[String]

  /** When an implementation can only handle one line at a time in history */
  def saveALine(line: String): F[Unit] = Runner.pure(())

  override def readline(info: TerminalInfo): F[ReadLineResult] = {
    def loop(prompt: fansi.Str): F[ReadLineResult] = {
      readALine(prompt).flatMap { line =>
        if (line == null) {
          Runner.pure(EndOfFile)
        } else if (line.forall(_.isWhitespace)) {
          loop(prompt) // continue reading
        } else {
          currentInputs = if (currentInputs.isEmpty) line else s"$currentInputs\n$line"
          for {
            _ <- saveALine(line)
            result <-
              ParserEngine.checkInputStatus(currentInputs) match {
                case Complete =>
                  history = history :+ currentInputs
                  val result = LineRead(currentInputs)
                  currentInputs = ""
                  Runner.pure(result)
                case Incomplete =>
                  loop(info.continuationPrompt)
                case Error(message) =>
                  currentInputs = ""
                  Runner.pure(StatusError(message))
              }
          } yield result
        }
      }
    }

    for {
      _ <- checkInit
      result <- loop(info.defaultPrompt)
    } yield result
  }

  override def getHistory: F[Seq[String]] = for {
    _ <- checkInit
  } yield history
}
