package vyxal

import scala.language.strictEquality

import vyxal.impls.Elements
import vyxal.Token.*
import vyxal.TokenType.*

import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, Queue}
import scala.util.matching.Regex
import scala.util.parsing.combinator.*

object LitLexer:
  private val endKeywords = List(
    "endfor",
    "end-for",
    "endwhile",
    "end-while",
    "endlambda",
    "end-lambda",
    "end",
  ).map(_ -> TokenType.StructureClose).toMap

  private val branchKeywords = List(
    "else",
    "elif",
    "else-if",
    "body",
    "do",
    "branch",
    "->",
    "then",
    "in",
    "using",
  ).map(_ -> TokenType.Branch).toMap

  /** Map keywords to their token types */
  private val keywords = Map(
    "close-all" -> TokenType.StructureAllClose
  ) ++ branchKeywords ++ endKeywords

  /** Keywords for opening structures. Has to be a separate map because while
    * all of them have the same [[TokenType]], they have different values
    * depending on the kind of structure
    */
  private val structOpeners = Map(
    // These can't go in the big map, because that's autogenerated
    "?" -> StructureType.Ternary,
    "?->" -> StructureType.Ternary,
    "if" -> StructureType.IfStatement,
    "for" -> StructureType.For,
    "do-to-each" -> StructureType.For,
    "while" -> StructureType.While,
    "is-there?" -> StructureType.DecisionStructure,
    "does-exist?" -> StructureType.DecisionStructure,
    "is-there" -> StructureType.DecisionStructure,
    "does-exist" -> StructureType.DecisionStructure,
    "any-in" -> StructureType.DecisionStructure,
    "relation" -> StructureType.GeneratorStructure,
    "generate-from" -> StructureType.GeneratorStructure,
    "generate" -> StructureType.GeneratorStructure,
    "lambda" -> StructureType.Lambda,
    "lam" -> StructureType.Lambda,
    "map-lambda" -> StructureType.LambdaMap,
    "map-lam" -> StructureType.LambdaMap,
    "filter-lambda" -> StructureType.LambdaFilter,
    "filter-lam" -> StructureType.LambdaFilter,
    "sort-lambda" -> StructureType.LambdaSort,
    "sort-lam" -> StructureType.LambdaSort,
    "reduce-lambda" -> StructureType.LambdaReduce,
    "reduce-lam" -> StructureType.LambdaReduce,
    "fold-lambda" -> StructureType.LambdaReduce,
    "fold-lam" -> StructureType.LambdaReduce
  )

  /** Tokenize a piece of code in literate mode */
  def apply(code: String): Either[VyxalCompilationError, List[Token]] =
    import LiterateParsers.*
    (parseAll(tokens, code): @unchecked) match
      case NoSuccess(msg, next)  => Left(VyxalCompilationError(msg))
      case Success(result, next) => Right(result)

  /** Convert literate mode code into SBCS mode code */
  def sbcsify(tokens: List[Token]): String =
    val out = StringBuilder()

    for i <- tokens.indices do
      val Token(tokenType, value, _) = tokens(i)
      val next = if i == tokens.length - 1 then None else Some(tokens(i + 1))
      tokenType match
        case Number =>
          if value == "0" then out.append("0")
          else
            next match
              case Some(Number(nextNumber, _)) =>
                if nextNumber == "." && value.endsWith(".") then
                  out.append(value)
                else out.append(value + " ")
              case _ => out.append(value)
        case GetVar | SetVar | AugmentVar =>
          next match
            case Some(token) =>
              if "[a-zA-Z0-9_]+".r.matches(token.value)
              then out.append(value + " ")
              else out.append(value)
            case _ => out.append(value)
        case _ => out.append(value)
      end match
    end for

    out.toString
  end sbcsify

  private object LiterateParsers extends Lexer:
    override val whiteSpace: Regex = "[ \t\r\f]+".r

    private val litDecimalRegex =
      raw"(-?((0|[1-9][0-9_]*)?\.[0-9]*|0|[1-9][0-9_]*))"
    override def number: Parser[Token] =
      withRange(
        raw"(${litDecimalRegex}i($litDecimalRegex)?)|(i$litDecimalRegex)|$litDecimalRegex|(i( |$$))".r
      ) ^^ { case (value, range) =>
        val temp = value.replace("i", "ı").replace("_", "")
        val parts =
          if !temp.endsWith("ı") then temp.split("ı").toSeq
          else temp.init.split("ı").toSeq :+ ""
        Token(
          Number,
          parts
            .map(x => if x.startsWith("-") then x.substring(1) + "_" else x)
            .mkString("ı"),
          range
        )
      }

    override def string: Parser[Token] =
      withRange(raw"""("(?:[^"\\]|\\.)*")""".r) ^^ { case (value, range) =>
        Token(Str, value.substring(1, value.length - 1), range)
      }

    override def contextIndex: Parser[Token] =
      withRange("""`\d*`""".r) ^^ { case (value, range) =>
        Token(ContextIndex, value.substring(1, value.length - 2), range)
      }

    def lambdaOpen: Parser[Token] = withRange("{") ^^ { case (_, range) =>
      Token(StructureOpen, StructureType.Lambda.open, range)
    }

    def litListOpen: Parser[Token] = withRange("[") ^^ { case (_, range) =>
      Token(ListOpen, "#[", range)
    }

    def litListClose: Parser[Token] = withRange("]") ^^ { case (_, range) =>
      Token(ListClose, "#]", range)
    }

    def normalGroup: Parser[List[Token]] = "(" ~> tokens <~ ")"

    def word: Parser[Token] =
      withRange("""[a-zA-Z?!*+=&%><-][a-zA-Z0-9?!*+=&%><-]*""".r) ^^ {
        case (word, range) =>
          Elements.elements.values.find(_.keywords.contains(word)) match
            case Some(element) => Token(Command, element.symbol, range)
            case None =>
              Modifiers.modifiers.keys.find(mod =>
                Modifiers.modifiers(mod).keywords.contains(word)
              ) match
                case Some(mod) =>
                  val tokenType = Modifiers.modifiers(mod).arity match
                    case 1 => MonadicModifier
                    case 2 => DyadicModifier
                    case 3 => TriadicModifier
                    case 4 => TetradicModifier
                    case _ => SpecialModifier
                  Token(tokenType, mod, range)
                case None =>
                  keywords.get(word) match
                    case Some(tokenType) => Token(tokenType, word, range)
                    case None =>
                      structOpeners.get(word) match
                        case Some(structType) =>
                          Token(StructureOpen, structType.open, range)
                        case None =>
                          throw RuntimeException(
                            s"Unrecognized word in literate mode: $word"
                          )
      }

    def litGetVariable: Parser[Token] =
      withRange("""\$([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(GetVar, value.substring(1), range)
      }

    def litSetVariable: Parser[Token] =
      withRange(""":=([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(SetVar, value.substring(2), range)
      }

    def litSetConstant: Parser[Token] =
      withRange(""":!=([_a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ {
        case (value, range) =>
          Token(Constant, value.substring(3), range)
      }

    def litAugVariable: Parser[Token] =
      withRange(""":>([a-zA-Z][_a-zA-Z0-9]*)?""".r) ^^ { case (value, range) =>
        Token(Constant, value.substring(2), range)
      }

    def unpackVar: Parser[Token] =
      parseToken(SyntaxTrigraph, ":=[")

    // TODO figure out what this is for
    // def tilde: Parser[Token] = "~" ^^^ AlreadyCode("!")

    def litBranch: Parser[Token] = withRange("[:,]".r) ^^ { case (_, range) =>
      Token(Branch, "|", range)
    }

    def rawCode: Parser[List[Token]] = withStartPos("#([^#]|#[^}])*#}".r) ^^ {
      case (value, row, col) =>
        super
          .parseAll(super.tokens, value.substring(1, value.length - 2))
          .map { tokens =>
            tokens.map { tok =>
              tok.copy(range =
                tok.range.copy(
                  startRow = row + tok.range.startRow,
                  startCol =
                    if tok.range.startRow == 0 then col + tok.range.startCol
                    else tok.range.startCol
                )
              )
            }
          }
          .get
    }

    override def tokens: Parser[List[Token]] =
      rep(
        (lambdaOpen | word | litListOpen | litListClose | litBranch | litGetVariable | litSetVariable | litAugVariable | unpackVar)
          .map(
            List(_)
          ) | normalGroup | rawCode |
          super.token.map(List(_))
      ).map(_.flatten)

  end LiterateParsers
end LitLexer
