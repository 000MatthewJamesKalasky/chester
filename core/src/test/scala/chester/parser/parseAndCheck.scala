package chester.parser

import chester.syntax.concrete.*
import munit.Assertions.{assertEquals, fail}
import munit.FunSuite
import upickle.default.*

def parseAndCheck(input: String, expected: Expr): Unit = {
  val resultignored = Parser.parseExpr(FileNameAndContent("testFile", input)) // it must parse with location
  Parser.parseExpr(FileNameAndContent("testFile", input), ignoreLocation = true) match {
    case Right(value) =>
      assertEquals(read[Expr](write[Expr](value)), value)
      assertEquals(read[Expr](write[Expr](resultignored.right.get)), resultignored.right.get)
      assertEquals(readBinary[Expr](writeBinary[Expr](value)), value)
      assertEquals(readBinary[Expr](writeBinary[Expr](resultignored.right.get)), resultignored.right.get)
      assertEquals(value, expected, s"Failed for input: $input")
    case Left(error) =>
      fail(s"Parsing failed for input: $input ${error.message} at index ${error.index}")
  }
}

def getParsed(input: String): Expr = {
  val resultignored = Parser.parseExpr(FileNameAndContent("testFile", input)) // it must parse with location
  Parser.parseExpr(FileNameAndContent("testFile", input), ignoreLocation = true) match {
    case Right(value) => value
    case Left(error) => fail(s"Parsing failed for input: $input ${error.message} at index ${error.index}")
  }
}
