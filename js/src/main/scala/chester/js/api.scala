package chester.js

import chester.doc.const.LightMode
import chester.parser.{FileNameAndContent, Parser}
import chester.tyck.*
import chester.utils.doc.ColorfulToHtml.colorfulToHtml
import chester.utils.doc.*

def runFileTopLevel(content: String, lightMode: Boolean): String = {
  implicit val options: PrettierOptions = PrettierOptions.Default.updated(LightMode, lightMode)
  Parser.parseTopLevel(FileNameAndContent("playground.chester", content)) match {
    case Right(parsedBlock) =>
      Tycker.check(parsedBlock) match {
        case TyckResult.Success(result, status, warnings) =>
          colorfulToHtml(ColorfulPrettyPrinter.render(result.wellTyped))
        case TyckResult.Failure(errors, warnings, state, result) =>
          s"Failed to type check file: $errors"
      }
    case Left(error) =>
      error.message
  }
}
