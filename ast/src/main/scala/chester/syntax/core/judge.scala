package chester.syntax.core
import upickle.default.*

case class Judge(wellTyped: Term, ty: Term, effects: Effects = NoEffect)  derives ReadWriter {
  def toMaybe: JudgeMaybeEffect = JudgeMaybeEffect(wellTyped, ty, Some(effects))
  def substitute(from: TermWithUniqId, to: Term): Judge = Judge(wellTyped.substitute(from, to), ty.substitute(from, to), effects.substitute(from, to).asInstanceOf[Effects])
  
  def collectMeta: Vector[MetaTerm] = wellTyped.collectMeta ++ ty.collectMeta ++ effects.collectMeta
  def replaceMeta(f: MetaTerm=> Term): Judge = Judge(wellTyped.replaceMeta(f), ty.replaceMeta(f), effects.replaceMeta(f))
}

case class JudgeNoEffect(wellTyped: Term, ty: Term)  derives ReadWriter {
  implicit def toJudge: Judge = Judge(wellTyped, ty)
}

case class JudgeMaybeEffect(wellTyped: Term, ty: Term, effects: Option[Effects] = None)  derives ReadWriter {
  @throws[NoSuchElementException]
  def get: Judge = Judge(wellTyped, ty, effects.get)
}