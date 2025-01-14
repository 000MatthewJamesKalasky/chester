package chester.utils.parse

import fastparse.*
import fastparse.NoWhitespace.*

import java.lang.Character.{isDigit, isLetter}

type Character = Int


inline def CharacterPred(inline p: Character => Boolean)(implicit ctx: P[?]): P[Unit] =
  CharPred(c => (!Character.isSurrogate(c) && p(c.toInt))) |
    (CharPred(Character.isHighSurrogate) ~ CharPred(Character.isLowSurrogate)).!.flatMap { c =>
      assert(c.length == 2)
      val highSurrogate = c.charAt(0)
      val lowSurrogate = c.charAt(1)

      val codePoint = ((highSurrogate - 0xD800) << 10) + (lowSurrogate - 0xDC00) + 0x10000

      if (p(codePoint)) Pass else Fail
    }


inline def CharactersWhile(inline p: Character => Boolean, inline min: Int = 1)(implicit ctx: P[?]): P[Unit] =
  CharacterPred(p).rep(min)

inline def StringPred(inline p: String => Boolean)(implicit ctx: P[?]): P[Unit] =
  AnyChar.rep.!.flatMap(c => if (p(c)) Pass else Fail)