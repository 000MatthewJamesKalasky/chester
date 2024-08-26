package chester.io

import chester.repl.{ReadLineResult, TerminalInfo, TerminalInit}
import typings.xtermXterm.mod

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

final class InXterm(terminal: mod.Terminal, init: TerminalInit) extends AbstractInTerminal[Future] {


  def initHistory: Future[Seq[String]] = Future.successful(Vector())

  inline override def writeln(line: fansi.Str): Future[Unit] = {
    val promise = Promise[Unit]()
    terminal.writeln(line.render, () => {
      promise.success(())
      ()
    })
    promise.future
  }

  terminal.onData(handler)

  private var reading: Promise[String] = null

  def handleLine(data: String): Unit = {
    if (reading != null) {
      val r = reading
      reading = null
      r.success(data)
    }
  }

  var command = ""

  def handler(data: String, unit: Unit): Unit = data match {
    case "\r" => {
      handleLine(command)
      command = ""
    }
    case "\u007F" => {
      if (command.nonEmpty) {
        terminal.write("\b \b")
        command = command.slice(0, command.length - 1)
      }
    }
    case _ => {
      terminal.write(data)
      command += data
    }
  }

  private inline def readOneLine: Future[String] = {
    assert(reading == null)
    val p = Promise[String]()
    reading = p
    p.future
  }

  override inline def readALine(prompt: fansi.Str): Future[String] = {
    val p = Promise[Unit]()
    terminal.write(prompt.render, () => {
      p.success(())
      ()
    })
    p.future.flatMap(_ => readOneLine)
  }
}

case class XtermTerminal(terminal: mod.Terminal) extends Terminal[Future] {
  def runTerminal[T](init: TerminalInit, block: InTerminal[Future] ?=> Future[T]): Future[T] = {
    val future =
      block(using new InXterm(terminal, init))
    future
  }

}
