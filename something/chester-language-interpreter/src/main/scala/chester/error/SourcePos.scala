package chester.error

case class Pos(index: Int, line: Int, column:Int)

case class RangeInFile(start: Pos, end: Pos)

case class SourcePos(fileName: String, range: RangeInFile)
