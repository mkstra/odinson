package ai.lum.odinson.compiler

import com.ibm.icu.text.Normalizer2
import fastparse._
import ScriptWhitespace._

class QueryParser(
    val allTokenFields: Seq[String], // the names of all valid token fields
    val defaultTokenField: String,   // the name of the default token field
    val normalizeQueriesToDefaultField: Boolean
) {

  import QueryParser._

  // parser's entry point
  def parseQuery(query: String) = parse(query, odinsonPattern(_)).get.value
  def parseQuery2(query: String) = parse(query, odinsonPattern(_))

  // FIXME temporary entrypoint
  def parseEventQuery(query: String) = parse(query, eventPattern(_), verboseFailures = true).get.value

  def eventPattern[_: P]: P[Ast.EventPattern] = {
    P(Start ~ "trigger" ~ "=" ~ surfacePattern ~ argumentPattern.rep(1) ~ End).map {
      case (trigger, arguments) => Ast.EventPattern(trigger, arguments.toList)
    }
  }

  def argumentPattern[_: P]: P[Ast.ArgumentPattern] = {
    P(existingArgumentPattern | promotedArgumentPattern)
  }

  // the argument must be a mention that already exists in the state
  def existingArgumentPattern[_: P]: P[Ast.ArgumentPattern] = {
    P(Literals.identifier.! ~ ":" ~ Literals.identifier.! ~
      quantifier(includeLazy = false).? ~ "=" ~/
      (disjunctiveTraversal ~ surfacePattern).rep ~ disjunctiveTraversal.?
    ).map {
        case (name, label, None, traversalsWithSurface, lastTraversal) =>
          // the kind of mention we want
          val mention = Ast.MentionPattern(None, label)
          // if the end of the argument pattern is a surface pattern
          // then we want to use it to constrain the retrieved mention
          val pattern = lastTraversal match {
            case Some(t) => traversalsWithSurface :+ (t, mention)
            case None =>
              val (lastHop, lastSurface) = traversalsWithSurface.last
              traversalsWithSurface.init :+ (lastHop, Ast.FilterPattern(mention, lastSurface))
          }
          Ast.ArgumentPattern(name, label, pattern.toList, 1, Some(1), promote = false)
        case (name, label, Some(GreedyQuantifier(min, max)), traversalsWithSurface, lastTraversal) =>
          // the kind of mention we want
          val mention = Ast.MentionPattern(None, label)
          // if the end of the argument pattern is a surface pattern
          // then we want to use it to constrain the retrieved mention
          val pattern = lastTraversal match {
            case Some(t) => traversalsWithSurface :+ (t, mention)
            case None =>
              val (lastHop, lastSurface) = traversalsWithSurface.last
              traversalsWithSurface.init :+ (lastHop, Ast.FilterPattern(mention, lastSurface))
          }
          Ast.ArgumentPattern(name, label, pattern.toList, min, max, promote = false)
        case _ => ??? // we don't support lazy quantifiers
    }
  }

  // the argument will be promoted to a mention if it isn't one already
  def promotedArgumentPattern[_: P]: P[Ast.ArgumentPattern] = {
    P(Literals.identifier.! ~ ":" ~ "^" ~ Literals.identifier.! ~
      quantifier(includeLazy = false).? ~ "=" ~
      (disjunctiveTraversal ~ surfacePattern).rep(1)
    ).map {
        case (name, label, None, traversalsWithSurface) =>
          // TODO should we use Ast.FilterPattern here too?
          Ast.ArgumentPattern(name, label, traversalsWithSurface.toList, 1, Some(1), promote = true)
        case (name, label, Some(GreedyQuantifier(min, max)), traversalsWithSurface) =>
          Ast.ArgumentPattern(name, label, traversalsWithSurface.toList, min, max, promote = true)
        case _ => ??? // we don't support lazy quantifiers
    }
  }

  // grammar's top-level symbol
  def odinsonPattern[_: P]: P[Ast.Pattern] = {
    P(Start ~ graphTraversalPattern ~ End)
  }

  def graphTraversalPattern[_: P]: P[Ast.Pattern] = {
    P(surfacePattern ~ (disjunctiveTraversal ~ surfacePattern).rep).map {
      case (src, rest) => rest.foldLeft(src) {
        case (lhs, (tr, rhs)) => Ast.GraphTraversalPattern(lhs, tr, rhs)
      }
    }
  }

  def surfacePattern[_: P]: P[Ast.Pattern] = {
    P(disjunctivePattern)
  }

  def disjunctivePattern[_: P]: P[Ast.Pattern] = {
    P(concatenatedPattern.rep(min = 1, sep = "|")).map {
      case Seq(pattern) => pattern
      case patterns     => Ast.DisjunctivePattern(patterns.toList)
    }
  }

  def concatenatedPattern[_: P]: P[Ast.Pattern] = {
    P(quantifiedPattern.rep(1)).map {
      case Seq(pattern) => pattern
      case patterns     => Ast.ConcatenatedPattern(patterns.toList)
    }
  }

  def quantifiedPattern[_: P]: P[Ast.Pattern] = {
    P(atomicPattern ~ quantifier(includeLazy = true).?).map {
      case (pattern, None) => pattern
      case (pattern, Some(GreedyQuantifier(min, max))) => Ast.GreedyRepetitionPattern(pattern, min, max)
      case (pattern, Some(LazyQuantifier(min, max)))   => Ast.LazyRepetitionPattern(pattern, min, max)
    }
  }

  def atomicPattern[_: P]: P[Ast.Pattern] = {
    P(constraintPattern | mentionPattern | "(" ~ disjunctivePattern ~ ")" | namedCapturePattern | assertionPattern)
  }

  def mentionPattern[_: P]: P[Ast.Pattern] = {
    P("@" ~ Literals.string).map(label => Ast.MentionPattern(None, label))
  }

  def namedCapturePattern[_: P]: P[Ast.Pattern] = {
    P("(?<" ~ Literals.identifier.! ~ ">" ~ disjunctivePattern ~ ")").map {
      case (name, pattern) => Ast.NamedCapturePattern(name, pattern)
    }
  }

  def constraintPattern[_: P]: P[Ast.Pattern] = {
    P(tokenConstraint).map(Ast.ConstraintPattern)
  }

  def assertionPattern[_: P]: P[Ast.Pattern] = {
    P(sentenceStart | sentenceEnd | lookaround).map(Ast.AssertionPattern)
  }

  ///////////////////////////
  //
  // quantifiers
  //
  ///////////////////////////

  def quantifier[_: P](includeLazy: Boolean): P[Quantifier] = {
    P(quantOperator(includeLazy) | range(includeLazy) | repetition)
  }

  def quantOperator[_: P](includeLazy: Boolean): P[Quantifier] = {
    if (includeLazy) {
      P(StringIn("?", "*", "+", "??", "*?", "+?")).!.map {
        case "?"  => GreedyQuantifier(0, Some(1))
        case "*"  => GreedyQuantifier(0, None)
        case "+"  => GreedyQuantifier(1, None)
        case "??" => LazyQuantifier(0, Some(1))
        case "*?" => LazyQuantifier(0, None)
        case "+?" => LazyQuantifier(1, None)
        case _ => ??? // this shouldn't happen
      }
    } else {
      P(StringIn("?", "*", "+")).!.map {
        case "?"  => GreedyQuantifier(0, Some(1))
        case "*"  => GreedyQuantifier(0, None)
        case "+"  => GreedyQuantifier(1, None)
        case _ => ??? // this shouldn't happen
      }
    }
  }

  def range[_: P](includeLazy: Boolean): P[Quantifier] = {
    if (includeLazy) {
      P("{" ~ Literals.unsignedInt.? ~ "," ~ Literals.unsignedInt.? ~ StringIn("}", "}?").!).flatMap {
        case (Some(min), Some(max), _) if min > max => Fail
        case (None,      maxOption, "}")  => Pass(GreedyQuantifier(0, maxOption))
        case (Some(min), maxOption, "}")  => Pass(GreedyQuantifier(min, maxOption))
        case (None,      maxOption, "}?") => Pass(LazyQuantifier(0, maxOption))
        case (Some(min), maxOption, "}?") => Pass(LazyQuantifier(min, maxOption))
      }
    } else {
      P("{" ~ Literals.unsignedInt.? ~ "," ~ Literals.unsignedInt.? ~ "}").flatMap {
        case (Some(min), Some(max)) if min > max => Fail
        case (None,      maxOption)  => Pass(GreedyQuantifier(0, maxOption))
        case (Some(min), maxOption)  => Pass(GreedyQuantifier(min, maxOption))
      }
    }
  }

  def repetition[_: P]: P[Quantifier] = {
    P("{" ~ Literals.unsignedInt ~ "}").map {
      case n => GreedyQuantifier(n, Some(n))
    }
  }

  ///////////////////////////
  //
  // assertions
  //
  ///////////////////////////

  def sentenceStart[_: P]: P[Ast.Assertion] = {
    P("<s>").map(_ => Ast.SentenceStartAssertion)
  }

  def sentenceEnd[_: P]: P[Ast.Assertion] = {
    P("</s>").map(_ => Ast.SentenceEndAssertion)
  }

  def lookaround[_: P]: P[Ast.Assertion] = {
    P(StringIn("(?=", "(?!", "(?<=", "(?<!").! ~ disjunctivePattern ~ ")").map {
      case ("(?=",  pattern) => Ast.PositiveLookaheadAssertion(pattern)
      case ("(?!",  pattern) => Ast.NegativeLookaheadAssertion(pattern)
      case ("(?<=", pattern) => Ast.PositiveLookbehindAssertion(pattern)
      case ("(?<!", pattern) => Ast.NegativeLookbehindAssertion(pattern)
      case _ => ???
    }
  }

  ///////////////////////////
  //
  // graph traversals
  //
  ///////////////////////////

  def disjunctiveTraversal[_: P]: P[Ast.Traversal] = {
    P(concatenatedTraversal.rep(min = 1, sep = "|")).map {
      case Seq(traversal) => traversal
      case traversals     => Ast.DisjunctiveTraversal(traversals.toList)
    }
  }

  def concatenatedTraversal[_: P]: P[Ast.Traversal] = {
    P(quantifiedTraversal.rep(1)).map {
      case Seq(traversal) => traversal
      case traversals     => Ast.ConcatenatedTraversal(traversals.toList)
    }
  }

  def quantifiedTraversal[_: P]: P[Ast.Traversal] = {
    P(atomicTraversal ~ quantifier(includeLazy = false).?).map {
      case (traversal, None) =>
        traversal
      case (traversal, Some(GreedyQuantifier(1, Some(1)))) =>
        traversal
      case (_, Some(GreedyQuantifier(0, Some(0)))) =>
        Ast.NoTraversal
      case (traversal, Some(GreedyQuantifier(0, Some(1)))) =>
        Ast.OptionalTraversal(traversal)
      case (traversal, Some(GreedyQuantifier(0, None))) =>
        Ast.KleeneStarTraversal(traversal)
      case (traversal, Some(GreedyQuantifier(n, None))) =>
        val clauses = List.fill(n)(traversal) :+ Ast.KleeneStarTraversal(traversal)
        Ast.ConcatenatedTraversal(clauses)
      case (traversal, Some(GreedyQuantifier(m, Some(n)))) if m == n =>
        val clauses = List.fill(n)(traversal)
        Ast.ConcatenatedTraversal(clauses)
      case (traversal, Some(GreedyQuantifier(m, Some(n)))) if m < n =>
        val required = List.fill(n)(traversal)
        val optional = List.fill(n - m)(Ast.OptionalTraversal(traversal))
        Ast.ConcatenatedTraversal(required ++ optional)
    }
  }

  def atomicTraversal[_: P]: P[Ast.Traversal] = {
    P(singleStepTraversal | "(" ~ disjunctiveTraversal ~ ")")
  }

  def singleStepTraversal[_: P]: P[Ast.Traversal] = {
    P(outgoingTraversal | incomingTraversal | outgoingWildcard | incomingWildcard)
  }

  def incomingWildcard[_: P]: P[Ast.Traversal] = {
    P("<<").map(_ => Ast.IncomingWildcard)
  }

  def incomingTraversal[_: P]: P[Ast.Traversal] = {
    P("<" ~ anyMatcher).map(Ast.IncomingTraversal)
  }

  def outgoingWildcard[_: P]: P[Ast.Traversal] = {
    P(">>").map(_ => Ast.OutgoingWildcard)
  }

  def outgoingTraversal[_: P]: P[Ast.Traversal] = {
    P(">" ~ anyMatcher).map(Ast.OutgoingTraversal)
  }

  ///////////////////////////
  //
  // token constraints
  //
  ///////////////////////////

  def tokenConstraint[_: P]: P[Ast.Constraint] = {
    P(explicitConstraint | defaultFieldConstraint)
  }

  // unicode/case normalization
  private val normalizer = Normalizer2.getNFKCCasefoldInstance()
  private def maybeNormalize(s: String): String = {
    if (normalizeQueriesToDefaultField) normalizer.normalize(s) else s
  }

  def defaultFieldConstraint[_: P]: P[Ast.Constraint] = {
    P(defaultFieldRegexConstraint | defaultFieldStringConstraint)
  }

  def defaultFieldStringConstraint[_: P]: P[Ast.Constraint] = {
    // a negative lookahead is required to ensure that this constraint
    // is not followed by a colon, if it is then it actually is an argument name
    P(Literals.string ~ !":" ~ "~".!.?).map {
      case (string, None) =>
        Ast.FieldConstraint(defaultTokenField, Ast.StringMatcher(maybeNormalize(string)))
      case (string, Some(_)) =>
        Ast.FuzzyConstraint(defaultTokenField, Ast.StringMatcher(maybeNormalize(string)))
    }
  }

  def defaultFieldRegexConstraint[_: P]: P[Ast.Constraint] = {
    P(Literals.regex).map { regex =>
      Ast.FieldConstraint(defaultTokenField, Ast.RegexMatcher(maybeNormalize(regex)))
    }
  }

  def explicitConstraint[_: P]: P[Ast.Constraint] = {
    P("[" ~ disjunctiveConstraint.? ~ "]").map {
      case None             => Ast.Wildcard
      case Some(constraint) => constraint
    }
  }

  def disjunctiveConstraint[_: P]: P[Ast.Constraint] = {
    P(conjunctiveConstraint.rep(min = 1, sep = "|")).map {
      case Seq(constraint) => constraint
      case constraints     => Ast.DisjunctiveConstraint(constraints.toList)
    }
  }

  def conjunctiveConstraint[_: P]: P[Ast.Constraint] = {
    P(negatedConstraint.rep(min = 1, sep = "&")).map {
      case Seq(constraint) => constraint
      case constraints     => Ast.ConjunctiveConstraint(constraints.toList)
    }
  }

  def negatedConstraint[_: P]: P[Ast.Constraint] = {
    P("!".!.? ~ atomicConstraint).map {
      case (None, constraint)    => constraint
      case (Some(_), constraint) => Ast.NegatedConstraint(constraint)
    }
  }

  def atomicConstraint[_: P]: P[Ast.Constraint] = {
    P(fieldConstraint | "(" ~ disjunctiveConstraint ~ ")")
  }

  def fieldConstraint[_: P]: P[Ast.Constraint] = {
    P(regexFieldConstraint | stringFieldConstraint)
  }

  def regexFieldConstraint[_: P]: P[Ast.Constraint] = {
    P(fieldName ~ StringIn("=", "!=").! ~ regexMatcher).map {
      case (name, "=",  matcher) => Ast.FieldConstraint(name, matcher)
      case (name, "!=", matcher) => Ast.NegatedConstraint(Ast.FieldConstraint(name, matcher))
      case _ => ??? // this shouldn't happen
    }
  }

  def stringFieldConstraint[_: P]: P[Ast.Constraint] = {
    P(fieldName ~ StringIn("=", "!=").! ~ extendedStringMatcher ~ "~".!.?).map {
      case (name, "=",  matcher, None)    => Ast.FieldConstraint(name, matcher)
      case (name, "!=", matcher, None)    => Ast.NegatedConstraint(Ast.FieldConstraint(name, matcher))
      case (name, "=",  matcher, Some(_)) => Ast.FuzzyConstraint(name, matcher)
      case (name, "!=", matcher, Some(_)) => Ast.NegatedConstraint(Ast.FuzzyConstraint(name, matcher))
      case _ => ??? // this shouldn't happen
    }
  }

  // any value in `allTokenFields` is a valid field name
  def fieldName[_: P]: P[String] = {
    P(Literals.identifier).flatMap { identifier =>
      if (allTokenFields contains identifier) Pass(identifier) else Fail
    }
  }

  def anyMatcher[_: P]: P[Ast.Matcher] = {
    P(extendedStringMatcher | regexMatcher)
  }

  def extendedStringMatcher[_: P]: P[Ast.StringMatcher] = {
    P(Literals.extendedString.map(Ast.StringMatcher))
  }

  def stringMatcher[_: P]: P[Ast.StringMatcher] = {
    P(Literals.string.map(Ast.StringMatcher))
  }

  def regexMatcher[_: P]: P[Ast.RegexMatcher] = {
    P(Literals.regex.map(Ast.RegexMatcher))
  }

}

object QueryParser {

  sealed trait Quantifier
  case class GreedyQuantifier(min: Int, max: Option[Int]) extends Quantifier
  case class LazyQuantifier(min: Int, max: Option[Int]) extends Quantifier

}
